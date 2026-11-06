# SPDX-License-Identifier: Apache-2.0
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import numpy as np
import torch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'shared-training'))
from common import digest,write_json
from ledger import Ledger
from candidate_cache import Cache,confined,allowed_url
from candidate_models import Candidate,stats_input
from candidate_training import metric,loss_for


class CandidateTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(2)
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.pool=self.root/'pool';self.pool.mkdir();self.owned=self.pool/'metering-candidates-20261106'
        self.db=self.pool/'continuous-registry.sqlite3';l=Ledger(self.db);l.initialize();l.close()
        self.config=dict(pool=str(self.pool),owned_cache=str(self.owned),pool_limit_bytes=12*1024**3)
        self.cache=Cache(self.config,[],lambda:Ledger(self.db))
    def tearDown(self):self.temp.cleanup()

    def row(self,split='train'):
        return dict(sha256='a'*64,id='one',split=split,group='one',provenance=dict(image=str(self.root/'missing'),source_url='https://commons.wikimedia.org/wiki/File:one',download_url='https://upload.wikimedia.org/one'))

    def test_paths_refuse_sibling_parent_and_symlink_escape(self):
        for p in (self.pool/'elsewhere',self.owned,self.owned/'../elsewhere'):
            with self.assertRaises(ValueError):confined(self.owned,p)
        self.assertEqual(confined(self.owned,self.owned/'originals'/'one'),self.owned/'originals'/'one')
        try:(self.owned/'link').symlink_to(self.root,target_is_directory=True)
        except OSError:return
        with self.assertRaises(ValueError):confined(self.owned,self.owned/'link'/'one')

    def test_download_allowlist_rejects_http_credentials_and_suffix_spoofing(self):
        for url in ('http://upload.wikimedia.org/a','https://upload.wikimedia.org.evil/a','https://evil/a','https://user@upload.wikimedia.org/a'):
            with self.assertRaises(ValueError):allowed_url(url)
        self.assertEqual(allowed_url('https://upload.wikimedia.org/a'),'https://upload.wikimedia.org/a')

    def test_full_pool_does_not_download_or_prevent_cached_reads(self):
        row=self.row();p=self.cache.replay_path(row)
        data=np.zeros((16,3,128,192),np.uint8)
        with p.open('wb') as f:np.save(f,data)
        write_json(p.with_suffix('.json'),dict(source_sha256=row['sha256'],cache_sha256=digest(p),split='train'))
        self.config['pool_limit_bytes']=1
        self.assertFalse(self.cache.room(1));self.assertTrue(np.array_equal(self.cache.load(row),data))
        with patch.object(self.cache.opener,'open',side_effect=AssertionError('network must not be used')):
            self.assertIsNone(self.cache.acquire_original(row))

    def test_fixed_validation_never_downloaded(self):
        with self.assertRaises(ValueError):self.cache.acquire_original(self.row('val'))

    def test_replay_tampering_rejected(self):
        row=self.row();p=self.cache.replay_path(row)
        p.write_bytes(b'corrupt')
        write_json(p.with_suffix('.json'),dict(source_sha256=row['sha256'],cache_sha256='0'*64,split='train'))
        with self.assertRaises(ValueError):self.cache.load(row)

    def reclaim_fixture(self):
        content=b'owned original';sha=hashlib.sha256(content).hexdigest();row=self.row();row['sha256']=sha
        p=self.owned/'originals'/(sha+'.image');p.write_bytes(content)
        self.cache.downloaded[sha]=dict(path=str(p))
        replay=self.cache.replay_path(row);replay.write_bytes(b'verified replay')
        write_json(replay.with_suffix('.json'),dict(source_sha256=sha,cache_sha256=digest(replay),split='train'))
        l=Ledger(self.db)
        l.register([dict(sha256=sha,id='one',image=str(self.pool/'shared.jpg'),phash='one',phash_version='test',group_id='one',global_split='train',quality_state='accepted',licence='CC0-1.0',source_record={},exif={})],{'one':dict(split='train',evidence=[])})
        commit=self.root/'checkpoint.pt';commit.write_bytes(b'durable state')
        for consumer in ('dust','metering','metering-candidates-A','metering-candidates-B','metering-candidates-C'):
            l.batch(consumer,'batch','manifest',[row],None,{},required=16)
            l.commit(consumer,'batch',commit,{}, {sha:16})
        l.close()
        return row,p,commit

    def test_reclaim_requires_all_consumers_and_preserves_replay(self):
        row,p,commit=self.reclaim_fixture();l=Ledger(self.db)
        l.batch('other-future-job','new','manifest',[row],None,{},required=1);l.close()
        self.cache.collect([row],{k:(str(commit),digest(commit)) for k in 'ABC'});self.assertTrue(p.exists())
        l=Ledger(self.db);l.commit('other-future-job','new',commit,{}, {row['sha256']:1});l.close()
        self.cache.collect([row],{k:(str(commit),digest(commit)) for k in 'ABC'})
        self.assertFalse(p.exists());self.assertTrue(self.cache.replay_path(row).exists())

    def test_read_lease_and_invalid_commit_block_deletion(self):
        row,p,commit=self.reclaim_fixture();l=Ledger(self.db)
        with l.db:l.db.execute('insert into shared_read_leases values(?,?,?,?,?)',('another','b',row['sha256'],'reader','now'))
        l.close();self.cache.collect([row],{k:(str(commit),'wrong') for k in 'ABC'});self.assertTrue(p.exists())
        l=Ledger(self.db)
        with l.db:l.db.execute('delete from shared_read_leases')
        l.close()
        with self.assertRaises(ValueError):self.cache.collect([row],{k:(str(commit),'wrong') for k in 'ABC'})
        self.assertTrue(p.exists())

    def test_unowned_original_is_never_removed(self):
        row,p,commit=self.reclaim_fixture();self.cache.downloaded.clear()
        self.cache.collect([row],{k:(str(commit),digest(commit)) for k in 'ABC'});self.assertTrue(p.exists())

    def test_metrics_macro_average_by_original_not_roi_count(self):
        m=metric([1,1,1,3],[0,0,0,0],['a','a','a','b'])
        self.assertEqual(m['mae_ev'],2.0)

    def test_all_models_have_finite_gradients_and_two_outputs(self):
        x=torch.rand(2,3,128,192);mean=torch.zeros(776);std=torch.ones(776)
        for name in 'ABC':
            model=Candidate(name,mean,std)
            loss,_=loss_for(model,[x,x,x],[torch.zeros(2)]*3,torch.ones(2,dtype=torch.bool),{})
            loss.backward();self.assertTrue(torch.isfinite(loss));self.assertEqual(model(x).shape,(2,2))
            self.assertTrue(all(p.grad is None or torch.isfinite(p.grad).all() for p in model.parameters()))

    def test_stats_subsampling_and_exposure_coordinate(self):
        from metering_features import FeatureExtractor
        x=torch.full((1,3,128,192),.1);f=FeatureExtractor()
        self.assertEqual(stats_input(x).shape,(1,3,64,96))
        self.assertAlmostEqual(float(f.from_linear(stats_input(x*2))[0,768]-f.from_linear(stats_input(x))[0,768]),1.0,places=5)


if __name__=='__main__':unittest.main()
