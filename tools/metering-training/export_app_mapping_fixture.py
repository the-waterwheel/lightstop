# SPDX-License-Identifier: Apache-2.0
"""Independent PyTorch oracle for the production Kotlin Global input adapter."""
from pathlib import Path
import numpy as np
import torch

from metering_domains import RawCalibration, processed_rgb_to_linear, raw_rgb_to_linear
from metering_features import FeatureExtractor


def export():
    torch.set_num_threads(2)
    n = 6144; index = torch.arange(n, dtype=torch.int64)
    gradient = torch.stack([((index*m+b)%997+1).float()/998 for m,b in ((13,37),(29,71),(47,103))]).reshape(1,3,64,96)
    raw4 = torch.stack([((index*m+b)%3072).float()/4095 for m,b in ((11,31),(17,53),(23,79),(37,97))])
    raw3 = torch.stack((raw4[0],(raw4[1]*1.1+raw4[2]*.9)*.5,raw4[3])).reshape(1,3,64,96)
    raw = raw_rgb_to_linear(raw3,RawCalibration(white=(1,1,1),gains=(1.4,1,1.7),
        matrix=((1.05,.03,-.02),(-.02,.98,.04),(.01,-.01,1.03)),correction_ev=.25))
    encoded = torch.stack([((index*m+b)%254+1).float()/255 for m,b in ((19,43),(31,61),(41,83))]).reshape(1,3,64,96)
    grid = np.linspace(0,1,17); codes = np.linspace(0,1,1025)
    inverse_luts = np.stack([np.interp(codes,grid**(1/gamma),grid) for gamma in (2.2,1.8,2.0)])
    processed = processed_rgb_to_linear(encoded,inverse_luts=inverse_luts,response_anchors=((.05,-.2),(.4,.15),(.9,.4)))
    examples = dict(black=torch.zeros_like(gradient),white=torch.ones_like(gradient),
                    gradient=gradient,raw=raw,processed=processed,
                    gray_corrected=torch.full_like(gradient,.18)*2**(-.5))
    output = Path(__file__).resolve().parents[2]/"app/src/test/resources/global-metering-mapping-v1.tsv"
    output.parent.mkdir(parents=True,exist_ok=True)
    lines = ["# PyTorch FeatureExtractor oracle; case\tindex\tvalue; float32 NCHW 3x64x96"]
    with torch.inference_mode():
        extractor=FeatureExtractor()
        for name,rgb in examples.items():
            for i,value in enumerate(extractor.from_linear(rgb)[0].tolist()):
                lines.append(f"{name}\t{i}\t{value:.10g}")
    output.write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"Exported {len(examples)} x 776 independent feature expectations to {output}")


if __name__ == "__main__": export()
