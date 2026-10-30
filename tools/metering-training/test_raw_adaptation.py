# SPDX-License-Identifier: Apache-2.0
"""Photometric, held-out partition and exact optimizer-resume contracts."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
import torch

from metering_domains import (DomainAugmenter, RawCalibration, inverse_smoothstep,
                              processed_to_linear, processed_rgb_to_linear, raw_rgb_to_linear, render_processed,
                              smoothstep, srgb_decode, srgb_encode)
from metering_features import FeatureExtractor
from metering_model import ExposureMLP
from raw_adaptation import capture_rng, initialize, loss_step, pause_identity, restore_rng, train_locked, tree_hash
from raw_adaptation_data import read, validate_rows
from raw_adaptation_evaluation import baseline_scores
from metering_baselines import scores as numpy_baseline_scores


class DomainContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)
        cls.config = read(Path(__file__).with_name("raw_adaptation_config.json"))

    def test_standard_transfer_roundtrip(self):
        x = torch.linspace(0, 1, 1025)
        self.assertTrue(torch.allclose(srgb_decode(srgb_encode(x)), x, atol=3e-7))

    def test_residual_tone_inverse_including_endpoints(self):
        x = torch.linspace(0, 1, 1001)
        self.assertTrue(torch.allclose(inverse_smoothstep(smoothstep(x)), x, atol=5e-6))
        self.assertEqual(float(inverse_smoothstep(torch.tensor(0.))), 0)
        self.assertEqual(float(inverse_smoothstep(torch.tensor(1.))), 1)

    def test_exposure_occurs_before_the_nonlinear_tone(self):
        x = torch.tensor([.1])
        physical = smoothstep(2*x)
        old_style = 2*smoothstep(x)
        self.assertGreater(float((physical-old_style).abs()), .03)
        restored = processed_to_linear(render_processed(2*x, quantize=False))
        self.assertTrue(torch.allclose(restored, 2*x, atol=1e-6))

    def test_raw_black_white_wb_and_existing_ev_calibration(self):
        c = RawCalibration(black=(64,64,64), white=(1023,1023,1023), gains=(2,1,1.5), correction_ev=1)
        reference = torch.tensor([.1,.2,.3])[None,:,None,None]
        native = reference/torch.tensor(c.gains)[None,:,None,None]
        out = raw_rgb_to_linear(native*959+64, c)
        self.assertTrue(torch.allclose(out, 2*reference, atol=1e-6))

    def test_raw_calibration_keeps_global_brightness(self):
        a = raw_rgb_to_linear(torch.full((1,3,2,2), 400.), RawCalibration())
        b = raw_rgb_to_linear(torch.full((1,3,2,2), 800.), RawCalibration())
        self.assertTrue(torch.allclose(b, 2*a))

    def test_invalid_raw_metadata_is_rejected(self):
        with self.assertRaises(ValueError):
            raw_rgb_to_linear(torch.ones(1,3,2,2), RawCalibration(white=(0,0,0)))

    def test_reported_inverse_lut_is_not_double_gamma_decoded(self):
        x = torch.full((1,3,2,2), .25)
        lut = torch.linspace(0,1,1025).repeat(3,1)
        self.assertTrue(torch.equal(processed_rgb_to_linear(x,inverse_luts=lut),x))

    def test_existing_multi_point_ev_response_is_reused(self):
        x = torch.full((1,3,2,2), .2)
        lut = torch.linspace(0,1,1025).repeat(3,1)
        out = processed_rgb_to_linear(x,inverse_luts=lut,response_anchors=((.1,0),(.3,1)))
        self.assertTrue(torch.allclose(out,x*2**.5,atol=1e-6))

    def test_known_virtual_isp_aligns_raw_and_processed(self):
        aug = DomainAugmenter(self.config)
        bank = torch.full((2,3,64,96), 100, dtype=torch.uint8)
        raw, processed = aug.pair(bank, torch.tensor([-.5,.5]), noise=False, quantize=False)
        self.assertTrue(torch.allclose(raw, processed, atol=2e-6))
        extractor = FeatureExtractor()
        self.assertEqual(tuple(extractor.from_linear(raw).shape), (2,776))
        self.assertTrue(torch.allclose(extractor.from_linear(raw), extractor.from_linear(processed), atol=1e-5))

    def test_one_stop_remains_one_stop_in_the_canonical_domain(self):
        aug = DomainAugmenter(self.config); bank = torch.full((1,3,64,96), 80, dtype=torch.uint8)
        a,_ = aug.pair(bank, torch.zeros(1), noise=False, quantize=False)
        b,_ = aug.pair(bank, torch.ones(1), noise=False, quantize=False)
        self.assertTrue(torch.allclose(b, 2*a, atol=1e-6))
        extractor = FeatureExtractor()
        difference = extractor.from_linear(b)[0,768]-extractor.from_linear(a)[0,768]
        self.assertAlmostEqual(float(difference), 1, places=5)

    def test_quality_features_preserve_clipped_area(self):
        x = torch.ones(1,3,64,96)
        f = FeatureExtractor().from_linear(x)
        self.assertEqual(float(f[0,771]), 1)
        self.assertEqual(float(f[0,773]), 1)

    def test_adaptation_baselines_match_independent_policy_port(self):
        torch.manual_seed(52)
        linear=torch.rand(3,3,64,96)*.6
        features=FeatureExtractor().from_linear(linear)
        got,valid=baseline_scores(linear,features)
        expected=numpy_baseline_scores(linear.numpy())
        for name,values in got.items():
            self.assertTrue(np.allclose(values.numpy(),expected[name],atol=2e-6))
        self.assertTrue(np.array_equal(valid.numpy(),expected['base_reliable']))

    def row(self, identity, split, group, phash):
        return dict(id=identity, sha256=identity, split=split, group=group, phash=phash,
                    quality="accepted", licence="CC0-1.0", provenance=dict(source_url="https://example.org/photo",license_url="https://example.org/license"))

    def test_shared_group_and_phash_split_is_enforced(self):
        a = self.row("a","train","g1","p1"); b = self.row("b","val","g1","p2")
        with self.assertRaises(ValueError): validate_rows([a,b])
        b["group"] = "g2"; b["phash"] = "p1"
        with self.assertRaises(ValueError): validate_rows([a,b])

    def test_test_and_quarantined_sources_cannot_become_training(self):
        row = self.row("a","test","g1","p1")
        with self.assertRaises(ValueError): validate_rows([row])
        row.update(split="train",quality="quarantined")
        with self.assertRaises(ValueError): validate_rows([row])

    def test_weights_moments_normalization_and_next_update_restore(self):
        device = torch.device("cpu"); torch.manual_seed(7)
        parent_model = ExposureMLP(mean=torch.randn(776), std=torch.rand(776)+.2)
        parent_optimizer = torch.optim.AdamW(parent_model.parameters(), lr=.001)
        parent_model(torch.randn(3,776)).square().mean().backward(); parent_optimizer.step()
        parent = dict(model=copy.deepcopy(parent_model.state_dict()), optimizer=copy.deepcopy(parent_optimizer.state_dict()),rng=capture_rng(device))
        model, opt, scheduler = initialize(parent,self.config,device)
        self.assertEqual(tree_hash(model.state_dict()),tree_hash(parent["model"]))
        self.assertEqual(tree_hash(opt.state_dict()["state"]),tree_hash(parent["optimizer"]["state"]))
        bank = torch.randint(30,180,(3,3,64,96),dtype=torch.uint8)
        ext=FeatureExtractor();aug=DomainAugmenter(self.config)
        loss_step(model,opt,ext,aug,bank,self.config)
        stream=io.BytesIO()
        torch.save(dict(model=model.state_dict(),optimizer=opt.state_dict(),scheduler=scheduler.state_dict(),rng=capture_rng(device)),stream)
        loss_step(model,opt,ext,aug,bank,self.config);expected=tree_hash([model.state_dict(),opt.state_dict()])
        stream.seek(0);saved=torch.load(stream,map_location="cpu",weights_only=True)
        clone,clone_opt,clone_schedule=initialize(parent,self.config,device)
        clone.load_state_dict(saved["model"]);clone_opt.load_state_dict(saved["optimizer"])
        clone_schedule.load_state_dict(saved["scheduler"]);restore_rng(saved["rng"],device)
        loss_step(clone,clone_opt,ext,aug,bank,self.config)
        self.assertEqual(expected,tree_hash([clone.state_dict(),clone_opt.state_dict()]))

    def test_mid_epoch_pause_resume_matches_uninterrupted_training(self):
        from unittest.mock import patch
        device=torch.device("cpu");torch.manual_seed(24)
        model=ExposureMLP();opt=torch.optim.AdamW(model.parameters(),lr=.001)
        model(torch.randn(2,776)).square().mean().backward();opt.step()
        parent=dict(model=model.state_dict(),optimizer=opt.state_dict(),rng=capture_rng(device))
        config=dict(self.config,batch_size=8,validation_shifts_ev=[0.],checkpoint_every_steps=1)
        with tempfile.TemporaryDirectory() as temporary:
            dirs=[Path(temporary)/n for n in ("paused","continuous")]
            for folder in dirs:
                (folder/"data/cache").mkdir(parents=True)
                torch.save(parent,folder/"parent.pt");(folder/"prepared.json").write_text("{}",encoding="utf-8")
                rows=[]
                for split in ("train","val"):
                    np.save(folder/f"data/cache/{split}.npy",np.full((16,3,64,96),100,dtype=np.uint8))
                    rows.append(dict(id=split,split=split,cache=f"cache/{split}.npy"))
                (folder/"data/manifest.json").write_text(json.dumps(dict(rows=rows)),encoding="utf-8")
            plan=dict(parent_epoch=134,budget_epochs=1,parent_checkpoint="fixture-parent",parent_sha256="fixture")
            paused,continuous=dirs;marker=paused/"PAUSE.json"
            original_step=loss_step
            def step_and_pause(*args,**kwargs):
                result=original_step(*args,**kwargs);marker.write_text("pause",encoding="utf-8");return result
            with patch("raw_adaptation.loss_step",side_effect=step_and_pause):
                result=train_locked(paused,plan,config,device,{str(marker):None})
            self.assertEqual(result["state"],"paused")
            partial=torch.load(paused/"last.pt",map_location="cpu",weights_only=True)
            self.assertEqual(partial["progress"]["cursor"],8)
            train_locked(paused,plan,config,device,{str(marker):pause_identity(marker)})
            train_locked(continuous,plan,config,device,{})
            a=torch.load(paused/"last.pt",map_location="cpu",weights_only=True)
            b=torch.load(continuous/"last.pt",map_location="cpu",weights_only=True)
            self.assertEqual(tree_hash([a[k] for k in ("model","optimizer","scheduler","rng","progress")]),
                             tree_hash([b[k] for k in ("model","optimizer","scheduler","rng","progress")]))


if __name__ == "__main__": unittest.main(verbosity=2)
