# SPDX-License-Identifier: Apache-2.0
"""One bounded continuation stage, carrying the metering optimizer/scheduler/RNG."""
import os
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
import argparse
from collections import Counter
import copy
import json
import math
from pathlib import Path
import sys
sys.dont_write_bytecode = True

import numpy as np
import torch

from common import digest, gpu_slot, read_json, stamp, update, write_json
from ledger import Ledger
from validate import finite, tree_hash

TRAINING_ROOT = Path(__file__).resolve().parent.parent / "metering-training"
sys.path.insert(0, str(TRAINING_ROOT))
from metering_data import decode_srgb, roi_definitions, sample_roi
from metering_evaluation import fixed_features, predict, summarize
from metering_features import FeatureExtractor, FEATURE_VERSION
from metering_model import ExposureMLP
from train import atomic_checkpoint, draw_augmentation, initialize_runtime, restore_rng, rng_state, training_loss


def build_bank(rows, split, old_cache, cache, device):
    old = {item["record"]["sha256"]: item for item in read_json(old_cache / "index.json")["items"]}
    arrays, metadata = [], []
    cache.mkdir(exist_ok=True)
    for row in rows:
        if row["split"] != split:
            continue
        if digest(row["image"]) != row["sha256"]:
            raise ValueError("Shared original changed")
        if row["sha256"] in old:
            item = old[row["sha256"]]
            path = old_cache / item["file"]
            if digest(path) != item["cache_sha256"]:
                raise ValueError("Parent metering sampling cache changed")
        else:
            path = cache / (row["sha256"] + ".npy")
            if not path.exists():
                rgb, _ = decode_srgb(row["image"])
                data = np.stack([sample_roi(rgb, roi) for roi in roi_definitions()])
                with path.with_suffix(".tmp").open("wb") as handle:
                    np.save(handle, data, allow_pickle=False)
                os.replace(path.with_suffix(".tmp"), path)
        data = np.load(path, allow_pickle=False)
        if data.dtype != np.uint8 or data.shape != (16, 3, 64, 96):
            raise ValueError("Unexpected metering ROI layout")
        arrays.append(data)
        metadata.extend(dict(id=row["id"], split=split, roi=roi["name"], source="shared-reviewed") for roi in roi_definitions())
    return torch.from_numpy(np.concatenate(arrays)).to(device), metadata


