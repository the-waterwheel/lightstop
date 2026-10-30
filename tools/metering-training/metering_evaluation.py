# SPDX-License-Identifier: Apache-2.0
"""Original-level metrics, fixed synthetic evaluation, and uncertainty calibration."""
import csv
from pathlib import Path

import numpy as np
import torch

from metering_features import valid_features


@torch.inference_mode()
def fixed_features(bank, metadata, extractor, shifts, batch_size):
    features, labels, records = [], [], []
    for shift in shifts:
        for start in range(0, len(bank), batch_size):
            batch = bank[start:start + batch_size]
            delta = torch.full((len(batch),), shift, device=bank.device)
            features.append(extractor(batch, delta))
            labels.append(-delta)
            records.extend(dict(row, augmentation_ev=float(shift), target_compensation_ev=-float(shift))
                           for row in metadata[start:start + batch_size])
    return torch.cat(features), torch.cat(labels), records


@torch.inference_mode()
def predict(model, features, batch_size):
    model.eval()
    return torch.cat([model(features[start:start + batch_size])
                      for start in range(0, len(features), batch_size)])


def subset_metrics(predictions, targets, records, mask):
    indices = np.flatnonzero(mask)
    if len(indices) == 0:
        return dict(samples=0, originals=0, mae_ev=None, p90_absolute_error_ev=None,
                    p90_original_mae_ev=None, over_1ev_rate=None, bias_ev=None)
    errors = predictions[indices] - targets[indices]
    absolute = np.abs(errors)
    groups = {}
    for index, error in zip(indices, errors):
        groups.setdefault(records[index]["id"], []).append(float(error))
    per_original = [np.asarray(values) for values in groups.values()]
    return dict(samples=len(indices), originals=len(groups),
                mae_ev=float(np.mean([np.abs(values).mean() for values in per_original])),
                p90_absolute_error_ev=float(np.quantile(absolute, 0.9)),
                p90_original_mae_ev=float(np.quantile([np.abs(values).mean() for values in per_original], 0.9)),
                over_1ev_rate=float(np.mean([(np.abs(values) > 1).mean() for values in per_original])),
                bias_ev=float(np.mean([values.mean() for values in per_original])))


def summarize(predictions, targets, records, features, config, threshold=None):
    if isinstance(predictions, torch.Tensor):
        predictions = predictions.detach().cpu().numpy()
    if isinstance(targets, torch.Tensor):
        targets = targets.detach().cpu().numpy()
    if isinstance(features, torch.Tensor):
        features = features.detach().cpu().numpy()
    valid = ((features[:, 771] <= config["valid_max_clipped_fraction"]) &
             (features[:, 772] <= config["valid_max_dark_fraction"]))
    masks = dict(all=np.ones(len(records), dtype=bool), valid=valid,
                 severe_clipping=features[:, 771] > config["valid_max_clipped_fraction"],
                 low_signal=features[:, 772] > config["valid_max_dark_fraction"],
                 full_frame=np.asarray([row["roi"] == "full" for row in records]),
                 cropped=np.asarray([row["roi"] != "full" for row in records]),
                 original_zero_shift=np.asarray([row["augmentation_ev"] == 0 for row in records]))
    if threshold is not None:
        masks["confidence_accepted"] = predictions[:, 1] <= threshold
        masks["confidence_accepted_valid"] = masks["confidence_accepted"] & valid
    result = {name: subset_metrics(predictions[:, 0], targets, records, mask) for name, mask in masks.items()}
    result["valid_fraction"] = float(valid.mean())
    result["confidence_coverage"] = float(masks["confidence_accepted"].mean()) if threshold is not None else None
    result["confidence_valid_coverage"] = (float(masks["confidence_accepted_valid"].sum() / max(valid.sum(), 1))
                                           if threshold is not None else None)
    result["statistical_unit"] = "original image; ROI/exposure errors are correlated; tail quantiles are descriptive"
    return result


