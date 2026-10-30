# SPDX-License-Identifier: Apache-2.0
import torch
from torch import nn
from torch.nn import functional as F


class ExposureMLP(nn.Module):
    def __init__(self, mean=None, std=None):
        super().__init__()
        self.register_buffer("feature_mean", torch.zeros(776) if mean is None else mean.detach().float().clone())
        self.register_buffer("feature_std", torch.ones(776) if std is None else std.detach().float().clone())
        self.register_buffer("uncertainty_factor", torch.tensor(1.0))
        self.layers = nn.Sequential(nn.Linear(776, 64), nn.ReLU(), nn.Linear(64, 32), nn.ReLU(), nn.Linear(32, 2))

    def forward(self, features):
        raw = self.layers((features - self.feature_mean) / self.feature_std)
        compensation = 4.0 * torch.tanh(raw[:, 0] / 4.0)
        scale = (F.softplus(raw[:, 1]) + 0.05).clamp_max(4.0) * self.uncertainty_factor
        return torch.stack((compensation, scale), dim=-1)
