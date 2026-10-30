# SPDX-License-Identifier: Apache-2.0
"""Bounded restore checks on private checkpoint branches, never the paused originals."""
import os
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
import argparse
import copy
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import sys
sys.dont_write_bytecode = True
import time

import numpy as np
import torch

from common import digest, gpu_slot, read_json, stamp, update, write_json


def finite(value):
    if isinstance(value, torch.Tensor):
        return not (value.is_floating_point() or value.is_complex()) or bool(torch.isfinite(value).all())
    if isinstance(value, np.ndarray):
        return bool(np.isfinite(value).all())
    if isinstance(value, dict):
        return all(finite(child) for child in value.values())
    if isinstance(value, (list, tuple)):
        return all(finite(child) for child in value)
    return True


def tree_hash(value):
    result = hashlib.sha256()

    def visit(item):
        if isinstance(item, torch.Tensor):
            array = item.detach().cpu().contiguous()
            result.update(str((str(array.dtype), tuple(array.shape))).encode())
            result.update(array.reshape(-1).view(torch.uint8).numpy().tobytes())
        elif isinstance(item, dict):
            for key in sorted(item, key=repr):
                result.update(repr(key).encode())
                visit(item[key])
        elif isinstance(item, (tuple, list)):
            for child in item:
                visit(child)
        else:
            result.update(repr(item).encode())
    visit(value)
    return result.hexdigest()


def same_metrics(left, right):
    if isinstance(left, dict) and isinstance(right, dict):
        keys = set(left) - {"seconds", "path", "preview"}
        return keys == set(right) - {"seconds", "path", "preview"} and all(same_metrics(left[k], right[k]) for k in keys)
    if isinstance(left, (list, tuple)):
        return len(left) == len(right) and all(same_metrics(a, b) for a, b in zip(left, right))
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return bool(np.isclose(left, right, rtol=1e-6, atol=1e-7))
    return left == right


def check_snapshot(snapshot):
    index = read_json(snapshot / "files.json")
    expected = index["files"]
    wrong = [name for name, checksum in expected.items() if digest(snapshot / name) != checksum]
    if wrong:
        raise RuntimeError(f"Frozen dust source differs: {wrong}")
    return dict(path=str(snapshot), files=len(expected), index_sha256=digest(snapshot / "files.json"))


