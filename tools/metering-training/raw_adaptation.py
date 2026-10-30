# SPDX-License-Identifier: Apache-2.0
"""Prepare, verify and explicitly run a warm-start RAW-domain adaptation stage."""
import os
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import argparse
from contextlib import contextmanager
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import random
import shutil
import sys

import numpy as np
import torch
from torch.nn import functional as F

from metering_data import atomic_json, sha256
from metering_domains import DOMAIN_VERSION, DomainAugmenter
from metering_features import FEATURE_VERSION, FeatureExtractor, valid_features
from metering_model import ExposureMLP
from raw_adaptation_data import load_bank, prepare_bank, read, verify_bank
from raw_adaptation_evaluation import baseline_scores, fit_anchors
from application_mapping_contract import capture as capture_app_mapping
from train import atomic_checkpoint, draw_augmentation


def now():
    return datetime.now(timezone.utc).isoformat()


def tree_hash(value):
    h = hashlib.sha256()
    def add(x):
        if isinstance(x, torch.Tensor):
            h.update(str((str(x.dtype), tuple(x.shape))).encode()); h.update(x.detach().cpu().contiguous().numpy().tobytes())
        elif isinstance(x, dict):
            for k in sorted(x, key=repr):
                add(k); add(x[k])
        elif isinstance(x, (tuple, list)):
            h.update(type(x).__name__.encode())
            for item in x: add(item)
        else:
            h.update(repr(x).encode())
    add(value)
    return h.hexdigest()


def capture_rng(device):
    n = np.random.get_state()
    return dict(python=random.getstate(), numpy=[n[0], n[1].tolist(), n[2], n[3], n[4]],
                torch=torch.get_rng_state(), cuda=torch.cuda.get_rng_state_all() if device.type == "cuda" else [])


def restore_rng(state, device):
    random.setstate(state["python"])
    n = state["numpy"]; np.random.set_state((n[0], np.asarray(n[1], dtype=np.uint32), n[2], n[3], n[4]))
    torch.set_rng_state(state["torch"].cpu())
    if device.type == "cuda" and state["cuda"]:
        torch.cuda.set_rng_state_all([x.cpu() for x in state["cuda"]])


def runtime(device, config):
    torch.set_num_threads(config["cpu_threads"]); torch.use_deterministic_algorithms(True)
    if device.type == "cuda":
        torch.cuda.set_per_process_memory_fraction(config["cuda_memory_fraction"])
        torch.backends.cuda.matmul.allow_tf32 = False; torch.backends.cudnn.allow_tf32 = False


def initialize(parent, config, device):
    model = ExposureMLP().to(device); model.load_state_dict(parent["model"])
    optimizer = torch.optim.AdamW(model.parameters(), lr=config["learning_rate"], weight_decay=config["weight_decay"])
    optimizer.load_state_dict(parent["optimizer"])
    for group in optimizer.param_groups:
        group["lr"] = config["learning_rate"]; group["initial_lr"] = config["learning_rate"]
    def rate(epoch):
        if epoch < config["warmup_epochs"]:
            return (epoch+1)/config["warmup_epochs"]
        progress = (epoch-config["warmup_epochs"])/max(1, config["max_adaptation_epochs"]-config["warmup_epochs"])
        floor = config["minimum_learning_rate"]/config["learning_rate"]
        return floor+(1-floor)*(1+math.cos(math.pi*min(progress, 1)))/2
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, rate)
    restore_rng(parent["rng"], device)
    if tree_hash(model.state_dict()) != tree_hash(parent["model"]):
        raise ValueError("Parent model/normalization changed during warm start")
    if tree_hash(optimizer.state_dict()["state"]) != tree_hash(parent["optimizer"]["state"]):
        raise ValueError("AdamW moments did not restore")
    return model, optimizer, scheduler


