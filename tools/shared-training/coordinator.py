# SPDX-License-Identifier: Apache-2.0
"""Durable serial GPU queue: existing dust continuation and independent metering stages."""
import os
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
os.environ["PYTHONUTF8"] = "1"
os.environ["PYTHONIOENCODING"] = "utf-8"
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
sys.dont_write_bytecode = True
import time

import torch

from common import digest, read_json, stamp, update, write_json
from ledger import Ledger
from prepare_shared import freeze_lines
from publish import gates
from validate import check_snapshot, finite


def launch_command(script, **arguments):
    command = [sys.executable, "-B", "-u", str(Path(__file__).parent / script)]
    for key, value in arguments.items():
        command.extend(["--" + key.replace("_", "-"), str(value)])
    return command


def refresh(root, ledger_path):
    ledger = Ledger(ledger_path)
    counts = dict(ledger.db.execute("SELECT quality,COUNT(*) FROM shared_assets GROUP BY quality"))
    refs = dict(ledger.db.execute("SELECT state,COUNT(*) FROM shared_refs GROUP BY state"))
    ledger.close()
    update(root, "audit", state="shared-training-gates-passed", accepted=counts.get("accepted", 0),
           quarantined=counts.get("quarantined", 0), pending_new_review=counts.get("pending", 0),
           consumer_references=refs, automatic_gc_enabled=False,
           canonical_registry=str(ledger_path))


def run_job(command, root, consumer, ledger_path):
    stdout_path = root / f"{consumer}-{int(time.time())}.stdout.log"
    stderr_path = root / f"{consumer}-{int(time.time())}.stderr.log"
    with stdout_path.open("w", encoding="utf-8") as out, stderr_path.open("w", encoding="utf-8") as err:
        child = subprocess.Popen(command, stdout=out, stderr=err, env=dict(os.environ))
        write_json(root / "active-job.json", dict(consumer=consumer, pid=child.pid, command=command, stdout=str(stdout_path), stderr=str(stderr_path), started_at=stamp()))
        while child.poll() is None:
            refresh(root, ledger_path)
            time.sleep(2)
        if child.returncode:
            raise RuntimeError(f"{consumer} worker failed with {child.returncode}; inspect {stderr_path}")
    write_json(root / "active-job.json", dict(consumer=None, state="idle", finished_at=stamp()))


