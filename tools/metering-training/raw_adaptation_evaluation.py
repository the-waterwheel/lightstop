# SPDX-License-Identifier: Apache-2.0
"""Train-only baseline anchors and common-subset comparisons for each input domain."""
from pathlib import Path
import torch

from metering_baselines import CORE_PATH, app_policy

METHODS = ("project_global", "uniform_ev", "uniform_linear")


def policy():
    frozen = Path(__file__).with_name("EvaluativeMetering.kt")
    return app_policy(frozen if frozen.exists() else CORE_PATH)


def baseline_scores(linear, features):
    p = policy()
    base, high_difference = features[:,768], features[:,769]
    clipped, dark = features[:,771], features[:,772]
    common = (clipped <= 1-p["BASE_HIGH"]+p["EPSILON"]) & (dark <= p["BASE_LOW"]+p["EPSILON"])
    high_ok = (clipped <= 1-p["HIGHLIGHT_HIGH"]+p["EPSILON"]) & (dark <= p["HIGHLIGHT_LOW"]+p["EPSILON"])
    reduction = torch.where(high_ok, (high_difference-p["HIGHLIGHT_HEADROOM_EV"]).clamp(0,p["MAX_REDUCTION_EV"]), torch.zeros_like(base))
    luma = .2126*linear[:,0]+.7152*linear[:,1]+.0722*linear[:,2]
    return dict(project_global=base+reduction,
                uniform_ev=base+features[:,:768].reshape(-1,96,8)[:,:,0].mean(1),
                uniform_linear=(luma.flatten(1).mean(1).clamp_min(p["SIGNAL_FLOOR"])/.18).log2()), common


@torch.inference_mode()
def fit_anchors(bank, extractor, augmenter, config):
    values = {domain:{k:[] for k in METHODS} for domain in ("legacy_jpeg","canonical")}
    counts = {domain:0 for domain in values}
    for start in range(0,len(bank),config["batch_size"]):
        batch = bank[start:start+config["batch_size"]]
        domains = dict(legacy_jpeg=extractor.linear_lut[batch.long()],canonical=augmenter.reference(batch))
        for name, linear in domains.items():
            scores, common = baseline_scores(linear,extractor.from_linear(linear))
            counts[name] += int(common.sum())
            for key, value in scores.items(): values[name][key].append(value[common].cpu())
    anchors = {}
    for domain in values:
        if not counts[domain]: raise ValueError("No reliable training references for baseline calibration")
        anchors[domain] = {key:float(torch.quantile(torch.cat(parts),.5)) for key,parts in values[domain].items()}
    return dict(offset_ev=anchors,reliable_reference_rois=counts,
                method="One median offset per method/domain, training references only; no validation/test fitting")