def loss_step(model, optimizer, extractor, augmenter, batch, config):
    model.train()
    a, b = (draw_augmentation(len(batch), config, batch.device) for _ in range(2))
    with torch.no_grad():
        raw_a, processed_a = augmenter.pair(batch, a)
        _, processed_b = augmenter.pair(batch, b)
        x = [extractor.from_linear(v) for v in (raw_a, processed_a, processed_b)]
    predictions = [model(v) for v in x]; targets = [-a, -a, -b]
    beta = config["smooth_l1_beta"]
    point = sum(F.smooth_l1_loss(p[:, 0], y, beta=beta) for p, y in zip(predictions, targets))/3
    scale = sum(((p[:, 0]-y).abs().detach()/p[:, 1]+p[:, 1].log()).mean() for p, y in zip(predictions, targets))/3
    consistent = valid_features(x[0], config) & valid_features(x[2], config)
    paired = valid_features(x[0], config) & valid_features(x[1], config)
    zero = point*0
    relation = F.smooth_l1_loss(predictions[0][consistent, 0]-predictions[2][consistent, 0],
                              (b-a)[consistent], beta=beta) if consistent.any() else zero
    domain = F.smooth_l1_loss(predictions[0][paired, 0], predictions[1][paired, 0], beta=beta) if paired.any() else zero
    loss = point+config["uncertainty_loss_weight"]*scale+config["consistency_loss_weight"]*relation+config["domain_loss_weight"]*domain
    if not torch.isfinite(loss): raise FloatingPointError("Non-finite RAW adaptation loss")
    optimizer.zero_grad(set_to_none=True); loss.backward()
    norm = torch.nn.utils.clip_grad_norm_(model.parameters(), 5.)
    if not torch.isfinite(norm): raise FloatingPointError("Non-finite RAW adaptation gradient")
    optimizer.step()
    return dict(total=float(loss.detach()), compensation=float(point.detach()), scale=float(scale.detach()),
                exposure_consistency=float(relation.detach()), domain_consistency=float(domain.detach()))


@torch.inference_mode()
def evaluate(model, bank, records, extractor, augmenter, config, anchors=None):
    saved_rng = capture_rng(bank.device)
    torch.manual_seed(config["validation_seed"])
    model.eval(); predictions = {k:[] for k in ("legacy_jpeg", "synthetic_raw", "synthetic_processed")}
    baseline_predictions = {k:{name:[] for name in ("project_global","uniform_ev","uniform_linear")} for k in predictions}
    common_masks = {k:[] for k in predictions}
    targets, ids = [], []
    for delta in config["validation_shifts_ev"]:
        for start in range(0, len(bank), config["batch_size"]):
            batch = bank[start:start+config["batch_size"]]
            shifts = torch.full((len(batch),), delta, device=bank.device)
            raw, processed = augmenter.pair(batch, shifts)
            features = dict(legacy_jpeg=extractor(batch, shifts), synthetic_raw=extractor.from_linear(raw),
                            synthetic_processed=extractor.from_linear(processed))
            for name, x in features.items(): predictions[name].extend(model(x)[:, 0].cpu().tolist())
            if anchors is not None:
                for name, linear in dict(legacy_jpeg=extractor.linear_lut[batch.long()]*shifts[:,None,None,None].exp2(),
                                         synthetic_raw=raw,synthetic_processed=processed).items():
                    scores, common = baseline_scores(linear.clamp(0,1),features[name])
                    common_masks[name].extend(common.cpu().tolist())
                    anchor_key = "legacy_jpeg" if name == "legacy_jpeg" else "canonical"
                    for key, value in scores.items():
                        baseline_predictions[name][key].extend((anchors["offset_ev"][anchor_key][key]-value).cpu().tolist())
            targets.extend((-shifts).cpu().tolist()); ids.extend(r["id"] for r in records[start:start+len(batch)])
    restore_rng(saved_rng, bank.device)
    target = np.asarray(targets); groups = np.asarray(ids)
    def metric(values, mask=None):
        error = np.abs(np.asarray(values)-target)
        mask = np.ones(len(error),dtype=bool) if mask is None else np.asarray(mask,dtype=bool)
        if not mask.any(): return dict(mae_ev=None,p90_ev=None,max_ev=None,samples=0)
        return dict(mae_ev=float(np.mean([error[mask & (groups == k)].mean() for k in set(groups[mask])])),
                    p90_ev=float(np.quantile(error[mask], .9)), max_ev=float(error[mask].max()), samples=int(mask.sum()))
    result = {k:metric(v) for k,v in predictions.items()}
    result["paired_prediction_mae_ev"] = float(np.mean(np.abs(np.asarray(predictions["synthetic_raw"])-predictions["synthetic_processed"])))
    result["selection_mae_ev"] = (result["synthetic_raw"]["mae_ev"]+result["synthetic_processed"]["mae_ev"])/2
    if anchors is not None:
        result["comparisons"] = {name:dict(common_coverage=float(np.mean(common_masks[name])),
            common=dict(model=metric(predictions[name],common_masks[name]),
                        **{key:metric(v,common_masks[name]) for key,v in baseline_predictions[name].items()})) for name in predictions}
    result["scope"] = "16 fixed validation originals; virtual ISP pairs, not real sensor validation; test untouched"
    return result


