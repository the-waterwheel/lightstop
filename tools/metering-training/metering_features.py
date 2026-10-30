# SPDX-License-Identifier: Apache-2.0
"""776 camera-relative exposure features, without per-image mean removal."""
import torch
from torch import nn

FEATURE_VERSION = "relative-image-ev-grid-v1"
GRID_COLUMNS, GRID_ROWS, CELL_EDGE = 12, 8, 8
FEATURE_COUNT = 776
SIGNAL_FLOOR = 1e-6
CLIPPED_LEVEL = 0.98

CELL_NAMES = ["mean_ev_minus_base", "std_ev", "p10_ev_minus_base", "p90_ev_minus_base",
              "clipped_fraction", "dark_fraction", "log2_mean_r_over_g", "log2_mean_b_over_g"]
GLOBAL_NAMES = ["base_relative_ev", "highlight_minus_base_ev", "p90_minus_p10_ev", "clipped_fraction",
                "dark_fraction", "valid_coverage", "log2_mean_r_over_g", "log2_mean_b_over_g"]


def feature_spec():
    return dict(version=FEATURE_VERSION, dimensions=FEATURE_COUNT, grid=[GRID_ROWS, GRID_COLUMNS],
                samples_per_cell=[CELL_EDGE, CELL_EDGE], cell_features=CELL_NAMES, global_features=GLOBAL_NAMES,
                layout="96 row-major cells, eight interleaved features per cell; then eight global features",
                input="RGB uint8 sRGB sample points, shape [N,3,64,96]",
                exposure_augmentation="linearRGB=srgb_decode(input); clamp(linearRGB*2**deltaEV,0,1)",
                luma="0.2126*R+0.7152*G+0.0722*B", ev="log2(max(luma,1e-6)/0.18)",
                base="exact fractional area mean of sorted EV samples over [0.05,0.95]",
                highlight="exact fractional area mean over [0.98,0.995]",
                quantiles="linear interpolation at q*(sample_count-1)",
                dark="luma<=1e-6", clipped="any linear channel>=0.98",
                ratios="log2(max(mean(channel),1e-6)/max(mean(G),1e-6))",
                standard_deviation="population, including censored samples",
                valid_coverage="1 for complete rectangular ROI; no censored area is removed",
                normalization="fixed training-only mean/std; never per-image global brightness normalization",
                output="[exposure_compensation_ev, estimated_absolute_error_scale_ev]; positive compensation brightens",
                absolute_ev100=False)


def interval_weights(count, low, high):
    positions = torch.arange(count, dtype=torch.float32)
    begin, end = low * count, high * count
    return (torch.minimum(positions + 1, torch.tensor(end)) -
            torch.maximum(positions, torch.tensor(begin))).clamp_min(0) / (end - begin)


def sorted_quantile(values, quantile):
    position = quantile * (values.shape[-1] - 1)
    left = int(position)
    right = min(left + 1, values.shape[-1] - 1)
    return values[..., left] + (values[..., right] - values[..., left]) * (position - left)


def srgb_decode(values):
    return torch.where(values <= 0.04045, values / 12.92, ((values + 0.055) / 1.055).pow(2.4))


class FeatureExtractor(nn.Module):
    def __init__(self):
        super().__init__()
        self.register_buffer("linear_lut", srgb_decode(torch.arange(256, dtype=torch.float32) / 255))
        self.register_buffer("base_weights", interval_weights(6144, 0.05, 0.95))
        self.register_buffer("highlight_weights", interval_weights(6144, 0.98, 0.995))

    @staticmethod
    def cells(values):
        return values.reshape(-1, 8, 8, 12, 8).permute(0, 1, 3, 2, 4).reshape(-1, 96, 64)

    def forward(self, encoded, delta_ev):
        linear = self.linear_lut[encoded.long()]
        exposed = (linear * torch.exp2(delta_ev[:, None, None, None])).clamp(0, 1)
        return self.from_linear(exposed)

    def from_linear(self, exposed):
        r, g, b = exposed[:, 0], exposed[:, 1], exposed[:, 2]
        luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        ev = torch.log2(luma.clamp_min(SIGNAL_FLOOR) / 0.18)
        clipped = (exposed.amax(dim=1) >= CLIPPED_LEVEL).float()
        dark = (luma <= SIGNAL_FLOOR).float()
        ordered = torch.sort(ev.flatten(1), dim=-1).values
        base = (ordered * self.base_weights).sum(-1)
        highlight = (ordered * self.highlight_weights).sum(-1)
        cells = self.cells(ev)
        ordered_cells = torch.sort(cells, dim=-1).values
        mean = cells.mean(-1)
        std = cells.std(-1, unbiased=False)
        rm, gm, bm = (self.cells(channel).mean(-1).clamp_min(SIGNAL_FLOOR) for channel in (r, g, b))
        region = torch.stack((mean - base[:, None], std,
                              sorted_quantile(ordered_cells, 0.1) - base[:, None],
                              sorted_quantile(ordered_cells, 0.9) - base[:, None],
                              self.cells(clipped).mean(-1), self.cells(dark).mean(-1),
                              torch.log2(rm / gm), torch.log2(bm / gm)), dim=-1)
        rg, gg, bg = (channel.flatten(1).mean(-1).clamp_min(SIGNAL_FLOOR) for channel in (r, g, b))
        global_values = torch.stack((base, highlight - base,
                                     sorted_quantile(ordered, 0.9) - sorted_quantile(ordered, 0.1),
                                     clipped.flatten(1).mean(-1), dark.flatten(1).mean(-1),
                                     torch.ones_like(base), torch.log2(rg / gg), torch.log2(bg / gg)), dim=-1)
        return torch.cat((region.flatten(1), global_values), dim=-1)


def valid_features(features, config):
    return ((features[:, 771] <= config["valid_max_clipped_fraction"]) &
            (features[:, 772] <= config["valid_max_dark_fraction"]))
