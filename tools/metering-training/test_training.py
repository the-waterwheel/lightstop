# SPDX-License-Identifier: Apache-2.0
import copy
import json
import random
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image
import torch

from metering_data import (decode_srgb, prepare_cache, read_manifest, roi_bounds, roi_definitions,
                           sample_roi, sha256, verify_cache)
from metering_evaluation import calibrate_uncertainty, subset_metrics
from metering_features import FeatureExtractor, feature_spec, interval_weights, srgb_decode
from metering_model import ExposureMLP
from train import (atomic_checkpoint, export_model, read_training_state, restore_rng,
                   rng_state, run_all, seed_everything, training_loss)

CONFIG = json.loads((Path(__file__).parent / "config.json").read_text(encoding="utf-8"))
torch.set_num_threads(1)


class FeatureTests(unittest.TestCase):
    def setUp(self):
        self.extractor = FeatureExtractor()

    def test_uniform_middle_gray_is_zero_ev(self):
        features = self.extractor.from_linear(torch.full((1, 3, 64, 96), 0.18))
        self.assertEqual(tuple(features.shape), (1, 776))
        self.assertAlmostEqual(float(features[0, 768]), 0, places=5)
        self.assertAlmostEqual(float(features[0, 773]), 1)

    def test_linear_gain_preserves_exposure_signal(self):
        encoded = torch.full((2, 3, 64, 96), 80, dtype=torch.uint8)
        features = self.extractor(encoded, torch.tensor([0.0, 1.0]))
        self.assertAlmostEqual(float(features[1, 768] - features[0, 768]), 1, places=5)
        torch.testing.assert_close(features[0, :768], features[1, :768], atol=2e-5, rtol=0)

    def test_augmentation_label_sign(self):
        delta = torch.tensor([-2.0, 0.0, 1.0])
        self.assertEqual((-delta).tolist(), [2.0, -0.0, -1.0])

    def test_srgb_linearization_known_points(self):
        result = srgb_decode(torch.tensor([0.0, 0.04045, 1.0]))
        torch.testing.assert_close(result, torch.tensor([0.0, 0.003130805, 1.0]), atol=1e-7, rtol=0)

    def test_black_and_white_are_finite_and_censored(self):
        images = torch.stack((torch.zeros(3, 64, 96), torch.ones(3, 64, 96)))
        result = self.extractor.from_linear(images)
        self.assertTrue(bool(torch.isfinite(result).all()))
        self.assertEqual(float(result[0, 772]), 1)
        self.assertEqual(float(result[1, 771]), 1)

    def test_ev_mean_is_not_log_of_mean_luma(self):
        image = torch.full((1, 3, 64, 96), 0.01)
        image[:, :, :, 48:] = 0.16
        result = self.extractor.from_linear(image)
        expected = (np.log2(0.01 / 0.18) + np.log2(0.16 / 0.18)) / 2
        self.assertAlmostEqual(float(result[0, 768]), expected, places=5)
        self.assertGreater(abs(float(result[0, 768]) - np.log2(0.085 / 0.18)), 1)

    def test_region_order_is_row_major(self):
        values = torch.arange(64 * 96).reshape(1, 64, 96)
        cells = self.extractor.cells(values)
        self.assertEqual(int(cells[0, 0, 0]), 0)
        self.assertEqual(int(cells[0, 1, 0]), 8)
        self.assertEqual(int(cells[0, 12, 0]), 8 * 96)
        self.assertEqual(int(cells[0, 95, -1]), 64 * 96 - 1)

    def test_interval_partial_area_weights(self):
        weights = interval_weights(10, 0.05, 0.95)
        self.assertAlmostEqual(float(weights.sum()), 1, places=6)
        self.assertAlmostEqual(float(weights[0]), 0.5 / 9, places=6)
        self.assertAlmostEqual(float(weights[-1]), 0.5 / 9, places=6)

    def test_cpu_cuda_parity(self):
        if not torch.cuda.is_available():
            self.skipTest("CUDA unavailable")
        encoded = torch.randint(0, 240, (2, 3, 64, 96), generator=torch.Generator().manual_seed(17), dtype=torch.uint8)
        shifts = torch.tensor([-0.7, 0.4])
        expected = self.extractor(encoded, shifts)
        actual = FeatureExtractor().cuda()(encoded.cuda(), shifts.cuda()).cpu()
        torch.testing.assert_close(expected, actual, atol=3e-5, rtol=1e-5)