def verify_prepared(folder):
    folder = Path(folder).resolve(); plan = read(folder/"prepared.json")
    for name, checksum in plan["files"].items():
        path = (folder/name).resolve()
        if folder not in path.parents or sha256(path) != checksum:
            raise ValueError("Prepared configuration/code/parent changed: "+name)
    verify_bank(folder/"data")
    return plan


def prepare(control, folder, parent_override=None, config_override=None):
    control, folder = Path(control).resolve(), Path(folder).resolve()
    if (folder/"prepared.json").exists(): return verify_prepared(folder)
    folder.mkdir(parents=True, exist_ok=True)
    status = read(control/"metering-status.json"); parent = Path(parent_override or status["checkpoint"]).resolve()
    shutil.copyfile(parent, folder/"parent.pt")
    if sha256(parent) != sha256(folder/"parent.pt"): raise ValueError("Parent changed while copying")
    # All model tensors are on CPU; preparing never acquires or uses a GPU.
    state = torch.load(folder/"parent.pt", map_location="cpu", weights_only=True)
    if state["feature_version"] != FEATURE_VERSION: raise ValueError("Unexpected parent feature contract")
    if state.get("progress",{}).get("cursor",0):
        raise ValueError("Parent has an unfinished epoch; resume that stage before changing its data")
    data = prepare_bank(control, folder/"data")
    code = folder/"code"; code.mkdir(exist_ok=True)
    source = Path(__file__).resolve().parent
    for path in source.glob("*.py"): shutil.copyfile(path, code/path.name)
    shared = Path(read(control/"controller-process.json")["runtime"]).parent/"common.py"
    shutil.copyfile(shared, code/"shared_common.py")
    shutil.copyfile(Path(config_override).resolve() if config_override else source/"raw_adaptation_config.json", folder/"config.json")
    workspace = control.parent.parent; shutil.copyfile(workspace/"LICENSE", folder/"LICENSE")
    app_contract, app_sources = capture_app_mapping(workspace)
    atomic_json(folder/"app-contract.json",app_contract)
    atomic_json(folder/"application-sources.json",app_sources)
    shutil.copyfile(workspace/"app/src/main/java/com/lightmeter/rawmeter/EvaluativeMetering.kt",code/"EvaluativeMetering.kt")
    config = read(folder/"config.json")
    if config["domain_version"] != DOMAIN_VERSION or config["reference_inverse"] != "smoothstep":
        raise ValueError("Unknown domain recipe")
    budget = min(config["max_adaptation_epochs"], config["global_epoch_limit"]-state["epoch"])
    if budget <= 0: raise ValueError("No remaining global epoch budget")
    files = {str(p.relative_to(folder)).replace("\\", "/"):sha256(p) for p in list(code.glob("*.py"))+[code/"EvaluativeMetering.kt",folder/"config.json", folder/"parent.pt", folder/"data/manifest.json", folder/"LICENSE",folder/"app-contract.json",folder/"application-sources.json"]}
    plan = dict(schema="metering-raw-adaptation-prepared-v2", prepared_at=now(), control_root=str(control),
                parent_checkpoint=str(parent), parent_sha256=sha256(folder/"parent.pt"), parent_epoch=state["epoch"],
                parent_recipe=state.get("schema","legacy"),
                budget_epochs=budget, counts=data["counts"], files=files, training_started=False,
                preserves=["all 51874 parameters", "776-d training normalization", "AdamW moments", "parent RNG lineage"],
                changes=["exposure before virtual ISP", "RAW/processed pairing", "1e-4 fine-tune LR with new stage scheduler", "new per-recipe best metric"],
                calibration_policy="Existing device calibration plus metadata adapters; no extra user-operated neural calibration",
                real_raw_pairs=0)
    plan["application_mapping_version"] = app_contract["mapping_version"]
    atomic_json(folder/"prepared.json", plan)
    return verify_prepared(folder)


