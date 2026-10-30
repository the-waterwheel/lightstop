# SPDX-License-Identifier: Apache-2.0
"""Offline numerical core of the app's EV policy; no camera/RAW fusion emulation."""
import hashlib
from pathlib import Path
import re
import numpy as np

CORE_PATH = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/lightmeter/rawmeter/EvaluativeMetering.kt'
SUPPORTED_CORE_SHA256 = 'a2091706814da4a78c51268ff71accb65310464da4485fecdde447ee63733807'
NAMES = ('BASE_LOW', 'BASE_HIGH', 'HIGHLIGHT_LOW', 'HIGHLIGHT_HIGH',
         'HIGHLIGHT_HEADROOM_EV', 'MAX_REDUCTION_EV', 'SIGNAL_FLOOR', 'EPSILON')


def app_policy(path=CORE_PATH):
    if hashlib.sha256(Path(path).read_bytes()).hexdigest() != SUPPORTED_CORE_SHA256:
        raise ValueError('App core changed; review the numerical port and parity fixtures before publishing a new comparison')
    text = Path(path).read_text(encoding='utf-8')
    values = {}
    for name in NAMES:
        match = re.search(r'const val '+name+r'\s*=\s*([0-9.eE+-]+)', text)
        if not match:
            raise ValueError('Application constant missing: '+name)
        values[name] = float(match.group(1))
    return values


def interval_mean(sorted_values, low, high):
    values = np.asarray(sorted_values, dtype=np.float64)
    count = values.shape[-1]
    if not count or not 0 <= low < high <= 1:
        raise ValueError('Invalid percentile interval')
    index = np.arange(count, dtype=np.float64)
    weights = np.maximum(0, np.minimum(index+1, high*count)-np.maximum(index, low*count))
    return np.sum(values*weights,axis=-1)/((high-low)*count)


def scores(linear_rgb, policy=None):
    """N,3,H,W -> brightness scores; correction is frozen_anchor - score.

    Camera EV100, device response calibration and shorter-RAW highlight recovery
    are unavailable in the photographic references and deliberately not invented.
    """
    policy = policy or app_policy()
    rgb = np.asarray(linear_rgb,dtype=np.float64)
    if rgb.ndim != 4 or rgb.shape[1] != 3 or not np.isfinite(rgb).all():
        raise ValueError('Expected finite NCHW RGB')
    luma = .2126*rgb[:,0]+.7152*rgb[:,1]+.0722*rgb[:,2]
    clipped = (rgb.max(axis=1)>=.98).reshape(len(rgb),-1).mean(axis=1)
    dark = (luma<=policy['SIGNAL_FLOOR']).reshape(len(rgb),-1).mean(axis=1)
    ev = np.log2(np.maximum(luma,policy['SIGNAL_FLOOR'])/.18).reshape(len(rgb),-1)
    ordered = np.sort(ev,axis=1)
    base = interval_mean(ordered,policy['BASE_LOW'],policy['BASE_HIGH'])
    high = interval_mean(ordered,policy['HIGHLIGHT_LOW'],policy['HIGHLIGHT_HIGH'])
    base_valid = (clipped<=1-policy['BASE_HIGH']+policy['EPSILON']) & (dark<=policy['BASE_LOW']+policy['EPSILON'])
    high_valid = (clipped<=1-policy['HIGHLIGHT_HIGH']+policy['EPSILON']) & (dark<=policy['HIGHLIGHT_LOW']+policy['EPSILON'])
    reduction = np.where(high_valid,np.clip(high-base-policy['HIGHLIGHT_HEADROOM_EV'],0,policy['MAX_REDUCTION_EV']),0)
    return dict(project_global=base+reduction,uniform_ev=ev.mean(axis=1),
                uniform_linear=np.log2(np.maximum(luma.reshape(len(rgb),-1).mean(axis=1),policy['SIGNAL_FLOOR'])/.18),
                base_reliable=base_valid,highlight_reliable=high_valid,clipped_fraction=clipped,dark_fraction=dark)


def core_sha256():
    return hashlib.sha256(CORE_PATH.read_bytes()).hexdigest()
