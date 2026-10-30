# SPDX-License-Identifier: Apache-2.0
"""Freeze admitted shared data into a private derivative replay bank, no originals copied."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3

import numpy as np
import torch

from metering_data import atomic_json, decode_srgb, roi_definitions, sample_roi, sha256


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def validate_rows(rows):
    identities, hashes, groups, phashes = {}, {}, {}, {}
    for row in rows:
        split = row["split"]
        if split not in ("train", "val"):
            raise ValueError("Adaptation bank may contain only train and validation")
        for seen, key in ((identities, row["id"]), (hashes, row["sha256"]), (groups, row["group"]), (phashes, row["phash"])):
            if key in seen and seen[key] != split:
                raise ValueError("Cross-split identity/group/phash leakage")
            seen[key] = split
        if row["quality"] != "accepted" or row["licence"] not in ("CC0-1.0", "CC-BY-4.0"):
            raise ValueError("Unapproved source")
        source = row["provenance"]
        if not source.get("source_url") or not source.get("license_url"):
            raise ValueError("Missing provenance")
        if row["licence"] == "CC-BY-4.0" and not source.get("author"):
            raise ValueError("Missing attribution")


def prepare_bank(control, output):
    control, output = Path(control).resolve(), Path(output).resolve()
    if (output/"manifest.json").exists():
        return verify_bank(output)
    output.mkdir(parents=True, exist_ok=True)
    cache = output/"cache"; cache.mkdir(exist_ok=True)
    registry = Path(read(control/"publication.json")["canonical_registry"])
    db = sqlite3.connect(registry.as_uri()+"?mode=ro", uri=True, timeout=30)
    db.row_factory = sqlite3.Row
    assets = [dict(r) for r in db.execute("SELECT a.*,g.split AS group_split FROM shared_assets a JOIN shared_groups g USING(group_id) WHERE a.quality='accepted' ORDER BY a.original_id")]
    stages = [dict(r) for r in db.execute("SELECT path,sha256 FROM shared_manifests WHERE consumer='metering'")]
    db.close()
    # Validate derived-array lineage against already-frozen metering manifests.
    derived_ids = set()
    for stage in stages:
        if sha256(stage["path"]) != stage["sha256"]:
            raise ValueError("Shared immutable manifest changed")
        derived_ids.update(json.loads(line)["sha256"] for line in Path(stage["path"]).read_text(encoding="utf-8").splitlines() if line.strip())
    workspace = control.parent.parent
    old_cache = workspace/"outputs/metering-training/cache-v1"
    old = {i["record"]["sha256"]: i for i in read(old_cache/"index.json")["items"]}
    rows, skipped = [], []
    heldout_groups = {a["group_id"] for a in assets if a["global_split"] == "test"}
    heldout_hashes = {a["phash"] for a in assets if a["global_split"] == "test"}
    for asset in assets:
        split = asset["global_split"]
        if split == "test":
            continue  # No test pixel/cache reads.
        if split != asset["group_split"] or asset["group_id"] in heldout_groups or asset["phash"] in heldout_hashes:
            raise ValueError("Shared held-out/group partition mismatch")
        checksum = asset["sha256"]; destination = cache/(checksum+".npy")
        original_checked = False
        if checksum in old:
            item = old[checksum]; source = old_cache/item["file"]
            if sha256(source) != item["cache_sha256"]:
                raise ValueError("Corrupt audited sampling cache")
            origin = "audited-cache-v1"
        elif checksum in derived_ids and (control/"metering-derived"/(checksum+".npy")).exists():
            source = control/"metering-derived"/(checksum+".npy")
            origin = "shared-stage-derived-cache"
        elif Path(asset["image"]).exists():
            if sha256(asset["image"]) != checksum:
                raise ValueError("Changed original")
            rgb, _ = decode_srgb(asset["image"])
            array = np.stack([sample_roi(rgb, roi) for roi in roi_definitions()])
            with destination.open("wb") as stream:
                np.save(stream, array, allow_pickle=False)
            source = destination; original_checked = True; origin = "admitted-original"
        else:
            skipped.append(dict(id=asset["original_id"], reason="No surviving original or lineage-backed derivative"))
            continue
        array = np.load(source, allow_pickle=False)
        if array.shape != (16, 3, 64, 96) or array.dtype != np.uint8:
            raise ValueError("Unexpected sampling cache contract")
        if source != destination:
            shutil.copyfile(source, destination)
        rows.append(dict(id=asset["original_id"], sha256=checksum, split=split, group=asset["group_id"],
                         phash=asset["phash"], quality=asset["quality"], licence=asset["licence"],
                         provenance=json.loads(asset["source_json"]), cache="cache/"+destination.name,
                         cache_sha256=sha256(destination), origin=origin, original_verified_now=original_checked))
    validate_rows(rows)
    counts = Counter(r["split"] for r in rows)
    if counts["train"] < 8 or counts["val"] != 16:
        raise ValueError("Need >=8 train originals and the complete fixed 16-image validation split")
    manifest = dict(schema="metering-raw-replay-bank-v2", registry=str(registry), counts=dict(counts),
                    roi_count=16, rows=rows, skipped=skipped, test_pixels_read=0, original_files_copied=0,
                    reference_semantics="Each ROI inherits the photographic reference exposure; inverse ISP is approximate")
    atomic_json(output/"manifest.json", manifest)
    return verify_bank(output)


def verify_bank(output):
    output = Path(output).resolve(); manifest = read(output/"manifest.json")
    validate_rows(manifest["rows"])
    for row in manifest["rows"]:
        path = (output/row["cache"]).resolve()
        if path.parent != output/"cache" or sha256(path) != row["cache_sha256"]:
            raise ValueError("Frozen derivative cache changed")
    return manifest


def load_bank(output, split, device):
    output = Path(output); manifest = read(output/"manifest.json")
    arrays, records = [], []
    for row in manifest["rows"]:
        if row["split"] != split:
            continue
        arrays.append(np.load(output/row["cache"], allow_pickle=False))
        records.extend(dict(id=row["id"], roi=r["name"], split=split) for r in roi_definitions())
    return torch.from_numpy(np.concatenate(arrays)).to(device), records