def smoke(folder):
    folder = Path(folder).resolve(); plan = verify_prepared(folder); config = read(folder/"config.json")
    device = torch.device("cpu"); runtime(device, config)
    parent = torch.load(folder/"parent.pt", map_location="cpu", weights_only=True)
    bank, _ = load_bank(folder/"data", "train", device)
    val, records = load_bank(folder/"data", "val", device)
    model, optimizer, scheduler = initialize(parent, config, device)
    extractor = FeatureExtractor(); augmenter = DomainAugmenter(config)
    anchors = fit_anchors(bank,extractor,augmenter,config)
    atomic_json(folder/"baseline-anchors.json",anchors)
    baseline = evaluate(model, val, records, extractor, augmenter, config, anchors)
    losses = [loss_step(model, optimizer, extractor, augmenter, bank[:8], config) for _ in range(2)]
    payload = dict(model=copy.deepcopy(model.state_dict()), optimizer=copy.deepcopy(optimizer.state_dict()),
                   scheduler=scheduler.state_dict(), rng=capture_rng(device))
    smoke_dir = folder/"smoke"; smoke_dir.mkdir(exist_ok=True)
    atomic_checkpoint(smoke_dir/"resume-test.pt", payload)
    expected = loss_step(model, optimizer, extractor, augmenter, bank[8:16], config)
    expected_hash = tree_hash([model.state_dict(), optimizer.state_dict()])
    restored = torch.load(smoke_dir/"resume-test.pt", map_location="cpu", weights_only=True)
    clone, clone_optimizer, clone_scheduler = initialize(parent, config, device)
    clone.load_state_dict(restored["model"]); clone_optimizer.load_state_dict(restored["optimizer"])
    clone_scheduler.load_state_dict(restored["scheduler"]); restore_rng(restored["rng"], device)
    actual = loss_step(clone, clone_optimizer, extractor, augmenter, bank[8:16], config)
    exact = expected_hash == tree_hash([clone.state_dict(), clone_optimizer.state_dict()]) and expected == actual
    if not exact: raise AssertionError("Resume produced a different next update")
    report = dict(at=now(), state="ready-for-explicit-training", device="CPU only", parent_epoch=plan["parent_epoch"],
                  training_originals=plan["counts"]["train"], updated_batches=2, exact_next_update_after_reload=exact,
                  initial_weights_exact=True, adamw_moments_preserved=True, normalization_preserved=True,
                  parent_sha256_unchanged=sha256(plan["parent_checkpoint"]) == plan["parent_sha256"],
                  baseline=baseline, losses=losses, long_training_started=False, real_raw_validation="pending real paired captures")
    atomic_json(folder/"smoke/report.json", report)
    return report


def pause_identity(path):
    return [sha256(path), Path(path).stat().st_mtime_ns] if Path(path).exists() else None


@contextmanager
def run_lock(folder):
    with (folder/"training.lock").open("a+b") as stream:
        if not (folder/"training.lock").stat().st_size: stream.write(b"0"); stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            try: msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as error: raise RuntimeError("RAW adaptation worker already owns this stage") from error
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX|fcntl.LOCK_NB)
        yield


def run(folder, device_name, acknowledge_pause=False):
    folder = Path(folder).resolve(); plan = verify_prepared(folder); config = read(folder/"config.json")
    control = Path(plan["control_root"])
    if not read(folder/"smoke/report.json").get("exact_next_update_after_reload"):
        raise ValueError("Complete the CPU smoke/reload check before starting")
    sys.path.insert(0, str(control/"training-ops"))
    from ops import alive
    if any(alive(read(control/name).get("pid")) for name in ("controller-process.json", "coordinator-state.json", "active-job.json")):
        raise RuntimeError("Existing shared controller/worker is live; do not start a second training controller")
    pauses = {str(p):pause_identity(p) for p in (control/"PAUSE.json", folder/"PAUSE.json")}
    if any(pauses.values()) and not acknowledge_pause:
        raise RuntimeError("Training is paused; explicit metering-only resume must acknowledge existing pause markers")
    from shared_common import gpu_slot
    with run_lock(folder), gpu_slot(control, "metering-raw"):
        try:
            atomic_json(folder/"training-status.json",dict(state="loading",at=now(),pid=os.getpid()))
            return train_locked(folder, plan, config, torch.device(device_name), pauses)
        except BaseException as error:
            atomic_json(folder/"training-status.json",dict(state="failed",at=now(),pid=os.getpid(),error=f"{type(error).__name__}: {error}"))
            raise


