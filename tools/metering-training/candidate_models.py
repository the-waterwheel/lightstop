# SPDX-License-Identifier: Apache-2.0
"""Three no-face, two-output exposure regressors. No pretrained downloads."""
import torch
from torch import nn
from torch.nn import functional as F
from torchvision.models import mobilenet_v3_small
from metering_features import FeatureExtractor


def stats_input(linear):
    if linear.shape[-2:] != (128, 192):
        raise ValueError('CNN input contract is 192x128')
    return linear[..., 1::2, 1::2].contiguous()


class TinyBackbone(nn.Sequential):
    def __init__(self, channels):
        layers, ci = [], 3
        for i, co in enumerate(channels):
            if i == 0:
                layers += [nn.Conv2d(ci, co, 3, 2, 1), nn.ReLU()]
            else:
                layers += [nn.Conv2d(ci, ci, 3, 2, 1, groups=ci), nn.ReLU(),
                           nn.Conv2d(ci, co, 1), nn.ReLU()]
            ci = co
        super().__init__(*layers)


class Candidate(nn.Module):
    def __init__(self, name, mean, std):
        super().__init__()
        self.name = name
        self.register_buffer('feature_mean', mean.clone())
        self.register_buffer('feature_std', std.clone())
        if name == 'A':
            self.extractor = FeatureExtractor()
            self.stats = nn.Sequential(nn.Linear(776, 64), nn.ReLU(), nn.Linear(64, 32), nn.ReLU())
            self.backbone = TinyBackbone((16,32,64,96,128))
            self.head = nn.Sequential(nn.Linear(160,64), nn.ReLU(), nn.Linear(64,2))
        elif name == 'B':
            self.backbone = TinyBackbone((24,48,96,144,192))
            self.head = nn.Sequential(nn.Flatten(), nn.Linear(192*6,64), nn.ReLU(), nn.Linear(64,2))
        elif name == 'C':
            self.backbone = mobilenet_v3_small(weights=None, width_mult=.5)
            self.backbone.avgpool = GlobalMean()
            self.backbone.classifier = nn.Sequential(nn.Linear(288,64), nn.ReLU(), nn.Linear(64,2))
        else:
            raise ValueError(name)

    def forward(self, linear):
        if self.name == 'A':
            features = self.extractor.from_linear(stats_input(linear))
            s = self.stats((features-self.feature_mean)/self.feature_std)
            c = self.backbone(linear).mean(dim=(-2,-1))
            raw = self.head(torch.cat((s,c),1))
        elif self.name == 'B':
            raw = self.head(F.avg_pool2d(self.backbone(linear),2,2))
        else:
            raw = self.backbone(linear)
        return torch.stack((4*torch.tanh(raw[:,0]/4), (F.softplus(raw[:,1])+.05).clamp_max(4)),1)


class GlobalMean(nn.Module):
    def forward(self,x):return x.mean(dim=(-2,-1),keepdim=True)