def dust_validation(arguments):
    root = arguments.root.resolve()
    parent = arguments.dust_run.resolve()
    snapshot = arguments.snapshot.resolve()
    source_path = parent / "last.pt"
    source_hash = digest(source_path)
    parent_checkpoint = torch.load(source_path, map_location="cpu", weights_only=True)
    if not finite(parent_checkpoint):
        raise RuntimeError("Paused checkpoint is non-finite")
    source_identity = check_snapshot(snapshot)
    branch = root / "dust-restore-validation"
    branch.mkdir(exist_ok=True)
    origin_path = branch / "parent.json"
    origin = dict(checkpoint=str(source_path), sha256=source_hash, source=source_identity,
                  cursor=parent_checkpoint["stream_cursor"], detector_steps=parent_checkpoint["stream_steps"],
                  repair_steps=parent_checkpoint["repair"]["steps"], created_at=stamp())
    if origin_path.exists():
        if read_json(origin_path)["sha256"] != source_hash:
            raise RuntimeError("Validation parent changed")
    else:
        for filename in ("last.pt", "config.json", "training-status.json"):
            shutil.copyfile(parent / filename, branch / filename)
        write_json(origin_path, origin)
    sys.path.insert(0, str(snapshot / "src"))
    from film_dust import stream_train
    from film_dust.models import Detector
    from film_dust.repair import RepairTrainer
    from film_dust.run_control import RunControl, resume_run
    observed = dict(optimizer_loads=[], detector_restored=False, repair_restored=False,
                    cpu_rng_restored=False, cuda_rng_restored=False, aligned_samples=0)
    target_cursor = parent_checkpoint["stream_cursor"] + 1
    expected_optimizers = {tree_hash(parent_checkpoint["optimizer"]), tree_hash(parent_checkpoint["repair"]["optimizer"])}
    original_optimizer_load = torch.optim.AdamW.load_state_dict

    def optimizer_load(self, state):
        original_optimizer_load(self, state)
        checksum = tree_hash(self.state_dict())
        observed["optimizer_loads"].append(dict(slots=len(state["state"]), matches_parent=checksum in expected_optimizers))
    torch.optim.AdamW.load_state_dict = optimizer_load
    original_detector_load = Detector.load_state_dict

    def detector_load(self, state, *args, **kwargs):
        value = original_detector_load(self, state, *args, **kwargs)
        observed["detector_restored"] |= tree_hash(self.state_dict()) == tree_hash(parent_checkpoint["model"])
        return value
    Detector.load_state_dict = detector_load
    original_repair_init = RepairTrainer.__init__

    def repair_init(self, *args, **kwargs):
        original_repair_init(self, *args, **kwargs)
        checkpoint = self.checkpoint()
        observed["repair_restored"] |= (tree_hash(checkpoint["model"]) == tree_hash(parent_checkpoint["repair"]["model"]) and
                                        tree_hash(checkpoint["scaler"]) == tree_hash(parent_checkpoint["repair"]["scaler"]) and
                                        checkpoint["steps"] == parent_checkpoint["repair"]["steps"])
    RepairTrainer.__init__ = repair_init
    original_set_rng = torch.set_rng_state

    def set_rng(state):
        observed["cpu_rng_restored"] |= torch.equal(state.cpu(), parent_checkpoint["torch_rng"].cpu())
        return original_set_rng(state)
    torch.set_rng_state = set_rng
    original_set_cuda_rng = torch.cuda.set_rng_state_all

    def set_cuda_rng(states):
        expected = parent_checkpoint.get("cuda_rng", [])
        observed["cuda_rng_restored"] |= len(states) == len(expected) and all(torch.equal(a.cpu(), b.cpu()) for a, b in zip(states, expected))
        return original_set_cuda_rng(states)
    torch.cuda.set_rng_state_all = set_cuda_rng
    original_buffer = stream_train.make_image_buffer

    def private_buffer(rows, path, catalog, options, **kwargs):
        options = dict(options, _origin_retry_path=str(branch / "scratch-cache" / "origin-retry.json"))
        return original_buffer(rows, branch / "scratch-cache" / "train", catalog, options, **kwargs)
    stream_train.make_image_buffer = private_buffer
    original_sample = stream_train.render_training_sample

    def checked_sample(*args, **kwargs):
        result = original_sample(*args, **kwargs)
        image, mask, valid = result[:3]
        if not finite(result) or image.shape[0] != 3 or mask.shape[0] != 2 or valid.shape[0] != 1 or image.shape[-2:] != mask.shape[-2:] or image.shape[-2:] != valid.shape[-2:]:
            raise RuntimeError("Synthetic sample/mask alignment or finiteness failed")
        if mask.min() < 0 or mask.max() > 1 or valid.min() < 0 or valid.max() > 1:
            raise RuntimeError("Synthetic label outside [0,1]")
        observed["aligned_samples"] += 1
        return result
    stream_train.render_training_sample = checked_sample
    original_control_update = RunControl.update

    def control_update(self, state, **values):
        original_control_update(self, state, **values)
        if values.get("phase") == "checkpoint-saved":
            update(root, "dust", state="small-restore-validation", checkpoint=str(branch / "last.pt"), **values)
            if values.get("source_cursor", 0) >= target_cursor:
                write_json(branch / "pause-request.json", dict(reason="bounded shared-data restore validation"))
    RunControl.update = control_update
    update(root, "dust", state="small-restore-running", original_checkpoint=str(source_path), validation_branch=str(branch),
           target_cursor=target_cursor)
    if torch.load(branch / "last.pt", map_location="cpu", weights_only=True)["stream_cursor"] < target_cursor:
        resume_run(branch)
    child = torch.load(branch / "last.pt", map_location="cpu", weights_only=True)
    if child["stream_cursor"] != target_cursor or child["stream_steps"] <= parent_checkpoint["stream_steps"] or child["repair"]["steps"] <= parent_checkpoint["repair"]["steps"] or not finite(child):
        raise RuntimeError("Bounded restore did not commit both model updates")
    if not all(observed[key] for key in ("detector_restored", "repair_restored", "cpu_rng_restored", "cuda_rng_restored")) or len(observed["optimizer_loads"]) < 2 or not all(item["matches_parent"] for item in observed["optimizer_loads"][:2]):
        raise RuntimeError(f"Full parent state restore not proven: {observed}")
    update(root, "dust", state="fixed-validation-recompute", cursor=child["stream_cursor"], detector_steps=child["stream_steps"],
           repair_steps=child["repair"]["steps"], aligned_samples=observed["aligned_samples"])
    benchmark = branch / "fixed-validation"
    previous_argv = sys.argv[:]
    sys.argv = [str(snapshot / "tools" / "benchmark_validation.py"), "cached", str(branch / "last.pt"), str(benchmark)]
    try:
        runpy.run_path(str(snapshot / "tools" / "benchmark_validation.py"), run_name="__main__")
    finally:
        sys.argv = previous_argv
    validation = read_json(benchmark / "result.json")
    phases = validation["results"]
    reproducible = all(phases[0]["detector_input_sha256"] == phase["detector_input_sha256"] and
                       phases[0]["repair_input_sha256"] == phase["repair_input_sha256"] and
                       same_metrics(phases[0]["detector"], phase["detector"]) and
                       same_metrics(phases[0]["repair"], phase["repair"]) for phase in phases[1:])
    if not reproducible or digest(source_path) != source_hash or not validation["rng_unchanged"]:
        raise RuntimeError("Fixed validation/original checkpoint protection failed")
    report = dict(consumer="dust", passed=True, finished_at=stamp(), parent=origin, observed_restore=observed,
                  branch_checkpoint_sha256=digest(branch / "last.pt"),
                  child=dict(cursor=child["stream_cursor"], detector_steps=child["stream_steps"], repair_steps=child["repair"]["steps"],
                             unique_originals=child["stream_trained_sources"], architecture=child["repair"]["architecture"]),
                  original_checkpoint_unchanged=True, fixed_validation_reproducible=True,
                  fixed_validation_result=str(benchmark / "result.json"), authoritative_parent_resumed=False,
                  note="Isolated validation branch, not a replacement for the paused production checkpoint")
    write_json(root / "dust-validation.json", report)
    update(root, "dust", state="restore-verified-awaiting-shared-publish", original_checkpoint=str(source_path),
           cursor=origin["cursor"], detector_steps=origin["detector_steps"], repair_steps=origin["repair_steps"],
           validation_cursor=child["stream_cursor"], validation_passed=True)
    return report