def train_locked(folder, plan, config, device, pauses):
    runtime(device, config)
    parent = torch.load(folder/"parent.pt", map_location="cpu", weights_only=True)
    model, optimizer, scheduler = initialize(parent, config, device)
    bank, _ = load_bank(folder/"data", "train", device); val, records = load_bank(folder/"data", "val", device)
    extractor = FeatureExtractor().to(device); augmenter = DomainAugmenter(config)
    anchors = fit_anchors(bank,extractor,augmenter,config)
    if (folder/"baseline-anchors.json").exists():
        frozen = read(folder/"baseline-anchors.json")
        difference = max(abs(anchors["offset_ev"][d][k]-v) for d,m in frozen["offset_ev"].items() for k,v in m.items())
        if difference > 1e-4 or anchors["reliable_reference_rois"] != frozen["reliable_reference_rois"]:
            raise ValueError("Training-only baseline anchors changed")
        anchors = frozen  # Keep CPU calibration fixed despite GPU rounding differences.
    atomic_json(folder/"baseline-anchors.json",anchors)
    checkpoint = folder/"last.pt"; state = dict(adaptation_epoch=0, cursor=0, order=None, total_steps=0, best=float("inf"), stale=0)
    latest_metrics = None
    if checkpoint.exists():
        saved = torch.load(checkpoint, map_location="cpu", weights_only=True)
        if saved["prepared_sha256"] != sha256(folder/"prepared.json"): raise ValueError("Resume identity changed")
        model.load_state_dict(saved["model"]); optimizer.load_state_dict(saved["optimizer"])
        scheduler.load_state_dict(saved["scheduler"]); restore_rng(saved["rng"], device); state = saved["progress"]
        latest_metrics = saved.get("validation")
    def save():
        payload = dict(schema="metering-raw-adaptation-v2", config=config, feature_version=FEATURE_VERSION,
                       domain_version=DOMAIN_VERSION, model=model.state_dict(), optimizer=optimizer.state_dict(),
                       validation=latest_metrics,
                       scheduler=scheduler.state_dict(), rng=capture_rng(device), progress=state,
                       parent_checkpoint=plan["parent_checkpoint"], parent_sha256=plan["parent_sha256"],
                       prepared_sha256=sha256(folder/"prepared.json"), epoch=plan["parent_epoch"]+state["adaptation_epoch"])
        atomic_checkpoint(checkpoint, payload)
        return payload
    def status(phase, **extra):
        extra.setdefault("validation",latest_metrics)
        atomic_json(folder/"training-status.json", dict(state=phase, at=now(), pid=os.getpid(),
            epoch=plan["parent_epoch"]+state["adaptation_epoch"], adaptation_epoch=state["adaptation_epoch"],
            total_steps=state["total_steps"], cursor=state["cursor"], total_rois=len(bank), **extra))
    for epoch in range(state["adaptation_epoch"], plan["budget_epochs"]):
        if state["order"] is None: state["order"] = torch.randperm(len(bank))
        for start in range(state["cursor"], len(bank), config["batch_size"]):
            batch = bank[state["order"][start:start+config["batch_size"]].to(device)]
            losses = loss_step(model, optimizer, extractor, augmenter, batch, config)
            state.update(cursor=start+len(batch), total_steps=state["total_steps"]+1)
            status("training", loss=losses)
            paused = any(pause_identity(p) != identity for p, identity in pauses.items())
            if paused or state["total_steps"] % config["checkpoint_every_steps"] == 0: save()
            if paused:
                status("paused-checkpoint-saved"); return dict(state="paused", checkpoint=str(checkpoint))
        scheduler.step()
        metrics = evaluate(model, val, records, extractor, augmenter, config, anchors)
        latest_metrics = metrics
        improved = metrics["selection_mae_ev"] < state["best"]-1e-6
        state.update(adaptation_epoch=epoch+1, cursor=0, order=None,
                     best=metrics["selection_mae_ev"] if improved else state["best"], stale=0 if improved else state["stale"]+1)
        payload = save()
        if improved: atomic_checkpoint(folder/"best.pt", payload)
        status("epoch-complete", validation=metrics)
        with (folder/"metrics.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(dict(epoch=payload["epoch"], adaptation_epoch=epoch+1, validation=metrics))+"\n")
        if state["stale"] >= config["patience"]: break
    status("stage-complete")
    return dict(state="stage-complete", checkpoint=str(checkpoint), epoch=plan["parent_epoch"]+state["adaptation_epoch"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "smoke", "status", "run"))
    parser.add_argument("--folder", type=Path, required=True); parser.add_argument("--control", type=Path)
    parser.add_argument("--parent", type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cuda")
    parser.add_argument("--acknowledge-existing-pause", action="store_true")
    args = parser.parse_args()
    if args.action == "prepare": result = prepare(args.control, args.folder, args.parent, args.config)
    elif args.action == "smoke": result = smoke(args.folder)
    elif args.action == "status":
        result = dict(prepared=verify_prepared(args.folder), smoke=read(args.folder/"smoke/report.json") if (args.folder/"smoke/report.json").exists() else None)
    else: result = run(args.folder, args.device, args.acknowledge_existing_pause)
    print(json.dumps(result, ensure_ascii=False, indent=2))
