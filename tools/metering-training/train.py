# SPDX-License-Identifier: Apache-2.0
"""Reference exposure pretraining. No Android or external training-state mutations."""
import os
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import argparse
import copy
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import platform
import random
import shutil
import sys
import time

import numpy as np
import torch
from torch.nn import functional as F
from safetensors.torch import save_file

from metering_data import atomic_json, load_bank, now, prepare_cache, sha256, verify_cache
from metering_evaluation import (calibrate_uncertainty, fixed_features, per_original_rows,
                                 predict, summarize, write_predictions)
from metering_features import FEATURE_VERSION, FeatureExtractor, feature_spec, valid_features
from metering_model import ExposureMLP

SCRIPT_ROOT = Path(__file__).resolve().parent


def emit(event, **values):
    print(json.dumps(dict(event=event, **values), ensure_ascii=False, allow_nan=False), flush=True)


def config_hash(config):
    return hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()


def source_hashes():
    return {path.name: sha256(path) for path in sorted(SCRIPT_ROOT.glob("*.py"))}


def initialize_runtime(config):
    torch.set_num_threads(config["cpu_threads"])
    torch.use_deterministic_algorithms(True)
    if torch.cuda.is_available():
        torch.cuda.set_per_process_memory_fraction(config["cuda_memory_fraction"])
        torch.backends.cuda.matmul.allow_tf32 = False
        torch.backends.cudnn.allow_tf32 = False
        return torch.device("cuda")
    return torch.device("cpu")