def catalog(root, database):
    ledger = Ledger(database)
    records = [dict(row) for row in ledger.db.execute("SELECT * FROM shared_assets WHERE quality='accepted' ORDER BY original_id")]
    ledger.close()
    rows = []
    for record in records:
        source = json.loads(record["source_json"])
        source.update(id=record["original_id"], image=record["image"], sha256=record["sha256"], group=record["group_id"], split=record["global_split"])
        rows.append(dict(id=record["original_id"], image=record["image"], sha256=record["sha256"], group=record["group_id"],
                         split=record["global_split"], source_license=record["licence"], source_record=source,
                         metering_label=dict(kind="suitable-reference-exposure", compensation_ev=0.0,
                                             augmentation="linear exposure delta -> negative delta target", version=1)))
    signature = hashlib.sha256(json.dumps(rows, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    path = root / "manifests" / f"metering-catalog-{signature[:16]}.jsonl"
    freeze_lines(path, rows)
    return path, signature, rows


def reserve_metering_stage(state, root, manifest, signature):
    """Persist this intent before launch, so interruption resumes the same stage."""
    if state.get("pending_metering"):
        return state["pending_metering"]
    state["metering_stage"] += 1
    pending = dict(stage=state["metering_stage"], parent=state["metering_parent"],
                   manifest=str(manifest), manifest_sha256=digest(manifest), signature=signature,
                   output=str(root / "metering" / f"metering-shared-stage-{state['metering_stage']:04d}"))
    state["pending_metering"] = pending
    return pending


def finish_metering_stage(state, result):
    if result["stop_reason"] == "user-pause":
        return False
    pending = state["pending_metering"]
    state.update(metering_parent=result["checkpoint"], last_metering_catalog=pending["signature"])
    del state["pending_metering"]
    return True


def pause_children(root, pool):
    write_json(pool / "pause-all.json", dict(reason="Shared training pause", requested_at=time.time()))
    write_json(pool / "continuous-stop.json", dict(reason="shared scheduler pause", at=time.time()))
    update(root, "collection", state="pause-requested")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "pool", "dust_root", "snapshot", "metering_parent", "old_cache"):
        parser.add_argument("--" + name.replace("_", "-"), type=Path, required=True)
    parser.add_argument("--no-collect", action="store_true")
    args = parser.parse_args()
    root, pool, dust_root, snapshot = (value.resolve() for value in (args.root, args.pool, args.dust_root, args.snapshot))
    preflight, dust_validation, metering_validation = gates(root)
    if not read_json(root / "authorization.json").get("long_training_after_gates"):
        raise RuntimeError("No explicit user authorization for long training")
    if not read_json(root / "publication.json").get("legacy_rows_preserved"):
        raise RuntimeError("Shared registry migration not verified")
    check_snapshot(snapshot)
    check_snapshot(Path(__file__).resolve().parents[2])
    sys.path.insert(0, str(snapshot / "src"))
    from film_dust.continuous_pool import Registry, exclusive_lock
    from film_dust.local_continuation import continue_on_local_pool
    from film_dust.manifest import load_manifest
    database = pool / "continuous-registry.sqlite3"
    guards = Ledger(database)
    guards.initialize()  # Additive guard upgrades; no legacy table/row rewrite.
    guards.close()
    state_path = root / "coordinator-state.json"
    collector = None
    with exclusive_lock(pool / "continuous-controller.lock"):
        if state_path.exists():
            state = read_json(state_path)
        else:
            current = read_json(pool / "active-training.json")["run"]
            if digest(Path(current) / "last.pt") != dust_validation["parent"]["sha256"] or digest(args.metering_parent) != metering_validation["checkpoint_sha256"]:
                raise RuntimeError("Audited parents changed before first real continuation")
            backups = root / "authoritative-parent-backups"
            backups.mkdir(exist_ok=True)
            shutil.copyfile(Path(current) / "last.pt", backups / "dust-last-before-resume.pt")
            shutil.copyfile(args.metering_parent, backups / "metering-last-before-resume.pt")
            state = dict(dust_run=current, metering_parent=str(args.metering_parent.resolve()), metering_stage=0,
                         last_metering_catalog=None, state="starting", created_at=stamp())
        for name in ("pause-all.json", "continuous-stop.json"):
            path = pool / name
            if path.exists():
                backup = root / (name + ".before-shared-start")
                if not backup.exists():
                    shutil.copyfile(path, backup)
                path.unlink()  # Named control markers only; explicit resume authorization above.
        state.update(state="running", pid=os.getpid(), updated_at=stamp())
        write_json(state_path, state)
        dust_parent = torch.load(Path(state["dust_run"]) / "last.pt", map_location="cpu", weights_only=True)
        meter_parent = torch.load(state["metering_parent"], map_location="cpu", weights_only=True)
        write_json(root / "dust-status.json", dict(consumer="dust", state="queued-from-paused-checkpoint", updated_at=stamp(),
                   checkpoint=str(Path(state["dust_run"]) / "last.pt"), cursor=dust_parent["stream_cursor"],
                   detector_steps=dust_parent["stream_steps"], repair_steps=dust_parent["repair"]["steps"],
                   unique_originals=dust_parent["stream_trained_sources"], architecture=dust_parent["repair"]["architecture"],
                   detector_optimizer_slots=len(dust_parent["optimizer"]["state"]),
                   repair_optimizer_slots=len(dust_parent["repair"]["optimizer"]["state"]), reset=False))
        write_json(root / "metering-status.json", dict(consumer="metering",
                   state="queued-independent-continuation" if state.get("pending_metering") or not state.get("last_metering_catalog") else
                         "global-epoch-budget-complete" if state.get("metering_global_budget_complete") else "waiting-new-reviewed-data",
                   updated_at=stamp(), checkpoint=state["metering_parent"], epoch=meter_parent["epoch"],
                   optimizer_slots=len(meter_parent["optimizer"]["state"])))
        write_json(pool / "shared-training.json", dict(schema_version=1, canonical_registry=str(database), control_root=str(root),
                                                      state="running", controller_pid=os.getpid(), automatic_gc_enabled=False,
                                                      consumers=["dust", "metering"], user_authorization="current-chat-after-audit-gates"))
        registry = Registry(pool)
        campaign = registry.get("campaign")
        if not args.no_collect:
            parent_config = dust_root / "training" / "runs" / "joint-liu4k-expanded-20261022" / "config.json"
            stdout = (root / "collector.stdout.log").open("a", encoding="utf-8")
            stderr = (root / "collector.stderr.log").open("a", encoding="utf-8")
            collector = subprocess.Popen(launch_command("collector.py", root=root, pool=pool, snapshot=snapshot, config=parent_config),
                                         stdout=stdout, stderr=stderr, env=dict(os.environ))
            stdout.close()
            stderr.close()
            write_json(root / "collector-process.json", dict(pid=collector.pid, state="collecting-to-pending-review", max_pending=32))
        waiting_since = time.monotonic()
        try:
            while not (root / "PAUSE.json").exists():
                if min(shutil.disk_usage(root).free, shutil.disk_usage(pool).free) < 4 * 1024**3:
                    write_json(root / "PAUSE.json", dict(reason="disk free below 4GiB; preserve all data/checkpoints", at=stamp()))
                    break
                manifest, signature, rows = catalog(root, database)
                if state.get("pending_metering") or state["last_metering_catalog"] != signature:
                    meter_parent = torch.load(state["metering_parent"], map_location="cpu", weights_only=True)
                    budget_complete = meter_parent["epoch"] >= meter_parent.get("config", read_json(Path(__file__).parent.parent / "metering-training" / "config.json"))["max_epochs"]
                    if budget_complete and not state.get("pending_metering"):
                        update(root, "metering", state="global-epoch-budget-complete", epoch=meter_parent["epoch"],
                               checkpoint=state["metering_parent"], pending_catalog_not_consumed=True)
                        state.update(last_metering_catalog=signature, metering_global_budget_complete=True)
                        write_json(state_path, state)
                    else:
                        pending = reserve_metering_stage(state, root, manifest, signature)
                        if digest(pending["manifest"]) != pending["manifest_sha256"]:
                            raise ValueError("Pending metering stage manifest changed")
                        write_json(state_path, state)
                        output = Path(pending["output"])
                        output.parent.mkdir(exist_ok=True)
                        run_job(launch_command("metering_job.py", root=root, ledger=database, parent=pending["parent"],
                                               manifest=pending["manifest"], output=output, old_cache=args.old_cache), root, "metering", database)
                        result = read_json(output / "latest-result.json")
                        complete = finish_metering_stage(state, result)
                        write_json(state_path, state)
                        if not complete:
                            break
                current = Path(state["dust_run"])
                checkpoint = torch.load(current / "last.pt", map_location="cpu", weights_only=True)
                if not finite(checkpoint):
                    raise FloatingPointError("Non-finite authoritative dust checkpoint")
                total = len([row for row in load_manifest(checkpoint["config"]["manifest"]) if row["split"] == "train"]) * checkpoint["config"]["stream"]["source_passes"]
                if checkpoint["stream_cursor"] < total or checkpoint.get("stream_validation_pending", False):
                    run_job(launch_command("dust_job.py", root=root, run=current, pool=pool, snapshot=snapshot, ledger=database), root, "dust", database)
                    result = read_json(root / "dust-last-job.json")
                    if not result["complete"]:
                        break
                    waiting_since = time.monotonic()
                    checkpoint = torch.load(current / "last.pt", map_location="cpu", weights_only=True)
                    campaign.update(parent=str(current), active=None, state="shared-batch-complete")
                    registry.set("campaign", campaign)
                    write_json(current / "continuous-commit.json", dict(checkpoint_sha256=digest(current / "last.pt"), cursor=checkpoint["stream_cursor"],
                                                                      detector_steps=checkpoint["stream_steps"], repair_steps=checkpoint["repair"]["steps"],
                                                                      unique_originals=checkpoint["stream_trained_sources"], complete=True, at=time.time()))
                    continue  # Give newly reviewed metering data the next GPU opportunity.
                remaining = campaign["unique_target"] - checkpoint["stream_trained_sources"]
                if remaining <= 0:
                    update(root, "dust", state="unique-target-complete", unique_originals=checkpoint["stream_trained_sources"])
                    time.sleep(10)
                    continue
                accepted_ids = {row["id"] for row in rows if row["split"] == "train"}
                ready = [row for row in registry.ready(4096) if row["id"] in accepted_ids]
                size = min(64, remaining)
                enough = len(ready) >= size or (len(ready) >= min(8, remaining) and time.monotonic() - waiting_since >= 1800)
                if not enough:
                    update(root, "dust", state="waiting-reviewed-data", ready_originals=len(ready), batch_target=size,
                           policy="64 originals; partial >=8 after 30 minutes; pending new photos are not eligible")
                    refresh(root, database)
                    if collector and collector.poll() is not None:
                        update(root, "collection", state="collector-exited", returncode=collector.returncode, log=str(root / "collector.stderr.log"))
                    time.sleep(10)
                    continue
                group_by_id = {row["id"]: row["group"] for row in rows}
                selected = [dict(row, group=group_by_id[row["id"]]) for row in ready[:size]]
                held_out = [row for row in load_manifest(checkpoint["config"]["manifest"]) if row["split"] != "train"]
                stage = campaign["stage"] + 1
                new_run = dust_root / "training" / "runs" / f"joint-shared-20261025-stage-{stage:04d}"
                if new_run.exists():
                    raise RuntimeError("Unexpected unregistered stage directory; inspect instead of overwriting")
                new_manifest = root / "manifests" / f"dust-stage-{stage:04d}.jsonl"
                freeze_lines(new_manifest, selected + held_out)
                continue_on_local_pool(current / "last.pt", new_manifest, new_run, source_passes=16,
                                       crops_per_image=16, preserve_evaluation=True, finish_admitted_passes=True)
                registry.register_batch(new_run, selected, 16)
                campaign.update(parent=str(current), active=str(new_run), stage=stage, state="shared-queued", snapshot=str(snapshot))
                registry.set("campaign", campaign)
                write_json(new_run / "source-snapshot.json", dict(snapshot=str(snapshot), shared_runtime=str(Path(__file__).resolve()),
                                                                shared_runtime_sha256=digest(Path(__file__))))
                state["dust_run"] = str(new_run)
                write_json(state_path, state)
                pointer = read_json(pool / "active-training.json")
                pointer.update(run=str(new_run), stage=stage, state="shared-queued", shared_control_root=str(root), updated_at=time.time())
                write_json(pool / "active-training.json", pointer)
        except BaseException as error:
            state.update(state="failed", error=f"{type(error).__name__}: {error}")
            write_json(root / "PAUSE.json", dict(reason=state["error"], at=stamp()))
            raise
        finally:
            pause_children(root, pool)
            registry.close()
            if state.get("state") != "failed":
                state["state"] = "paused"
            state["updated_at"] = stamp()
            write_json(state_path, state)
            update(root, "scheduler", **state)


if __name__ == "__main__":
    main()
