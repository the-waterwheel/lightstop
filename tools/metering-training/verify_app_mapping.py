# SPDX-License-Identifier: Apache-2.0
"""Evaluate actual Kotlin mapping vectors against their PyTorch oracle and parent model."""
import argparse
import json
from pathlib import Path

import numpy as np
import torch
from metering_model import ExposureMLP
from metering_data import atomic_json, sha256


def vectors(path):
    cases={}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"): continue
        name,index,value=line.split("\t")
        cases.setdefault(name,np.full(776,np.nan,dtype=np.float32))[int(index)]=float(value)
    if not cases or any(not np.isfinite(v).all() for v in cases.values()): raise ValueError("Incomplete parity vectors")
    return cases


def verify(actual_path,expected_path,checkpoint,output):
    torch.set_num_threads(2)
    actual,expected=vectors(actual_path),vectors(expected_path)
    if set(actual)!=set(expected): raise ValueError("Different parity cases")
    state=torch.load(checkpoint,map_location="cpu",weights_only=True)
    model=ExposureMLP();model.load_state_dict(state["model"]);model.eval()
    rows={}
    with torch.inference_mode():
        for name in actual:
            reference=model(torch.from_numpy(expected[name][None]))[0]
            mapped=model(torch.from_numpy(actual[name][None]))[0]
            rows[name]=dict(feature_max_abs=float(np.abs(actual[name]-expected[name]).max()),
                            compensation_difference_ev=float((mapped[0]-reference[0]).abs()),
                            scale_difference_ev=float((mapped[1]-reference[1]).abs()))
    passed=all(r["feature_max_abs"]<=1e-4 and r["compensation_difference_ev"]<=.001 for r in rows.values())
    result=dict(passed=passed,cases=rows,parent_epoch=state["epoch"],checkpoint_sha256=sha256(checkpoint),
                actual_features_sha256=sha256(actual_path),oracle_sha256=sha256(expected_path),
                interpretation="Kotlin/JVM vs PyTorch preprocessing; model outputs use the same preserved parent weights")
    atomic_json(output,result)
    if not passed: raise AssertionError(json.dumps(result,indent=2))
    return result


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("actual","expected","checkpoint","output"):p.add_argument("--"+name,type=Path,required=True)
    a=p.parse_args();print(json.dumps(verify(a.actual,a.expected,a.checkpoint,a.output),ensure_ascii=False,indent=2))
