# SPDX-License-Identifier: Apache-2.0
"""Bounded restoration of a frozen source list; only owned train copies are reclaimable."""
import hashlib
import json
import os
from pathlib import Path
import stat
import threading
import time
import urllib.parse
import urllib.request
import numpy as np
from metering_data import decode_srgb, roi_definitions
from common import digest, write_json


def confined(root, path):
    root, path = Path(root).absolute(), Path(path).absolute()
    # Check lexical containment AND each ancestor before resolving any reparse point.
    if path == root or root not in path.parents:
        raise ValueError('Outside owned cache directory')
    for ancestor in root.parents:
        if ancestor.exists() and (ancestor.is_symlink() or getattr(ancestor.lstat(),'st_file_attributes',0) & 1024):
            raise ValueError('Owned cache ancestor is a reparse point')
    current = root
    for piece in ('', *path.relative_to(root).parts):
        if piece:
            current = current/piece
        if current.exists() or current.is_symlink():
            attrs = getattr(current.lstat(), 'st_file_attributes', 0)
            if current.is_symlink() or attrs & getattr(stat,'FILE_ATTRIBUTE_REPARSE_POINT',1024):
                raise ValueError('Cache refuses symlinks/junctions/reparse points')
    if root.resolve() not in path.resolve().parents:
        raise ValueError('Resolved cache path escapes owned directory')
    return path


def pool_bytes(pool):
    total = 0
    for base, dirs, files in os.walk(pool, followlinks=False):
        for name in list(dirs):
            p=Path(base)/name
            if p.is_symlink() or getattr(p.lstat(),'st_file_attributes',0) & 1024:
                raise ValueError('Reparse point inside bounded pool')
        for name in files:
            p=Path(base)/name
            if p.is_symlink() or getattr(p.lstat(),'st_file_attributes',0) & 1024:
                raise ValueError('Reparse file inside bounded pool')
            try: total += p.stat().st_size
            except FileNotFoundError: pass
    return total


def allowed_url(url):
    parsed=urllib.parse.urlsplit(url)
    host=(parsed.hostname or '').lower()
    if parsed.scheme != 'https' or parsed.username or not any(host==h or host.endswith('.'+h) for h in ('wikimedia.org','huggingface.co','hf.co')):
        raise ValueError('Download host/protocol outside frozen-source allowlist')
    return url


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        allowed_url(newurl)
        return super().redirect_request(req,fp,code,msg,headers,newurl)