class DataTests(unittest.TestCase):
    def fixture(self, root):
        originals = root / "originals"
        originals.mkdir()
        rows = []
        for index, split in enumerate(("train", "val", "test")):
            path = originals / f"{split}.png"
            image = np.full((12, 20, 3), 60 + index * 40, dtype=np.uint8)
            Image.fromarray(image).save(path)
            rows.append(dict(id=split, image=str(path), sha256=sha256(path), split=split,
                             source_license="CC0-1.0", source_record=dict(group=split,
                             source_url="https://example.org/reference", license_url="https://creativecommons.org/publicdomain/zero/1.0/")))
        manifest = root / "manifest.jsonl"
        manifest.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        return manifest, rows

    def test_sixteen_rois_and_crop_bounds(self):
        definitions = roi_definitions()
        self.assertEqual(len(definitions), 16)
        self.assertEqual(roi_bounds(400, 300, definitions[0]), (0, 0, 400, 300))
        for definition in definitions:
            left, top, right, bottom = roi_bounds(400, 300, definition)
            self.assertTrue(0 <= left < right <= 400)
            self.assertTrue(0 <= top < bottom <= 300)

    def test_roi_outside_content_cannot_enter(self):
        definition = dict(zoom=2.0, x=0, y=0)
        image = np.zeros((64, 96, 3), dtype=np.uint8)
        reference = sample_roi(image, definition)
        image[32:, :, :] = 255
        image[:, 48:, :] = 255
        np.testing.assert_array_equal(reference, sample_roi(image, definition))

    def test_exif_orientation_and_srgb_assumption(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "orientation.jpg"
            image = Image.new("RGB", (20, 12), "red")
            exif = Image.Exif()
            exif[274] = 6
            image.save(path, exif=exif)
            result, status = decode_srgb(path)
            self.assertEqual(result.shape, (20, 12, 3))
            self.assertEqual(status, "assumed-srgb-no-icc")

    def test_cross_split_hash_and_group_leakage_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, rows = self.fixture(root)
            for field in ("sha256", "group"):
                changed = copy.deepcopy(rows)
                if field == "sha256":
                    changed[1][field] = changed[0][field]
                else:
                    changed[1]["source_record"][field] = changed[0]["source_record"][field]
                manifest.write_text("".join(json.dumps(row) + "\n" for row in changed), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "leakage"):
                    read_manifest(manifest)

    def test_cache_reuse_integrity_and_no_original_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, rows = self.fixture(root)
            before = [sha256(row["image"]) for row in rows]
            index = prepare_cache(manifest, root / "cache")
            self.assertEqual(len(index["items"]), 3)
            verify_cache(root / "cache")
            self.assertEqual(before, [sha256(row["image"]) for row in rows])
            cached = root / "cache" / index["items"][0]["file"]
            with cached.open("ab") as stream:
                stream.write(b"corruption")
            with self.assertRaisesRegex(ValueError, "Corrupt"):
                verify_cache(root / "cache")


class TrainingTests(unittest.TestCase):
    def test_complete_cpu_campaign_and_frozen_test(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, _ = DataTests().fixture(root)
            config = dict(CONFIG, seeds=[17], max_epochs=2, warmup_epochs=1, patience=2, batch_size=8)
            result = run_all(manifest, root / "cache", root / "experiment", config, torch.device("cpu"))
            self.assertEqual(result["dataset"]["splits"], dict(train=1, val=1, test=1))
            self.assertEqual(result["test"]["all"]["originals"], 1)
            self.assertTrue((root / "experiment" / "export" / "model.onnx").is_file())
            self.assertTrue((root / "experiment" / "test-predictions.csv").is_file())
            with self.assertRaisesRegex(ValueError, "Final test already exists"):
                run_all(manifest, root / "cache", root / "experiment", config, torch.device("cpu"))

    def test_parameter_count_output_bounds_and_frozen_norm(self):
        model = ExposureMLP()
        self.assertEqual(sum(p.numel() for p in model.parameters()), 51874)
        self.assertNotIn("feature_mean", dict(model.named_parameters()))
        result = model(torch.randn(12, 776) * 10)
        self.assertTrue(bool((result[:, 0].abs() <= 4).all()))
        self.assertTrue(bool((result[:, 1] > 0).all()))

    def test_rng_and_optimizer_checkpoint_resume_is_exact(self):
        seed_everything(27)
        model = ExposureMLP()
        optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)

        def step(net, opt):
            first, second = torch.randn(8, 776), torch.randn(8, 776)
            target_a, target_b = torch.randn(8), torch.randn(8)
            opt.zero_grad()
            loss = training_loss(net, first, second, target_a, target_b, CONFIG)
            loss.backward()
            opt.step()

        step(model, optimizer)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "last.pt"
            atomic_checkpoint(path, dict(model=model.state_dict(), optimizer=optimizer.state_dict(), rng=rng_state()))
            expected_random = (random.random(), float(np.random.rand()))
            step(model, optimizer)
            expected = copy.deepcopy(model.state_dict())
            saved = read_training_state(path)
            resumed = ExposureMLP()
            resumed_optimizer = torch.optim.AdamW(resumed.parameters(), lr=1e-3)
            resumed.load_state_dict(saved["model"])
            resumed_optimizer.load_state_dict(saved["optimizer"])
            restore_rng(saved["rng"])
            self.assertEqual(expected_random, (random.random(), float(np.random.rand())))
            step(resumed, resumed_optimizer)
            for key, value in resumed.state_dict().items():
                torch.testing.assert_close(value, expected[key], atol=0, rtol=0)

    def test_metrics_macro_average_originals_not_roi_counts(self):
        records = [dict(id="a"), dict(id="a"), dict(id="b")]
        result = subset_metrics(np.asarray([0, 0, 3]), np.zeros(3), records, np.ones(3, dtype=bool))
        self.assertEqual(result["mae_ev"], 1.5)

    def test_uncertainty_calibration_requires_coverage(self):
        predictions = torch.stack((torch.linspace(0.01, 0.1, 100), torch.ones(100)), -1)
        features = torch.zeros(100, 776)
        result = calibrate_uncertainty(predictions, torch.zeros(100), features, CONFIG)
        self.assertTrue(result["feasible"])
        self.assertGreaterEqual(result["validation_coverage"], 0.8)
        self.assertFalse(result["probability_claim"])

    def test_infeasible_threshold_still_uses_inclusive_coverage(self):
        predictions = torch.stack((torch.full((7,), 2.0), torch.arange(1, 8).float()), -1)
        result = calibrate_uncertainty(predictions, torch.zeros(7), torch.zeros(7, 776), CONFIG)
        self.assertFalse(result["feasible"])
        self.assertGreaterEqual(result["validation_coverage"], CONFIG["confidence_minimum_coverage"])

    def test_onnx_export_matches_multiple_batches(self):
        with tempfile.TemporaryDirectory() as temporary:
            model = ExposureMLP().eval()
            features = torch.randn(7, 776)
            config = dict(CONFIG, batch_size=3)
            result = export_model(model, Path(temporary), features, config)
            self.assertTrue(result["passed"])
            self.assertLess(result["compensation_max_abs_difference_ev"], 0.001)
            self.assertEqual(feature_spec()["dimensions"], 776)


if __name__ == "__main__":
    unittest.main(verbosity=2)
