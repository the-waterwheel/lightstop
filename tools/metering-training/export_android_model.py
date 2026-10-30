# SPDX-License-Identifier: Apache-2.0
"""Export an explicitly selected checkpoint to the small Android FP32 MLP format."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct

import numpy as np
import torch
from metering_model import ExposureMLP
from metering_features import FEATURE_VERSION
from verify_app_mapping import vectors
from train import export_model


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def export(checkpoint, workspace, output):
    torch.set_num_threads(2)
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    if state.get("epoch") != 219 or state.get("feature_version") != FEATURE_VERSION:
        raise ValueError("This application test explicitly requires epoch 219 and the existing feature contract")
    model = ExposureMLP(); model.load_state_dict(state["model"]); model.eval()
    arrays = [model.feature_mean, model.feature_std, model.uncertainty_factor.reshape(1)]
    arrays += [state["model"]["layers."+str(layer)+"."+key] for layer in (0, 2, 4) for key in ("weight", "bias")]
    if any(not torch.isfinite(v).all() for v in arrays) or (model.feature_std <= 0).any():
        raise ValueError("Invalid weights/normalization")
    assets = workspace/"app/src/main/assets"; output.mkdir(parents=True, exist_ok=True)
    path = assets/"global_metering_epoch219.bin"
    path.write_bytes(b"LTMTRMLP"+struct.pack("<6i", 1, 219, 776, 64, 32, 2)+b"".join(
        v.detach().contiguous().numpy().astype("<f4").tobytes() for v in arrays))
    examples = vectors(workspace/"app/src/test/resources/global-metering-mapping-v1.tsv")
    generator = torch.Generator().manual_seed(219)
    for index in range(12):
        examples[f"normalization-{index}"] = (model.feature_mean+model.feature_std*
            torch.randn(776, generator=generator)*(index%3+.25)).numpy()
    comparison = torch.from_numpy(np.stack(list(examples.values())))
    with torch.inference_mode(): predictions = model(comparison).numpy()
    fixture = workspace/"app/src/test/resources/global-metering-epoch219.tsv"
    fixture.write_text("# case\tcompensation_ev\tscale_ev\t776 float32 features\n"+"".join(
        name+"\t"+"\t".join(format(float(v), ".9g") for v in np.concatenate([prediction, value]))+"\n"
        for (name, value), prediction in zip(examples.items(), predictions)), encoding="utf-8")
    export_check = export_model(model, output, comparison, dict(onnx_opset=17, batch_size=128))
    manifest = json.loads((checkpoint.parent/"data/manifest.json").read_text(encoding="utf-8"))
    provenance = [dict(id=row["id"],sha256=row["sha256"],split=row["split"],group=row["group"],
        phash=row["phash"],licence=row["licence"],provenance=row["provenance"]) for row in manifest["rows"]]
    (output/"provenance.json").write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    metadata = dict(schema="global-metering-android-mlp-v1", epoch=219,
        checkpoint_sha256=digest(checkpoint), weights_sha256=digest(path), weights_bytes=path.stat().st_size,
        architecture=[776,64,32,2], parameters=51874, feature_version=FEATURE_VERSION,
        mapping_version="global-input-mapping-v1",domain_version=state["domain_version"],
        code_license="Apache-2.0",source_manifest_sha256=digest(checkpoint.parent/"data/manifest.json"),
        source_licences=dict(Counter(row["licence"] for row in manifest["rows"])),
        source_counts=manifest["counts"],validation=state["validation"],
        status="device-test; reference-exposure supervision; synthetic RAW validation",
        inference="FP32 local MLP, normalization and output transforms included; no calibration refit",
        output_semantics="positive compensation increases exposure; target EV100 = frame camera EV100 - compensation",
        export_check=export_check)
    (assets/"global_metering_epoch219.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+"\n",encoding="utf-8")
    (output/"android-model.json").write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(dict(epoch=219,checkpoint_sha256=digest(checkpoint),weights_bytes=path.stat().st_size,
        weights_sha256=digest(path),export_check=export_check),indent=2))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint",type=Path,required=True)
    parser.add_argument("--workspace",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args(); export(args.checkpoint,args.workspace,args.output)