def calibrate_uncertainty(predictions, targets, features, config, records=None):
    predictions = predictions.detach().cpu().numpy()
    targets = targets.detach().cpu().numpy()
    valid = valid_features(features, config).cpu().numpy()
    if not valid.any():
        return dict(factor=1.0, threshold_ev=0.0, feasible=False, validation_coverage=0.0)
    errors = np.abs(predictions[:, 0] - targets)
    if records is None:
        records = [dict(id=str(index)) for index in range(len(targets))]
    identities = np.asarray([row["id"] for row in records])

    def macro_mean(values, mask):
        return float(np.mean([values[mask & (identities == identity)].mean()
                              for identity in sorted(set(identities[mask]))]))

    factor = float(np.clip(macro_mean(errors, valid) / max(macro_mean(predictions[:, 1], valid), 1e-6), 0.01, 100.0))
    scales = predictions[:, 1] * factor
    chosen = None
    for coverage in (1.0, 0.95, 0.9, 0.85, 0.8):
        if coverage + 1e-9 < config["confidence_minimum_coverage"]:
            continue
        # Inclusive order statistics guarantee the advertised minimum coverage;
        # interpolated quantiles can leave fewer than ceil(N * coverage) rows.
        threshold = float(np.quantile(scales[valid], coverage, method="higher"))
        selected = valid & (scales <= threshold)
        if macro_mean(errors, selected) <= config["target_mae_ev"] and np.quantile(errors[selected], 0.9) <= config["target_p90_ev"]:
            chosen = (threshold, float(selected.sum() / valid.sum()), True)
            break
    if chosen is None:
        threshold = float(np.quantile(scales[valid], config["confidence_minimum_coverage"], method="higher"))
        chosen = threshold, float((valid & (scales <= threshold)).sum() / valid.sum()), False
    return dict(factor=factor, threshold_ev=chosen[0], validation_coverage=chosen[1], feasible=chosen[2],
                method="validation original-macro absolute error / original-macro predicted scale; widest passing valid-sample coverage >=80%",
                probability_claim=False)


def per_original_rows(predictions, targets, records, features, config):
    predicted = predictions.detach().cpu().numpy() if isinstance(predictions, torch.Tensor) else predictions
    target = targets.detach().cpu().numpy() if isinstance(targets, torch.Tensor) else targets
    feature = features.detach().cpu().numpy() if isinstance(features, torch.Tensor) else features
    rows = []
    for identity in sorted({row["id"] for row in records}):
        mask = np.asarray([row["id"] == identity for row in records])
        metrics = subset_metrics(predicted[:, 0], target, records, mask)
        original = mask & np.asarray([row["augmentation_ev"] == 0 for row in records])
        metrics.update(id=identity, original_zero_shift_bias_ev=float(predicted[original, 0].mean()),
                       mean_estimated_error_scale_ev=float(predicted[mask, 1].mean()),
                       severe_clipping_fraction=float((feature[mask, 771] > config["valid_max_clipped_fraction"]).mean()))
        rows.append(metrics)
    return rows


def write_predictions(path, predictions, targets, records, features, threshold):
    predicted = predictions.detach().cpu().numpy()
    feature = features.detach().cpu().numpy()
    fields = ["id", "split", "roi", "source", "augmentation_ev", "target_compensation_ev",
              "predicted_compensation_ev", "error_ev", "estimated_error_scale_ev", "confidence_accepted",
              "clipped_fraction", "dark_fraction"]
    with Path(path).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for index, record in enumerate(records):
            writer.writerow(dict(record, predicted_compensation_ev=float(predicted[index, 0]),
                                 error_ev=float(predicted[index, 0] - record["target_compensation_ev"]),
                                 estimated_error_scale_ev=float(predicted[index, 1]),
                                 confidence_accepted=bool(predicted[index, 1] <= threshold),
                                 clipped_fraction=float(feature[index, 771]), dark_fraction=float(feature[index, 772])))