def seed_everything(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def rng_state():
    numpy = np.random.get_state()
    return dict(python=random.getstate(), numpy=[numpy[0], numpy[1].tolist(), numpy[2], numpy[3], numpy[4]],
                torch=torch.get_rng_state(), cuda=torch.cuda.get_rng_state_all() if torch.cuda.is_available() else [])


def restore_rng(state):
    random.setstate(state["python"])
    numpy = state["numpy"]
    np.random.set_state((numpy[0], np.asarray(numpy[1], dtype=np.uint32), numpy[2], numpy[3], numpy[4]))
    torch.set_rng_state(state["torch"].cpu())
    if state["cuda"]:
        torch.cuda.set_rng_state_all([value.cpu() for value in state["cuda"]])


def atomic_checkpoint(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    torch.save(value, temporary)
    os.replace(temporary, path)


def draw_augmentation(count, config, device):
    delta = (torch.rand(count, device=device) * 2 - 1) * config["maximum_augmentation_ev"]
    return torch.where(torch.rand(count, device=device) < config["original_probability"], torch.zeros_like(delta), delta)


def training_loss(model, first, second, first_target, second_target, config):
    a, b = model(first), model(second)
    beta = config["smooth_l1_beta"]
    point = (F.smooth_l1_loss(a[:, 0], first_target, beta=beta) +
             F.smooth_l1_loss(b[:, 0], second_target, beta=beta)) / 2
    uncertainty = (((a[:, 0] - first_target).abs().detach() / a[:, 1] + a[:, 1].log()).mean() +
                   ((b[:, 0] - second_target).abs().detach() / b[:, 1] + b[:, 1].log()).mean()) / 2
    eligible = valid_features(first, config) & valid_features(second, config)
    consistency = F.smooth_l1_loss(a[eligible, 0] - b[eligible, 0],
                                 first_target[eligible] - second_target[eligible], beta=beta) if eligible.any() else point * 0
    loss = point + config["uncertainty_loss_weight"] * uncertainty + config["consistency_loss_weight"] * consistency
    return loss


@torch.inference_mode()
def normalization(bank, extractor, config):
    total, sums, squares, references = 0, torch.zeros(776, dtype=torch.float64), torch.zeros(776, dtype=torch.float64), []
    batch_size = config["batch_size"]
    for shift in config["normalization_shifts_ev"]:
        for start in range(0, len(bank), batch_size):
            batch = bank[start:start + batch_size]
            features = extractor(batch, torch.full((len(batch),), shift, device=bank.device)).cpu().double()
            total += len(features)
            sums += features.sum(0)
            squares += features.square().sum(0)
            if shift == 0:
                references.append(features[:, 768])
    mean = sums / total
    std = (squares / total - mean.square()).clamp_min(0).sqrt().clamp_min(config["minimum_feature_std"])
    return dict(mean=mean.float().tolist(), std=std.float().tolist(),
                reference_brightness_median_ev=float(torch.cat(references).median()),
                training_roi_count=len(bank), observations=total, source_split="train")


def read_training_state(path):
    return torch.load(path, map_location="cpu", weights_only=True)


def fit_seed(cache, index, root, seed, config, device):
    # Integrity is rechecked for every run, including a checkpoint resume.
    verify_cache(cache, originals=True)
    seed_everything(seed)
    run = root / f"seed-{seed}"
    run.mkdir(parents=True, exist_ok=True)
    data_hash = sha256(Path(cache) / "index.json")
    code = source_hashes()
    identity = dict(config_sha256=config_hash(config), cache_index_sha256=data_hash, source_files=code,
                    feature_version=FEATURE_VERSION, seed=seed)
    complete_path = run / "completed.json"
    if complete_path.exists():
        completed = json.loads(complete_path.read_text(encoding="utf-8"))
        if completed["identity"] != identity or sha256(run / "best.pt") != completed["best_sha256"]:
            raise ValueError("Completed run identity/checkpoint differs")
        emit("reused-completed", seed=seed, best_epoch=completed["best_epoch"])
        return completed
    bank, _ = load_bank(cache, index, "train", device)
    val_bank, val_metadata = load_bank(cache, index, "val", device)
    extractor = FeatureExtractor().to(device)
    norm_path = root / "normalization.json"
    if norm_path.exists():
        norm = json.loads(norm_path.read_text(encoding="utf-8"))
        if norm["cache_index_sha256"] != data_hash or norm["config_sha256"] != config_hash(config):
            raise ValueError("Normalization provenance mismatch")
    else:
        norm = normalization(bank, extractor, config)
        norm.update(cache_index_sha256=data_hash, config_sha256=config_hash(config), feature_version=FEATURE_VERSION)
        atomic_json(norm_path, norm)
    val_x, val_y, val_records = fixed_features(val_bank, val_metadata, extractor, config["validation_shifts_ev"], config["batch_size"])
    del val_bank
    model = ExposureMLP(torch.tensor(norm["mean"]), torch.tensor(norm["std"])).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=config["learning_rate"], weight_decay=config["weight_decay"])

    def rate(epoch):
        if epoch < config["warmup_epochs"]:
            return (epoch + 1) / config["warmup_epochs"]
        progress = (epoch - config["warmup_epochs"]) / max(config["max_epochs"] - config["warmup_epochs"], 1)
        floor = config["minimum_learning_rate"] / config["learning_rate"]
        return floor + (1 - floor) * (1 + math.cos(math.pi * min(progress, 1))) / 2

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, rate)
    start_epoch, best, best_epoch, stale, history = 0, math.inf, 0, 0, []
    if (run / "last.pt").exists():
        previous = read_training_state(run / "last.pt")
        if previous["identity"] != identity:
            raise ValueError("Resume rejected: data/config/source version changed")
        model.load_state_dict(previous["model"])
        optimizer.load_state_dict(previous["optimizer"])
        scheduler.load_state_dict(previous["scheduler"])
        start_epoch, best, best_epoch, stale = previous["epoch"], previous["best_mae"], previous["best_epoch"], previous["stale"]
        history = previous["history"]
        restore_rng(previous["rng"])
        emit("resume", seed=seed, epoch=start_epoch)
    emit("training-start", seed=seed, train_rois=len(bank), validation_originals=len({r["id"] for r in val_records}),
         device=str(device), parameters=sum(p.numel() for p in model.parameters()))
    for epoch in range(start_epoch, config["max_epochs"]):
        if stale >= config["patience"]:
            break
        began = time.perf_counter()
        model.train()
        permutation = torch.randperm(len(bank)).to(device)
        total_loss = 0.0
        for start in range(0, len(bank), config["batch_size"]):
            batch = bank[permutation[start:start + config["batch_size"]]]
            first_delta = draw_augmentation(len(batch), config, device)
            second_delta = draw_augmentation(len(batch), config, device)
            with torch.no_grad():
                first, second = extractor(batch, first_delta), extractor(batch, second_delta)
            optimizer.zero_grad(set_to_none=True)
            loss = training_loss(model, first, second, -first_delta, -second_delta, config)
            if not torch.isfinite(loss):
                raise FloatingPointError("Non-finite training loss")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimizer.step()
            total_loss += float(loss.detach()) * len(batch)
        scheduler.step()
        predictions = predict(model, val_x, config["batch_size"])
        metrics = summarize(predictions, val_y, val_records, val_x, config)
        score = metrics["all"]["mae_ev"]
        improved = score < best - 1e-6
        if improved:
            best, best_epoch, stale = score, epoch + 1, 0
        else:
            stale += 1
        entry = dict(epoch=epoch + 1, training_loss=total_loss / len(bank), validation_mae_ev=score,
                     validation_valid_mae_ev=metrics["valid"]["mae_ev"],
                     validation_original_bias_ev=metrics["original_zero_shift"]["bias_ev"],
                     learning_rate=optimizer.param_groups[0]["lr"], seconds=time.perf_counter() - began)
        history.append(entry)
        checkpoint = dict(identity=identity, epoch=epoch + 1, model=model.state_dict(), optimizer=optimizer.state_dict(),
                          scheduler=scheduler.state_dict(), rng=rng_state(), best_mae=best, best_epoch=best_epoch,
                          stale=stale, history=history)
        if improved:
            atomic_checkpoint(run / "best.pt", dict(identity=identity, epoch=epoch + 1,
                                                     model=model.state_dict(), validation_metrics=metrics))
        atomic_checkpoint(run / "last.pt", checkpoint)
        atomic_json(run / "history.json", history)
        if (epoch + 1) % 5 == 0 or epoch == start_epoch:
            emit("epoch", seed=seed, **entry, best_epoch=best_epoch, stale=stale)
    if not (run / "best.pt").exists():
        raise RuntimeError("No completed epoch exists")
    completed = dict(identity=identity, finished_at=now(), best_epoch=best_epoch, best_validation_mae_ev=best,
                     epochs_completed=history[-1]["epoch"], best_sha256=sha256(run / "best.pt"),
                     stop_reason="early-stopping" if stale >= config["patience"] else "epoch-budget")
    atomic_json(complete_path, completed)
    emit("seed-completed", seed=seed, best_epoch=best_epoch, best_validation_mae_ev=best)
    return completed


