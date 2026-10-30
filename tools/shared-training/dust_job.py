# SPDX-License-Identifier: Apache-2.0
"""Resume one authoritative dust batch, without changing its frozen recipe or model."""
import os
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
import argparse
from collections import Counter
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True

import torch

from common import digest, gpu_slot, read_json, stamp, update, write_json
from ledger import Ledger
from validate import check_snapshot, finite


def run_batch(arguments):
    root, run, pool, snapshot = (p.resolve() for p in (arguments.root, arguments.run, arguments.pool, arguments.snapshot))
    check_snapshot(snapshot)
    sys.path.insert(0, str(snapshot / "src"))
    from film_dust.continuous_pool import Registry
    from film_dust.manifest import load_manifest
    from film_dust.run_control import RunControl, resume_run
    from film_dust.stream_train import ordered_sources
    parent = torch.load(run / "last.pt", map_location="cpu", weights_only=True)
    if not finite(parent) or parent["repair"]["architecture"] != "masked-residual-separable-unet-v1":
        raise ValueError("Wrong/non-finite paused dust architecture; no implicit V2 conversion")
    rows = load_manifest(parent["config"]["manifest"])
    order = ordered_sources(rows, parent["config"].get("seed", 2026), parent["config"]["stream"]["source_passes"])
    expected_order = __import__("hashlib").sha256("\n".join(r["id"] for r in order).encode()).hexdigest()
    if expected_order != parent["stream_order_sha256"] or digest(parent["config"]["manifest"]) != parent["manifest_sha256"]:
        raise ValueError("Frozen dust manifest/order mismatch")
    ledger = Ledger(arguments.ledger)
    batch_id = run.name
    ledger.freeze_manifest("dust", batch_id, parent["config"]["manifest"], dict(labels="frozen V8 defect synthesis and native repair patches", architecture=parent["repair"]["architecture"]))
    ledger.batch("dust", batch_id, parent["manifest_sha256"], rows, digest(run / "last.pt"),
                 dict(cursor=parent["stream_cursor"]), required=parent["config"]["stream"]["source_passes"])
    by_id = {r["id"]: r["sha256"] for r in rows}
    original_update = RunControl.update

    def observe(self, state, **values):
        original_update(self, state, **values)
        if values.get("phase") == "checkpoint-saved":
            cursor = values["source_cursor"]
            consumed = Counter(row["original_id"] for row in order[:cursor])
            ledger.commit("dust", batch_id, run / "last.pt", dict(cursor=cursor, detector_steps=values["steps"], repair_steps=values.get("repair_steps")),
                          {by_id[identity]: count for identity, count in consumed.items()})
            update(root, "dust", state="shared-continuation-running", checkpoint=str(run / "last.pt"), cursor=cursor,
                   total_visits=len(order), detector_steps=values["steps"], repair_steps=values.get("repair_steps"),
                   unique_originals=values.get("original_image_stats", {}).get("total_trained_originals"),
                   architecture=parent["repair"]["architecture"], batch=batch_id)
            pointer = read_json(pool / "active-training.json")
            pointer.update(state="shared-running", pid=os.getpid(), run=str(run), shared_control_root=str(root), updated_at=__import__("time").time())
            write_json(pool / "active-training.json", pointer)
            if (root / "PAUSE.json").exists() or (pool / "pause-all.json").exists():
                write_json(run / "pause-request.json", dict(reason="shared scheduler cooperative pause"))
    RunControl.update = observe
    update(root, "dust", state="shared-continuation-loading", checkpoint=str(run / "last.pt"), cursor=parent["stream_cursor"],
           detector_steps=parent["stream_steps"], repair_steps=parent["repair"]["steps"],
           architecture=parent["repair"]["architecture"], batch=batch_id)
    resume_run(run)
    child = torch.load(run / "last.pt", map_location="cpu", weights_only=True)
    if not finite(child):
        raise FloatingPointError("Non-finite dust checkpoint")
    complete = child["stream_cursor"] == len(order) and not child.get("stream_validation_pending", False)
    registry = Registry(pool)
    registry.reconcile(run, child, digest(run / "last.pt"), complete)
    registry.close()
    ledger.close()
    report = dict(consumer="dust", run=str(run), checkpoint=str(run / "last.pt"), checkpoint_sha256=digest(run / "last.pt"),
                  cursor=child["stream_cursor"], total_visits=len(order), detector_steps=child["stream_steps"], repair_steps=child["repair"]["steps"],
                  unique_originals=child["stream_trained_sources"], complete=complete, finished_at=stamp(),
                  architecture=child["repair"]["architecture"], source_snapshot=str(snapshot), no_original_gc=True)
    write_json(root / "dust-last-job.json", report)
    update(root, "dust", state="batch-complete" if complete else "paused", **{k:v for k,v in report.items() if k != "consumer"})
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "run", "pool", "snapshot", "ledger"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        with gpu_slot(args.root, "dust"):
            result = run_batch(args)
        print(json.dumps(dict(event="dust-stage-complete", **result)), flush=True)
    except BaseException as error:
        update(args.root, "dust", state="failed", error=f"{type(error).__name__}: {error}")
        raise
