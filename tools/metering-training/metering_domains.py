# SPDX-License-Identifier: Apache-2.0
"""Sparse, independently implemented photometric adapters and synthetic ISP pairs.

Canonical coordinates are exposure-relative linear sRGB, never frame-normalized.
The inverse reference tone is an approximation for internet JPEGs, not recovered RAW.
"""
from dataclasses import dataclass

import torch

DOMAIN_VERSION = "scene-linear-sparse-pairs-v2"


def srgb_decode(x):
    return torch.where(x <= .04045, x / 12.92, ((x + .055) / 1.055).clamp_min(0).pow(2.4))


def srgb_encode(x):
    x = x.clamp(0, 1)
    return torch.where(x <= .0031308, 12.92*x, 1.055*x.pow(1/2.4)-.055)


def smoothstep(x):
    x = x.clamp(0, 1)
    return x*x*(3-2*x)


def inverse_smoothstep(x):
    return .5 - torch.sin(torch.asin((1-2*x.clamp(0, 1)).clamp(-1, 1))/3)


@dataclass(frozen=True)
class RawCalibration:
    """Sampled native RGB triplets after CFA gathering, before WB/CCM.

    A CFA reader must separately handle its two green sites/black offsets.
    Matrix rows map white-balanced camera RGB into canonical linear sRGB.
    correction_ev is the existing fixed device scale, not a per-image adjustment.
    """
    black: tuple = (0., 0., 0.)
    white: tuple = (4095., 4095., 4095.)
    gains: tuple = (1., 1., 1.)
    matrix: tuple = ((1., 0., 0.), (0., 1., 0.), (0., 0., 1.))
    correction_ev: float = 0.


def raw_rgb_to_linear(samples, calibration):
    if samples.ndim != 4 or samples.shape[1] != 3 or not torch.isfinite(samples).all():
        raise ValueError("Expected finite NCHW native RGB samples")
    def channel(values):
        value = torch.as_tensor(values, dtype=torch.float32, device=samples.device)
        if value.shape != (3,) or not torch.isfinite(value).all():
            raise ValueError("Expected three finite channel values")
        return value[None, :, None, None]
    black, white, gains = (channel(x) for x in (calibration.black, calibration.white, calibration.gains))
    matrix = torch.as_tensor(calibration.matrix, dtype=torch.float32, device=samples.device)
    if (white <= black).any() or (gains <= 0).any() or matrix.shape != (3, 3) or not torch.isfinite(matrix).all():
        raise ValueError("Invalid RAW calibration")
    scale = torch.tensor(float(calibration.correction_ev), device=samples.device).exp2()
    if not torch.isfinite(scale) or scale <= 0:
        raise ValueError("Invalid device EV scale")
    native = ((samples.float()-black)/(white-black)).clamp(0, 1)*gains
    return (torch.einsum("ij,njhw->nihw", matrix, native)*scale).clamp(0, 1)


def processed_rgb_to_linear(encoded, *, inverse_luts=None, correction_ev=0., response_anchors=None):
    """Real-capture adapter: supplied LUTs already include transfer inversion.

    Reuses camera-reported inverse curves and the app's existing EV response
    anchors. Does not additionally sRGB-decode a supplied inverse LUT result.
    """
    if encoded.ndim != 4 or encoded.shape[1] != 3 or not torch.isfinite(encoded).all():
        raise ValueError("Expected finite NCHW encoded RGB")
    if inverse_luts is None:
        linear = srgb_decode(encoded.clamp(0, 1))
    else:
        lut = torch.as_tensor(inverse_luts, dtype=torch.float32, device=encoded.device)
        if lut.ndim != 2 or lut.shape[0] != 3 or lut.shape[1] < 2 or not torch.isfinite(lut).all() or (lut[:,1:] < lut[:,:-1]).any():
            raise ValueError("Expected three monotonic inverse response LUTs")
        coordinate = encoded.clamp(0, 1)*(lut.shape[1]-1)
        low = coordinate.long(); high = (low+1).clamp_max(lut.shape[1]-1)
        channel = torch.arange(3, device=encoded.device)[None,:,None,None]
        linear = lut[channel,low]+(lut[channel,high]-lut[channel,low])*(coordinate-low)
    correction = torch.full_like(linear[:,0], float(correction_ev))
    if response_anchors is not None:
        anchors = torch.as_tensor(response_anchors, dtype=torch.float32, device=encoded.device)
        if anchors.ndim != 2 or anchors.shape[1] != 2 or len(anchors) < 1 or not torch.isfinite(anchors).all() or (anchors[1:,0] <= anchors[:-1,0]).any():
            raise ValueError("Invalid existing response anchors")
        luma = .2126*linear[:,0]+.7152*linear[:,1]+.0722*linear[:,2]
        if len(anchors) == 1: correction = torch.full_like(luma, anchors[0,1])
        else:
            right = torch.searchsorted(anchors[:,0].contiguous(), luma.contiguous()).clamp(1,len(anchors)-1)
            left = right-1
            weight = ((luma-anchors[left,0])/(anchors[right,0]-anchors[left,0])).clamp(0,1)
            correction = anchors[left,1]*(1-weight)+anchors[right,1]*weight
    if not torch.isfinite(correction).all(): raise ValueError("Invalid EV correction")
    return (linear*correction[:,None].exp2()).clamp(0,1)


def processed_to_linear(encoded, *, residual_gamma=1., residual_smoothstep=True):
    """Inverse of this experiment's known virtual ISP, after sRGB decoding.

    Real capture adapters must use actual transfer metadata/response calibration;
    residual_smoothstep is NOT assumed to be the curve of every phone.
    """
    linear = srgb_decode(encoded.clamp(0, 1))
    gamma = torch.as_tensor(residual_gamma, dtype=linear.dtype, device=linear.device)
    if not torch.isfinite(gamma).all() or (gamma <= 0).any():
        raise ValueError("Invalid tone exponent")
    linear = linear.pow(1/gamma)
    return inverse_smoothstep(linear) if residual_smoothstep else linear


def render_processed(linear, gamma=1., quantize=True):
    encoded = srgb_encode(smoothstep(linear).pow(gamma))
    return (encoded*255).round()/255 if quantize else encoded


class DomainAugmenter:
    def __init__(self, config):
        self.config = config

    def reference(self, encoded):
        linear = srgb_decode(encoded.float()/255)
        return inverse_smoothstep(linear) if self.config["reference_inverse"] == "smoothstep" else linear

    def pair(self, encoded, delta, *, noise=True, quantize=True):
        """Expose BEFORE the virtual ISP; RAW/processed share one noisy capture."""
        canonical = self.reference(encoded)*delta[:, None, None, None].exp2()
        shape = (len(encoded), 1, 1, 1)
        def uniform(low, high):
            return low+(high-low)*torch.rand(shape, device=encoded.device)
        gains = torch.cat((uniform(1.4, 2.4), torch.ones(shape, device=encoded.device), uniform(1.2, 2.0)), dim=1)
        native = (canonical/gains).clamp(0, 1)
        if noise:
            shot = uniform(*self.config["shot_noise_range"])
            read = uniform(*self.config["read_noise_range"])
            native = (native+torch.randn_like(native)*(native*shot+read.square()).sqrt()).clamp(0, 1)
        levels = float(2**self.config["raw_bits"]-1)
        if quantize:
            native = (native*levels).round()/levels
        raw = (native*gains).clamp(0, 1)
        gamma = uniform(*self.config["render_gamma_range"])
        processed = processed_to_linear(render_processed(raw, gamma, quantize), residual_gamma=gamma)
        return raw, processed