def load_model(path, device):
    checkpoint = read_training_state(path)
    model = ExposureMLP().to(device)
    model.load_state_dict(checkpoint["model"])
    model.eval()
    return model, checkpoint


def cpu_benchmark(model):
    previous_threads = torch.get_num_threads()
    torch.set_num_threads(1)
    cpu_model = copy.deepcopy(model).cpu().eval()
    example = cpu_model.feature_mean[None, :].clone()
    timings = []
    with torch.inference_mode():
        for _ in range(100):
            cpu_model(example)
        for _ in range(500):
            began = time.perf_counter_ns()
            cpu_model(example)
            timings.append((time.perf_counter_ns() - began) / 1e6)
    torch.set_num_threads(previous_threads)
    return dict(platform=platform.platform(), processor=platform.processor(), torch_version=str(torch.__version__),
                batch_size=1, threads=1, warmup=100, samples=500,
                p50_ms=float(np.median(timings)), p95_ms=float(np.quantile(timings, 0.95)),
                includes="feature normalization, MLP and output transforms; excludes image feature extraction",
                android_measurement=False)


def export_model(model, output, comparison_features, config):
    import onnx
    import onnxruntime as ort
    output.mkdir(parents=True, exist_ok=True)
    cpu_model = copy.deepcopy(model).cpu().eval()
    save_file({name: tensor.detach().cpu().contiguous() for name, tensor in cpu_model.state_dict().items()},
              str(output / "model.safetensors"), metadata={"feature_version": FEATURE_VERSION, "status": "reference-exposure-pretraining"})
    example = comparison_features[:2].detach().cpu()
    torch.onnx.export(cpu_model, (example,), str(output / "model.onnx"), opset_version=config["onnx_opset"],
                      input_names=["features"], output_names=["exposure"], dynamo=False,
                      dynamic_axes={"features": {0: "batch"}, "exposure": {0: "batch"}})
    onnx.checker.check_model(onnx.load(str(output / "model.onnx")))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(output / "model.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    maximum = np.zeros(2)
    with torch.inference_mode():
        for start in range(0, len(comparison_features), config["batch_size"]):
            features = comparison_features[start:start + config["batch_size"]].detach().cpu()
            reference = cpu_model(features).numpy()
            exported = session.run(None, {"features": features.numpy()})[0]
            maximum = np.maximum(maximum, np.abs(reference - exported).max(0))
    check = dict(compensation_max_abs_difference_ev=float(maximum[0]), scale_max_abs_difference_ev=float(maximum[1]),
                 tolerance_ev=0.001, passed=bool(maximum.max() <= 0.001),
                 model_sha256=sha256(output / "model.onnx"), safetensors_sha256=sha256(output / "model.safetensors"),
                 onnx_version=str(onnx.__version__), onnxruntime_version=str(ort.__version__))
    atomic_json(output / "export-check.json", check)
    if not check["passed"]:
        raise AssertionError("ONNX export numerical mismatch")
    atomic_json(output / "features.json", feature_spec())
    atomic_json(output / "normalization.json", dict(mean=cpu_model.feature_mean.tolist(), std=cpu_model.feature_std.tolist(),
                                                  uncertainty_factor=float(cpu_model.uncertainty_factor)))
    return check


def finalize(cache, index, root, completed, config, device):
    selection_path = root / "selection.json"
    selected = min(completed, key=lambda entry: entry["best_validation_mae_ev"])
    selection = dict(seed=selected["identity"]["seed"], epoch=selected["best_epoch"],
                     checkpoint_sha256=selected["best_sha256"],
                     criterion="minimum validation original-macro MAE over all fixed shifts and ROIs; test untouched",
                     runs=completed)
    if selection_path.exists() and json.loads(selection_path.read_text(encoding="utf-8")) != selection:
        raise ValueError("Frozen selection differs")
    atomic_json(selection_path, selection)
    model, checkpoint = load_model(root / f"seed-{selection['seed']}" / "best.pt", device)
    extractor = FeatureExtractor().to(device)
    val_bank, val_metadata = load_bank(cache, index, "val", device)
    val_x, val_y, val_records = fixed_features(val_bank, val_metadata, extractor, config["validation_shifts_ev"], config["batch_size"])
    del val_bank
    uncalibrated = predict(model, val_x, config["batch_size"])
    calibration = calibrate_uncertainty(uncalibrated, val_y, val_x, config, val_records)
    model.uncertainty_factor.fill_(calibration["factor"])
    atomic_json(root / "confidence-calibration.json", calibration)
    validation = summarize(predict(model, val_x, config["batch_size"]), val_y, val_records, val_x, config, calibration["threshold_ev"])
    # Final test is loaded only after the winner and confidence rule are frozen.
    test_bank, test_metadata = load_bank(cache, index, "test", device)
    test_x, test_y, test_records = fixed_features(test_bank, test_metadata, extractor, config["validation_shifts_ev"], config["batch_size"])
    del test_bank
    predictions = predict(model, test_x, config["batch_size"])
    metrics = summarize(predictions, test_y, test_records, test_x, config, calibration["threshold_ev"])
    norm = json.loads((root / "normalization.json").read_text(encoding="utf-8"))
    zero = torch.stack((torch.zeros_like(test_y), torch.ones_like(test_y)), -1)
    brightness = torch.stack(((norm["reference_brightness_median_ev"] - test_x[:, 768]).clamp(-4, 4), torch.ones_like(test_y)), -1)
    baselines = dict(zero_compensation=summarize(zero, test_y, test_records, test_x, config),
                     training_reference_brightness=summarize(brightness, test_y, test_records, test_x, config))
    valid = metrics["valid"]
    acceptance = dict(valid_mae=valid["mae_ev"] is not None and valid["mae_ev"] <= config["target_mae_ev"],
                      valid_p90=valid["p90_absolute_error_ev"] is not None and valid["p90_absolute_error_ev"] <= config["target_p90_ev"],
                      original_bias=abs(metrics["original_zero_shift"]["bias_ev"]) <= config["target_original_bias_ev"],
                      beats_simple_brightness=metrics["all"]["mae_ev"] < baselines["training_reference_brightness"]["all"]["mae_ev"])
    export_check = export_model(model, root / "export", val_x, config)
    acceptance["export_consistency"] = export_check["passed"]
    report = dict(status="reference-exposure-pretraining", generated_at=now(), selection=selection,
                  dataset=dict(originals=len(index["items"]), splits={split:sum(item["record"]["split"] == split for item in index["items"])
                                                                      for split in ("train", "val", "test")},
                               manifest_sha256=index["source_manifest_sha256"], cache_index_sha256=sha256(Path(cache) / "index.json")),
                  validation=validation, test=metrics, baselines=baselines, acceptance=acceptance,
                  acceptance_passed=all(acceptance.values()), confidence=calibration,
                  model=dict(architecture=[776, 64, 32, 2], parameters=sum(p.numel() for p in model.parameters()),
                             macs=51776, fp32_parameter_bytes=sum(p.numel() * p.element_size() for p in model.parameters())),
                  cpu_inference=cpu_benchmark(model), export_check=export_check,
                  limitations=["Assumes reference photographs have suitable exposure; not absolute EV100 supervision",
                               "Only 16 held-out originals; crops and exposure variants are correlated",
                               "Synthetic sRGB-derived linear exposures do not reproduce a real RAW/ISP pipeline",
                               "No Android integration or mobile latency/energy validation",
                               "Training and source image licences are separate; source attribution retained"],
                  sources=source_hashes())
    write_predictions(root / "test-predictions.csv", predictions, test_y, test_records, test_x, calibration["threshold_ev"])
    atomic_json(root / "test-per-original.json", per_original_rows(predictions, test_y, test_records, test_x, config))
    atomic_json(root / "report.json", report)
    write_report(root, report)
    write_model_card(root, report, index)
    emit("finished", output=str(root), selected_seed=selection["seed"], test_mae=metrics["all"]["mae_ev"],
         valid_mae=metrics["valid"]["mae_ev"], acceptance_passed=report["acceptance_passed"])
    return report


def write_report(root, report):
    def line(name, value):
        return f"| {name} | {value['mae_ev']:.4f} | {value['p90_absolute_error_ev']:.4f} | {value['over_1ev_rate']:.2%} |"
    rows = [line("零补偿", report["baselines"]["zero_compensation"]["all"]),
            line("训练集参考亮度基线", report["baselines"]["training_reference_brightness"]["all"]),
            line("MLP 全部样本", report["test"]["all"])]
    if report["test"]["valid"]["samples"]:
        rows.append(line("MLP 有效样本", report["test"]["valid"]))
    result = "通过" if report["acceptance_passed"] else "未通过"
    text = f"""# 参考曝光预训练首轮结果

生成时间（UTC）：{report['generated_at']}。首轮验收：**{result}**。

原图被假定曝光合适，标签是合成曝光扰动的相反数；此结果不证明绝对 EV100、真实 RAW/YUV 通用性或手机性能。

## 数据与选择

共 {report['dataset']['originals']} 张原图，划分 {report['dataset']['splits']}。每张 16 个固定 ROI，固定测试曝光档数，选择只使用验证集。最终选择 seed {report['selection']['seed']}、epoch {report['selection']['epoch']}。仅有 16 张测试原图；派生样本不能算作独立场景。

## 封存测试集

| 方法 | 原图宏平均 MAE / EV | 样本绝对误差 P90 / EV | 超过 1 EV 比例（原图宏平均） |
| --- | ---: | ---: | ---: |
{chr(10).join(rows)}

有效样本比例：{report['test']['valid_fraction']:.2%}。原图零扰动偏差：{report['test']['original_zero_shift']['bias_ev']:.4f} EV。
可信度阈值完全由验证集确定，测试有效样本覆盖率：{report['test']['confidence_valid_coverage']:.2%}。它不是准确概率，也不能替代完整测试结果。

## 验收及导出

{json.dumps(report['acceptance'], ensure_ascii=False, indent=2)}

ONNX 最大补偿差异：{report['export_check']['compensation_max_abs_difference_ev']:.8f} EV。单线程桌面 CPU 网络推理 P50/P95：{report['cpu_inference']['p50_ms']:.4f}/{report['cpu_inference']['p95_ms']:.4f} ms，包含固定特征归一化，不包含图像解码与特征提取；不是手机实测。

完整指标、裁切/高光/低信号分组见 report.json，逐图指标见 test-per-original.json，逐样本输出见 test-predictions.csv。未通过项保留原样，不以测试集重新调参。
"""
    (root / "REPORT_ZH.md").write_text(text, encoding="utf-8")


def write_model_card(root, report, index):
    atomic_json(root / "export" / "data-provenance.json", dict(
        source_manifest_sha256=index["source_manifest_sha256"],
        originals=[dict(id=item["record"]["id"], sha256=item["record"]["sha256"],
                        split=item["record"]["split"], source_license=item["record"]["source_license"],
                        provenance=item["record"].get("source_record", {})) for item in index["items"]]))
    card = dict(status="reference-exposure-pretraining; not approved for photographic metering",
                code_license="Apache-2.0", newly_trained_weights_license="Apache-2.0",
                initial_pretrained_weights=None, model=report["model"], feature_version=FEATURE_VERSION,
                input="float32 [batch,776], unstandardized features per features.json; normalization is inside graph",
                output="float32 [batch,2]: positive EV compensation brightens; second value estimates error scale in EV",
                reference_assumption="Original decoded reference exposure is suitable, including its dark/bright intent",
                uncertainty=report["confidence"], acceptance=report["acceptance"],
                intended_use="offline reference-exposure research only; application behaviour remains unchanged",
                prohibited_claims=["calibrated absolute illuminance", "proven RAW/YUV equivalence", "verified mobile performance"],
                data_licences=["CC0-1.0", "CC-BY-4.0; retain attribution when using/redistributing source material"],
                limitations=report["limitations"], source_files=report["sources"])
    atomic_json(root / "export" / "model-card.json", card)
    licence = SCRIPT_ROOT.parent.parent / "LICENSE"
    shutil.copyfile(licence, root / "export" / "LICENSE")


def run_all(manifest, cache, root, config, device):
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    if (root / "report.json").exists():
        raise ValueError("Final test already exists; use a new experiment directory for new work, not test-driven retuning")
    if (root / "config.json").exists() and json.loads((root / "config.json").read_text(encoding="utf-8")) != config:
        raise ValueError("Existing experiment config differs")
    atomic_json(root / "config.json", config)
    snapshot = root / "source-snapshot"
    snapshot.mkdir(exist_ok=True)
    for filename, digest in source_hashes().items():
        destination = snapshot / filename
        if destination.exists() and sha256(destination) != digest:
            raise ValueError("Frozen source snapshot differs")
        if not destination.exists():
            shutil.copyfile(SCRIPT_ROOT / filename, destination)
    index = prepare_cache(manifest, cache)
    atomic_json(root / "environment.json", dict(generated_at=now(), python=sys.version, torch=str(torch.__version__),
                                               cuda_runtime=str(torch.version.cuda), device=str(device),
                                               gpu=torch.cuda.get_device_name(0) if device.type == "cuda" else None,
                                               packages={name: importlib.metadata.version(name) for name in
                                                         ("numpy", "Pillow", "safetensors", "onnx", "onnxruntime")},
                                               config_sha256=config_hash(config), sources=source_hashes()))
    completed = [fit_seed(cache, index, root, seed, config, device) for seed in config["seeds"]]
    return finalize(cache, index, root, completed, config, device)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prepare = sub.add_parser("prepare")
    prepare.add_argument("--manifest", type=Path, required=True)
    prepare.add_argument("--cache", type=Path, required=True)
    run = sub.add_parser("run")
    run.add_argument("--manifest", type=Path, required=True)
    run.add_argument("--cache", type=Path, required=True)
    run.add_argument("--output", type=Path, required=True)
    run.add_argument("--config", type=Path, default=SCRIPT_ROOT / "config.json")
    evaluate = sub.add_parser("evaluate")
    evaluate.add_argument("--cache", type=Path, required=True)
    evaluate.add_argument("--checkpoint", type=Path, required=True)
    evaluate.add_argument("--split", choices=["val", "test"], default="val")
    evaluate.add_argument("--config", type=Path, default=SCRIPT_ROOT / "config.json")
    evaluate.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    if arguments.command == "prepare":
        index = prepare_cache(arguments.manifest, arguments.cache)
        emit("prepared", originals=len(index["items"]))
        return
    config = json.loads(arguments.config.read_text(encoding="utf-8"))
    device = initialize_runtime(config)
    if arguments.command == "run":
        run_all(arguments.manifest, arguments.cache, arguments.output, config, device)
    else:
        index = verify_cache(arguments.cache)
        bank, metadata = load_bank(arguments.cache, index, arguments.split, device)
        features, targets, records = fixed_features(bank, metadata, FeatureExtractor().to(device), config["validation_shifts_ev"], config["batch_size"])
        model, _ = load_model(arguments.checkpoint, device)
        atomic_json(arguments.output, summarize(predict(model, features, config["batch_size"]), targets, records, features, config))


if __name__ == "__main__":
    main()
