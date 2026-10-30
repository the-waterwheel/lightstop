# SPDX-License-Identifier: Apache-2.0
"""RAW preparation/resume commands behind the existing unified ops.py entry."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    path = Path(path); temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    os.replace(temporary, path)


def command(root, settings, action, *, folder=None, dry_run=False, config=None):
    root = Path(root).resolve(); workspace = root.parent.parent
    pointer = root/"training-ops/metering-raw-prepared.json"
    base = workspace/"outputs/metering-training"
    folder = Path(folder).resolve() if folder else Path(read(pointer)["folder"]) if pointer.exists() else base/"raw-adaptation-v2-20261028"
    if base not in folder.parents:
        raise ValueError("Adaptation artifacts must remain inside this workspace's metering-training outputs")
    executable = workspace/"tools/metering-training/raw_adaptation.py"
    if action == "prepare-metering-raw":
        args = [settings["python"],"-B",str(executable),"prepare","--control",str(root),"--folder",str(folder)]
        if config is not None: args.extend(["--config",str(Path(config).resolve())])
        if pointer.exists():
            previous = Path(read(pointer)["folder"])/"last.pt"
            if previous.exists(): args.extend(["--parent",str(previous)])
        result = subprocess.run(args,capture_output=True,text=True,encoding="utf-8",timeout=1200,
                                env=dict(os.environ,PYTHONUTF8="1",PYTHONDONTWRITEBYTECODE="1"))
        if result.returncode: raise RuntimeError((result.stderr or result.stdout)[-3000:])
        prepared = read(folder/"prepared.json")
        write(pointer,dict(folder=str(folder),parent_epoch=prepared["parent_epoch"],counts=prepared["counts"],state="prepared-not-started"))
        return dict(state="prepared-not-started",folder=str(folder),parent_epoch=prepared["parent_epoch"],counts=prepared["counts"])
    if not (folder/"prepared.json").exists(): raise ValueError("Prepare the RAW adaptation stage first")
    prepared = read(folder/"prepared.json")
    for name, expected in prepared["files"].items():
        path = (folder/name).resolve()
        if folder not in path.parents or digest(path) != expected: raise ValueError("Frozen artifact changed: "+name)
    if not (folder/"application-sources.json").exists():
        raise ValueError("Prepare a new stage bound to the application's Global input mapping")
    for name,expected in read(folder/"application-sources.json").items():
        path=(workspace/name).resolve()
        if workspace not in path.parents or digest(path) != expected:
            raise ValueError("Application input mapping changed; prepare a new version: "+name)
    if action == "smoke-metering-raw":
        result = subprocess.run([settings["python"],"-B",str(folder/"code/raw_adaptation.py"),"smoke","--folder",str(folder)],
                                capture_output=True,text=True,encoding="utf-8",timeout=600,
                                env=dict(os.environ,PYTHONUTF8="1",PYTHONDONTWRITEBYTECODE="1"))
        if result.returncode: raise RuntimeError((result.stderr or result.stdout)[-3000:])
        smoke = read(folder/"smoke/report.json")
        write(pointer,dict(read(pointer),state=smoke["state"],smoke_verified=smoke["exact_next_update_after_reload"]))
        return smoke
    from ops import alive
    launch_path = folder/"launch.json"
    if launch_path.exists() and alive(read(launch_path).get("pid")):
        return dict(state="already-running",folder=str(folder),pid=read(launch_path)["pid"])
    for name in ("controller-process.json","coordinator-state.json","active-job.json","gpu-owner.json"):
        if alive(read(root/name).get("pid")): raise RuntimeError("Existing shared controller/worker remains live; do not duplicate training")
    if not (folder/"smoke/report.json").exists() or not read(folder/"smoke/report.json")["exact_next_update_after_reload"]:
        raise ValueError("CPU smoke/reload verification is required")
    if digest(prepared["parent_checkpoint"]) != prepared["parent_sha256"]:
        raise ValueError("Latest parent advanced; prepare a new versioned stage instead of using a stale parent")
    if prepared.get("parent_recipe") != "metering-raw-adaptation-v2" and digest(read(root/"metering-status.json")["checkpoint"]) != prepared["parent_sha256"]:
        raise ValueError("The legacy parent advanced; prepare a new versioned stage")
    if shutil.disk_usage(folder).free < 4*1024**3: raise RuntimeError("Less than 4 GiB free")
    # Validate derivative hashes here, not on every monitor refresh.
    manifest = read(folder/"data/manifest.json")
    registry = Path(read(root/"publication.json")["canonical_registry"])
    db = sqlite3.connect(registry.as_uri()+"?mode=ro",uri=True)
    admission = {sha:(quality,split,group) for sha,quality,split,group in db.execute("SELECT sha256,quality,global_split,group_id FROM shared_assets")}
    db.close()
    for row in manifest["rows"]:
        if admission.get(row["sha256"]) != ("accepted",row["split"],row["group"]):
            raise ValueError("A prepared source is no longer admitted; prepare a new data version")
        path = (folder/"data"/row["cache"]).resolve()
        if path.parent != folder/"data/cache" or digest(path) != row["cache_sha256"]:
            raise ValueError("Frozen replay cache changed")
    result = dict(state="ready-for-explicit-training",folder=str(folder),parent_epoch=prepared["parent_epoch"],
                  counts=prepared["counts"],budget_epochs=prepared["budget_epochs"],existing_pauses_preserved=True,
                  other_models_resumed=False,device="cuda",gpu_queue="existing shared gpu0.lock")
    if dry_run: return result
    # This action itself is an explicit metering-only resume; historical pause
    # marker bytes stay intact for other consumers. A NEW pause stops this job.
    args = [settings["python"],"-B","-u",str(folder/"code/raw_adaptation.py"),"run","--folder",str(folder),"--device","cuda","--acknowledge-existing-pause"]
    with (folder/"stdout.log").open("a",encoding="utf-8") as out, (folder/"stderr.log").open("a",encoding="utf-8") as err:
        process = subprocess.Popen(args,cwd=folder,stdin=subprocess.DEVNULL,stdout=out,stderr=err,
            env=dict(os.environ,PYTHONUTF8="1",PYTHONDONTWRITEBYTECODE="1"),
            creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP,close_fds=True)
    write(launch_path,dict(pid=process.pid,command=args,at=datetime.now(timezone.utc).isoformat(),scope="metering-only-explicit-resume"))
    return dict(result,state="launched",pid=process.pid)