class Cache:
    def __init__(self, config, rows, ledger_factory):
        self.config=config; self.rows=rows; self.pool=Path(config['pool']).resolve()
        self.owned=Path(config['owned_cache']).absolute()
        if self.owned.parent != self.pool or self.owned.name != 'metering-candidates-20261106':
            raise ValueError('Unexpected exclusively owned cache root')
        confined(self.pool,self.owned)
        self.owned.mkdir(exist_ok=True)
        for name in ('originals','replay'):
            confined(self.owned,self.owned/name).mkdir(exist_ok=True)
        self.lock=threading.RLock(); self.stop=threading.Event(); self.failures={}; self.downloaded={}
        self.ledger_factory=ledger_factory; self.progress_path=self.owned/'cache-status.json'
        self.opener=urllib.request.build_opener(SafeRedirect())
        self.verified={}
        journal=self.owned/'owned-downloads.json'
        if journal.exists():self.downloaded=json.loads(journal.read_text(encoding='utf-8'))
        pending=self.owned/'pending-reclaims.json'
        self.reclaim_queue=json.loads(pending.read_text(encoding='utf-8')) if pending.exists() else {}

    def replay_path(self,row):
        if len(row['sha256'])!=64 or any(c not in '0123456789abcdef' for c in row['sha256']):
            raise ValueError('Invalid source identity')
        return confined(self.owned,self.owned/'replay'/(row['sha256']+'.npy'))

    def status(self,phase,**extra):
        write_json(self.progress_path,dict(state=phase,pid=os.getpid(),at=time.time(),
            pool_bytes=pool_bytes(self.pool),limit_bytes=self.config['pool_limit_bytes'],
            failures=self.failures,**extra))

    def load(self,row):
        p=self.replay_path(row); info=p.stat(); key=(info.st_size,info.st_mtime_ns)
        if self.verified.get(row['sha256'])!=key:
            record=json.loads(p.with_suffix('.json').read_text(encoding='utf-8'))
            if record['source_sha256']!=row['sha256'] or record['split']!=row['split'] or digest(p)!=record['cache_sha256']:
                raise ValueError('Replay identity/hash changed')
            self.verified[row['sha256']]=key
        return np.load(p,allow_pickle=False)

    def room(self, bytes_needed):
        return pool_bytes(self.pool)+bytes_needed+16*1024**2 <= self.config['pool_limit_bytes']

    def acquire_original(self,row):
        p=Path(row['provenance']['image'])
        if p.is_file():
            if digest(p)!=row['sha256']: raise ValueError('Existing source hash changed')
            return p
        if row['split']!='train': raise ValueError('Never download/replace fixed held-out originals')
        target=confined(self.owned,self.owned/'originals'/(row['sha256']+'.image'))
        if target.exists():
            if digest(target)!=row['sha256']:raise ValueError('Owned source hash changed')
            return target
        maximum=80*1024**2
        expected=row['provenance'].get('file_bytes',maximum)
        if expected>maximum:raise ValueError('Source exceeds per-file budget')
        if not self.room(maximum):return None
        url=allowed_url(row['provenance']['download_url'])
        partial=confined(self.owned,target.with_suffix('.part'))
        started=time.monotonic(); count=0
        try:
            request=urllib.request.Request(url,headers={'User-Agent':'lightstop-metering-research/1.0 (https://github.com/the-waterwheel/lightstop)'})
            with self.opener.open(request,timeout=20) as response, partial.open('wb') as out:
                allowed_url(response.geturl())
                length=int(response.headers.get('Content-Length','0'))
                if length>maximum:raise ValueError('Response exceeds download budget')
                while True:
                    if self.stop.is_set():raise InterruptedError('Stopped')
                    block=response.read(256*1024)
                    if not block:break
                    count+=len(block)
                    if count>maximum or time.monotonic()-started>180:raise ValueError('Download bounded limit reached')
                    if not self.room(len(block)):raise ValueError('Pool full while downloading')
                    out.write(block)
            if digest(partial)!=row['sha256']:raise ValueError('Downloaded original differs from frozen SHA256')
            # The frozen SHA, source licence, scene group and held-out split remain authoritative.
            os.replace(partial,target)
            self.downloaded[row['sha256']]=dict(path=str(target),source_url=url,at=time.time())
            write_json(self.owned/'owned-downloads.json',self.downloaded)
            return target
        finally:
            if partial.exists():confined(self.owned,partial).unlink()

    def prepare(self,row,allow_download=True):
        with self.lock:
            target=self.replay_path(row)
            if target.exists():
                array=self.load(row)
                if array.shape!=(16,3,128,192) or array.dtype!=np.uint8:raise ValueError('Replay contract changed')
                return target
            source=Path(row['provenance']['image'])
            if not allow_download and not source.is_file():return None
            if not self.room(16*3*128*192+4096):return None
            ledger=self.ledger_factory(); owner=str(os.getpid())
            try:
                with ledger.db:
                    asset=ledger.db.execute('SELECT quality,global_split,group_id FROM shared_assets WHERE sha256=?',(row['sha256'],)).fetchone()
                    if not asset or tuple(asset)!=('accepted',row['split'],row['group']):
                        raise ValueError('Frozen source no longer admitted under the original split/group')
                    ledger.db.execute('INSERT OR IGNORE INTO shared_read_leases VALUES(?,?,?,?,?)',
                        ('metering-candidates','prepare',row['sha256'],owner,str(time.time())))
                source=self.acquire_original(row)
                if source is None:return None
                rgb,_=decode_srgb(source); h,w=rgb.shape[:2]
                data=[]
                for roi in roi_definitions():
                    rw,rh=w/roi['zoom'],h/roi['zoom']; left=(w-rw)*roi['x']; top=(h-rh)*roi['y']
                    xs=np.floor(left+(np.arange(192)+.5)*rw/192).astype(int).clip(0,w-1)
                    ys=np.floor(top+(np.arange(128)+.5)*rh/128).astype(int).clip(0,h-1)
                    data.append(rgb[ys[:,None],xs[None,:],:].transpose(2,0,1))
                partial=confined(self.owned,target.with_suffix('.tmp'))
                with partial.open('wb') as f:np.save(f,np.stack(data),allow_pickle=False)
                write_json(target.with_suffix('.json'),dict(source_sha256=row['sha256'],cache_sha256=digest(partial),
                    shape=[16,3,128,192],split=row['split'],source_url=row['provenance']['source_url']))
                os.replace(partial,target)
                return target
            finally:
                with ledger.db:ledger.db.execute('DELETE FROM shared_read_leases WHERE consumer=? AND batch_id=? AND asset_sha256=? AND owner=?',
                    ('metering-candidates','prepare',row['sha256'],owner))
                ledger.close()

    def collect(self,rows,checkpoints):
        for row in rows:
            if row['sha256'] in self.downloaded:
                self.reclaim_queue[row['sha256']]=dict(row=row,checkpoints=checkpoints)
        write_json(self.owned/'pending-reclaims.json',self.reclaim_queue)
        # Slow network acquisition must never block the optimizer waiting for cleanup.
        if not self.lock.acquire(blocking=False):return
        try:
            for sha,item in list(self.reclaim_queue.items()):
                self._collect([item['row']],item['checkpoints'])
                entry=self.downloaded.get(sha)
                if entry and not Path(entry['path']).exists():self.reclaim_queue.pop(sha,None)
            write_json(self.owned/'pending-reclaims.json',self.reclaim_queue)
        finally:self.lock.release()

    def _collect(self,rows,checkpoints):
        """Only copies created by this job, after all three durable commits; never shared originals."""
        with self.lock:
            ledger=self.ledger_factory()
            try:
                for row in rows:
                    entry=self.downloaded.get(row['sha256'])
                    if row['split']!='train' or not entry:continue
                    if ledger.db.execute('SELECT 1 FROM shared_read_leases WHERE asset_sha256=?',(row['sha256'],)).fetchone():continue
                    if not ledger.may_reclaim(row['sha256']):continue
                    if not all(Path(p).is_file() and digest(p)==sha for p,sha in checkpoints.values()):
                        raise ValueError('Committed candidate checkpoint not durable')
                    path=confined(self.owned,Path(entry['path']))
                    if path.parent != self.owned/'originals' or path.name != row['sha256']+'.image':
                        raise ValueError('Unexpected owned original identity')
                    if not path.exists():continue
                    if digest(path)!=row['sha256']:raise ValueError('Owned reclaim identity changed')
                    replay=self.replay_path(row)
                    record=json.loads(replay.with_suffix('.json').read_text(encoding='utf-8'))
                    if record['cache_sha256']!=digest(replay):raise ValueError('Replay must survive original reclamation')
                    receipt=self.owned/(row['sha256']+'.reclaim.json')
                    write_json(receipt,dict(state='intent',path=str(path),sha256=row['sha256'],checkpoints=checkpoints,at=time.time()))
                    try:path.unlink()
                    except PermissionError:
                        write_json(receipt,dict(state='retained-file-in-use',path=str(path),at=time.time()));continue
                    write_json(receipt,dict(state='reclaimed',path=str(path),sha256=row['sha256'],checkpoints=checkpoints,at=time.time()))
            finally:ledger.close()

    def producer(self):
        while not self.stop.is_set():
            pending=0
            # Reuse the 203 surviving train originals before a slow restoration can delay startup.
            ordered=sorted(self.rows,key=lambda r:not Path(r['provenance']['image']).is_file())
            for row in ordered:
                if self.stop.is_set():return
                if self.replay_path(row).exists():continue
                pending+=1
                try:
                    result=self.prepare(row)
                    if result:self.failures.pop(row['sha256'],None)
                    self.status('preparing' if result else 'capacity-wait-training-continues',pending=pending)
                except Exception as e:
                    self.failures[row['sha256']]=str(e)
                    self.status('source-retry-training-continues',pending=pending)
            pending=sum(not self.replay_path(row).exists() for row in self.rows)
            self.status('ready' if not pending else 'waiting-capacity-or-source',pending=pending)
            if not pending:return
            self.stop.wait(60)