def run_stage(arguments):
    root, output = arguments.root.resolve(), arguments.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    parent_path = arguments.parent.resolve()
    parent_hash = digest(parent_path)
    manifest_hash = digest(arguments.manifest)
    rows = [json.loads(line) for line in arguments.manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    for row in rows:
        if row.get("metering_label", {}).get("kind") != "suitable-reference-exposure":
            raise ValueError("Metering manifest has no independent reference-exposure mapping")
    config = read_json(arguments.config)
    device = initialize_runtime(config)
    ledger = Ledger(arguments.ledger)
    batch_id = output.name
    ledger.freeze_manifest("metering", batch_id, arguments.manifest, dict(labels="reference zero; correction=-delta EV", feature_version=FEATURE_VERSION))
    if (output / "summary.json").exists():
        completed = read_json(output / "summary.json")
        if completed["manifest_sha256"] != manifest_hash or completed["parent_sha256"] != parent_hash:
            raise ValueError("Existing shared stage lineage changed")
        return completed
    checkpoint = torch.load(output / "last.pt" if (output / "last.pt").exists() else parent_path,
                            map_location="cpu", weights_only=True)
    if (output / "last.pt").exists() and (checkpoint["shared_manifest_sha256"] != manifest_hash or checkpoint["parent_sha256"] != parent_hash):
        raise ValueError("Do not change an in-progress shared metering stage")
    initial_epoch = checkpoint.get("stage_parent_epoch", checkpoint["epoch"]) if (output / "last.pt").exists() else checkpoint["epoch"]
    completed_epochs = checkpoint.get("stage_epochs", 0) if (output / "last.pt").exists() else 0
    if initial_epoch >= config["max_epochs"]:
        raise ValueError("Metering global epoch budget is complete; do not silently restart")
    ledger.batch("metering", batch_id, manifest_hash, rows, parent_hash, dict(global_epoch=checkpoint["epoch"]), required=1)
    update(root, "metering", state="shared-continuation-loading", parent_checkpoint=str(parent_path), epoch=checkpoint["epoch"],
           stage=batch_id, manifest_sha256=manifest_hash, independent_optimizer=True)
    bank, _ = build_bank(rows, "train", arguments.old_cache, root / "metering-derived", device)
    val_bank, val_metadata = build_bank(rows, "val", arguments.old_cache, root / "metering-derived", device)
    extractor = FeatureExtractor().to(device)
    val_x, val_y, val_records = fixed_features(val_bank, val_metadata, extractor, config["validation_shifts_ev"], config["batch_size"])
    del val_bank
    model = ExposureMLP().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=config["learning_rate"], weight_decay=config["weight_decay"])

    def rate(epoch):
        if epoch < config["warmup_epochs"]:
            return (epoch + 1) / config["warmup_epochs"]
        progress = (epoch - config["warmup_epochs"]) / max(config["max_epochs"] - config["warmup_epochs"], 1)
        floor = config["minimum_learning_rate"] / config["learning_rate"]
        return floor + (1 - floor) * (1 + math.cos(math.pi * min(progress, 1))) / 2

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, rate)
    model.load_state_dict(checkpoint["model"])
    optimizer.load_state_dict(checkpoint["optimizer"])
    scheduler.load_state_dict(checkpoint["scheduler"])
    if tree_hash(model.state_dict()) != tree_hash(checkpoint["model"]) or tree_hash(optimizer.state_dict()) != tree_hash(checkpoint["optimizer"]):
        raise ValueError("Metering full state did not restore exactly")
    restore_rng(checkpoint["rng"])
    if tree_hash(rng_state()) != tree_hash(checkpoint["rng"]):
        raise ValueError("Metering RNG mismatch")
    best = checkpoint.get("best_mae", float("inf"))
    best_epoch = checkpoint.get("best_epoch", 0)
    stale = checkpoint.get("stage_stale", 0) if completed_epochs else 0
    history = checkpoint.get("shared_history", []) if completed_epochs else []
    stop_reason = "stage-budget"
    budget = min(arguments.max_epochs, config["max_epochs"] - initial_epoch)
    for local_epoch in range(completed_epochs, budget):
        model.train()
        order = torch.randperm(len(bank)).to(device)
        for start in range(0, len(bank), config["batch_size"]):
            batch = bank[order[start:start + config["batch_size"]]]
            a, b = draw_augmentation(len(batch), config, device), draw_augmentation(len(batch), config, device)
            with torch.no_grad():
                x, y = extractor(batch, a), extractor(batch, b)
            optimizer.zero_grad(set_to_none=True)
            loss = training_loss(model, x, y, -a, -b, config)
            if not torch.isfinite(loss):
                raise FloatingPointError("Non-finite shared metering loss")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5)
            optimizer.step()
        scheduler.step()
        metrics = summarize(predict(model, val_x, config["batch_size"]), val_y, val_records, val_x, config)
        score = metrics["all"]["mae_ev"]
        improved = score < best - 1e-6
        if improved:
            best, best_epoch, stale = score, initial_epoch + local_epoch + 1, 0
        else:
            stale += 1
        history.append(dict(global_epoch=initial_epoch + local_epoch + 1, validation_mae_ev=score))
        payload = dict(schema="shared-metering-continuation-v1", config=config, model=model.state_dict(),
                       optimizer=optimizer.state_dict(), scheduler=scheduler.state_dict(), rng=rng_state(),
                       epoch=initial_epoch + local_epoch + 1, stage_parent_epoch=initial_epoch, stage_epochs=local_epoch + 1,
                       stage_stale=stale, best_mae=best, best_epoch=best_epoch, shared_history=history,
                       parent_checkpoint=str(parent_path), parent_sha256=parent_hash, shared_manifest_sha256=manifest_hash,
                       feature_version=FEATURE_VERSION)
        if not finite(payload):
            raise FloatingPointError("Non-finite metering checkpoint")
        if improved:
            atomic_checkpoint(output / "best.pt", payload)
        atomic_checkpoint(output / "last.pt", payload)
        update(root, "metering", state="shared-continuation-running", epoch=payload["epoch"], stage=batch_id,
               validation_mae_ev=score, checkpoint=str(output / "last.pt"), best_mae_ev=best,
               optimizer_slots=len(optimizer.state_dict()["state"]), source_images=sum(r["split"] == "train" for r in rows))
        if (root / "PAUSE.json").exists():
            stop_reason = "user-pause"
            break
        if stale >= 5:
            stop_reason = "stage-early-stop-no-new-validation-improvement"
            break
    if not (output / "last.pt").exists():
        raise RuntimeError("No shared metering commit")
    saved = torch.load(output / "last.pt", map_location="cpu", weights_only=True)
    if stop_reason != "user-pause":
        ledger.commit("metering", batch_id, output / "last.pt", dict(global_epoch=saved["epoch"], stage_epochs=saved["stage_epochs"]),
                      {row["sha256"]: saved["stage_epochs"] for row in rows if row["split"] == "train"})
    summary = dict(consumer="metering", parent_checkpoint=str(parent_path), parent_sha256=parent_hash,
                   checkpoint=str(output / "last.pt"), checkpoint_sha256=digest(output / "last.pt"), manifest_sha256=manifest_hash,
                   parent_epoch=initial_epoch, global_epoch=saved["epoch"], stage_epochs=saved["stage_epochs"],
                   restored_full_state=True, normalization_preserved=True, stop_reason=stop_reason,
                   fixed_test_evaluated=False, best_mae_ev=best, last_validation_mae_ev=history[-1]["validation_mae_ev"], finished_at=stamp())
    if stop_reason != "user-pause":
        write_json(output / "summary.json", summary)
    write_json(output / "latest-result.json", summary)
    ledger.close()
    update(root, "metering", state="paused" if stop_reason == "user-pause" else "waiting-new-reviewed-data",
           epoch=saved["epoch"], stage=batch_id, checkpoint=summary["checkpoint"], last_stage=summary)
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--parent", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--old-cache", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=TRAINING_ROOT / "config.json")
    parser.add_argument("--max-epochs", type=int, default=10)
    args = parser.parse_args()
    try:
        with gpu_slot(args.root, "metering"):
            result = run_stage(args)
        print(json.dumps(dict(event="metering-stage-complete", **result)), flush=True)
    except BaseException as error:
        update(args.root, "metering", state="failed", error=f"{type(error).__name__}: {error}")
        raise