def metering_validation(arguments):
    root = arguments.root.resolve()
    parent = arguments.metering_run.resolve() / "seed-20261025" / "last.pt"
    checksum = digest(parent)
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "metering-training"))
    from metering_data import load_bank, verify_cache
    from metering_features import FeatureExtractor
    from metering_model import ExposureMLP
    from train import draw_augmentation, restore_rng, rng_state, training_loss, atomic_checkpoint
    checkpoint = torch.load(parent, map_location="cpu", weights_only=True)
    config = read_json(arguments.metering_run / "config.json")
    index = verify_cache(arguments.metering_cache, originals=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.set_num_threads(2)
    torch.use_deterministic_algorithms(True)
    if device.type == "cuda":
        torch.cuda.set_per_process_memory_fraction(0.2)
    bank, _ = load_bank(arguments.metering_cache, index, "train", device)
    model = ExposureMLP().to(device)
    model.load_state_dict(checkpoint["model"])
    optimizer = torch.optim.AdamW(model.parameters(), lr=config["learning_rate"], weight_decay=config["weight_decay"])
    optimizer.load_state_dict(checkpoint["optimizer"])
    initial = dict(model_matches=tree_hash(model.state_dict()) == tree_hash(checkpoint["model"]),
                   optimizer_matches=tree_hash(optimizer.state_dict()) == tree_hash(checkpoint["optimizer"]),
                   epoch=checkpoint["epoch"], optimizer_slots=len(optimizer.state_dict()["state"]),
                   scheduler_preserved=copy.deepcopy(checkpoint["scheduler"]))
    restore_rng(checkpoint["rng"])
    random_state = rng_state()
    if tree_hash(random_state) != tree_hash(checkpoint["rng"]):
        raise RuntimeError("Metering RNG restore mismatch")
    batch = bank[torch.randperm(len(bank))[:config["batch_size"]].to(device)]
    a, b = draw_augmentation(len(batch), config, device), draw_augmentation(len(batch), config, device)
    extractor = FeatureExtractor().to(device)
    with torch.no_grad():
        x, y = extractor(batch, a), extractor(batch, b)
    optimizer.zero_grad(set_to_none=True)
    loss = training_loss(model, x, y, -a, -b, config)
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 5)
    optimizer.step()
    payload = dict(model=model.state_dict(), optimizer=optimizer.state_dict(), scheduler=checkpoint["scheduler"],
                   rng=rng_state(), parent_checkpoint_sha256=checksum, parent_epoch=checkpoint["epoch"],
                   validation_only=True, audit_batch_cursor=1,
                   note="One partial-epoch validation batch; never pretend this is an original trainer epoch-boundary checkpoint")
    if not finite(payload) or tree_hash(model.state_dict()) == tree_hash(checkpoint["model"]) or not all((initial["model_matches"], initial["optimizer_matches"])):
        raise RuntimeError("Metering bounded restore failed")
    child = root / "metering-restore-validation" / "last-audit.pt"
    child.parent.mkdir(exist_ok=True)
    atomic_checkpoint(child, payload)
    loaded = torch.load(child, map_location="cpu", weights_only=True)
    if tree_hash(loaded) != tree_hash(payload) or digest(parent) != checksum:
        raise RuntimeError("Metering commit/source protection failed")
    report = dict(consumer="metering", passed=True, finished_at=stamp(), checkpoint=str(parent), checkpoint_sha256=checksum,
                  restored=initial, rng_restored=True, child_checkpoint=str(child), child_sha256=digest(child),
                  committed_batches=1, batch_size=len(batch), loss=float(loss.detach()), parent_checkpoint_unchanged=True,
                  note="Independent metering model state; no dust labels/weights/optimizer imported")
    write_json(root / "metering-validation.json", report)
    update(root, "metering", state="restore-verified-awaiting-shared-publish", validation_passed=True,
           checkpoint=str(parent), epoch=checkpoint["epoch"], validation_committed_batches=1)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--consumer", choices=["dust", "metering"], required=True)
    parser.add_argument("--dust-run", type=Path)
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--metering-run", type=Path)
    parser.add_argument("--metering-cache", type=Path)
    arguments = parser.parse_args()
    try:
        with gpu_slot(arguments.root, arguments.consumer + "-small-validation"):
            report = dust_validation(arguments) if arguments.consumer == "dust" else metering_validation(arguments)
        print(json.dumps(dict(event="restore-validation-complete", consumer=arguments.consumer, passed=report["passed"])), flush=True)
    except BaseException as error:
        update(arguments.root, arguments.consumer, state="validation-failed", error=f"{type(error).__name__}: {error}")
        raise


if __name__ == "__main__":
    main()
