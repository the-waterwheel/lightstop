# SPDX-License-Identifier: Apache-2.0
"""Audit the paused shared pool and prepare model-independent group/quality evidence."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

from common import digest, read_json, stamp, update, write_json

PHASH_VERSION = "legacy-compatible-pil32-cv2-dct64-median-excluding-dc-v1"


def phash(image):
    gray = np.asarray(image.convert("L").resize((32, 32)), dtype=np.float32)
    low = cv2.dct(gray)[:8, :8].flatten()
    bits = low > np.median(low[1:])
    return "".join(f"{sum(int(bit) << position for position, bit in enumerate(bits[index:index+4])):x}"
                   for index in range(0, 64, 4))


class Groups:
    def __init__(self, identities):
        self.parent = {identity: identity for identity in identities}

    def find(self, identity):
        if self.parent[identity] != identity:
            self.parent[identity] = self.find(self.parent[identity])
        return self.parent[identity]

    def join(self, left, right):
        a, b = self.find(left), self.find(right)
        self.parent[max(a, b)] = min(a, b)


def snapshot_registry(pool, output):
    database = pool / "continuous-registry.sqlite3"
    if not (pool / "pause-all.json").is_file() or Path(str(database) + "-wal").exists():
        raise RuntimeError("Requires an idle paused registry without a live WAL")
    raw = database.read_bytes()
    checksum = hashlib.sha256(raw).hexdigest()
    if digest(database) != checksum:
        raise RuntimeError("Registry changed during snapshot")
    target = output / "legacy-registry-snapshot.sqlite3"
    if target.exists() and digest(target) != checksum:
        raise RuntimeError("Preserve the previous audit; choose a new output directory")
    if not target.exists():
        target.write_bytes(raw)
    connection = sqlite3.connect(target.as_uri() + "?immutable=1", uri=True)
    connection.row_factory = sqlite3.Row
    originals = [dict(row) for row in connection.execute("SELECT * FROM originals ORDER BY id")]
    settings = {row["key"]: json.loads(row["value"]) for row in connection.execute("SELECT * FROM settings")}
    connection.close()
    return checksum, originals, settings


def manifest_rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8-sig").splitlines() if line.strip()]


def exif_metadata(image):
    root = image.getexif()
    nested = root.get_ifd(34665) if 34665 in root else {}
    return {name: str(nested.get(tag, root.get(tag, ""))) for name, tag in
            (("make", 271), ("model", 272), ("datetime", 306), ("datetime_original", 36867),
             ("body_serial", 42033), ("image_unique_id", 42016))}


def examine(row, ordinal, output):
    path = Path(row["image"])
    if digest(path) != row["sha256"]:
        raise RuntimeError(f"Original checksum mismatch: {row['id']}")
    reasons = []
    record = row.get("source_record", row)
    licence = row.get("source_license")
    licence_ok = licence in ("CC0-1.0", "CC-BY-4.0") and bool(record.get("source_url") and record.get("license_url"))
    if licence == "CC-BY-4.0" and not record.get("author"):
        licence_ok = False
    if not licence_ok:
        reasons.append("licence-provenance-incomplete")
    with Image.open(path) as source:
        file_format, mode = source.format, source.mode
        exif_data = exif_metadata(source)
        image = ImageOps.exif_transpose(source)
        width, height = image.size
        fingerprint = phash(image)
        thumbnail = image.convert("RGB")
        thumbnail.thumbnail((220, 150))
        thumb_path = output / "quality" / f"{ordinal:04d}-thumb.jpg"
        thumbnail.save(thumb_path, quality=90)
        # First 32 and every potential metadata mismatch get a native-resolution crop.
        pixels = width * height
        title = record.get("title", "")
        suspicious = re.search(r"painting|engraving|lithograph|map of|drawing|watermark|reproduction|by gustine", title, re.I)
        if not 8_000_000 <= pixels <= 64_000_000:
            reasons.append("outside-8-to-64-megapixels")
        if mode not in ("RGB", "L") or file_format not in ("PNG", "JPEG"):
            reasons.append("input-format-needs-review")
        if suspicious:
            reasons.append("content-metadata-needs-review")
        if ordinal <= 32 or reasons:
            x, y = max(0, width // 2 - 128), max(0, height // 2 - 128)
            image.crop((x, y, min(x + 256, width), min(y + 256, height))).convert("RGB").save(
                output / "quality" / f"{ordinal:04d}-native-center.png")
    return dict(ordinal=ordinal, id=row["id"], sha256=row["sha256"], image=str(path.resolve()),
                split=row["split"], source_record=record, licence=licence, licence_metadata_pass=licence_ok,
                width=width, height=height, format=file_format, mode=mode, bytes=path.stat().st_size,
                phash=fingerprint, phash_version=PHASH_VERSION, exif=exif_data,
                quality_state="pending-visual-review", quality_flags=reasons,
                thumbnail=str(thumb_path.resolve()))


def sheets(records, output):
    font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 13)
    for start in range(0, len(records), 30):
        selected = records[start:start+30]
        sheet = Image.new("RGB", (1200, 1000), "#f0f0f0")
        draw = ImageDraw.Draw(sheet)
        for offset, record in enumerate(selected):
            left, top = (offset % 5) * 240, (offset // 5) * 166
            with Image.open(record["thumbnail"]) as image:
                sheet.paste(image, (left, top))
            text = f"{record['ordinal']:03d} {record['split']} {record['id'][:23]}"
            draw.text((left, top + 150), text, font=font, fill="black")
        sheet.save(output / "quality" / f"sheet-{start//30+1:02d}.jpg", quality=92)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pool", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--dust-run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    output = arguments.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    (output / "quality").mkdir(exist_ok=True)
    update(output, "audit", state="hash-phash-decode", stage="auditing", processed=0)
    database_hash, legacy, settings = snapshot_registry(arguments.pool.resolve(), output)
    rows = manifest_rows(arguments.manifest)
    dust_config = read_json(arguments.dust_run / "config.json")
    dust_rows = manifest_rows(dust_config["manifest"])
    protected = {row["id"]: row["split"] for row in rows + dust_rows if row["split"] in ("val", "test")}
    conflicts = [dict(id=row["id"], held_out=protected[row["id"]]) for row in rows + dust_rows
                 if row["split"] == "train" and row["id"] in protected]
    records = []
    legacy_by_id = {row["id"]: row for row in legacy}
    for ordinal, row in enumerate(rows, 1):
        result = examine(row, ordinal, output)
        old = legacy_by_id.get(row["id"], {})
        result["legacy_phash_matches"] = old.get("phash") == result["phash"]
        result["legacy_state"] = old.get("state")
        result["legacy_phash"] = old.get("phash")
        records.append(result)
        if ordinal % 16 == 0 or ordinal == len(rows):
            update(output, "audit", state="hash-phash-decode", processed=ordinal, total=len(rows))
            print(json.dumps(dict(event="audit", processed=ordinal, total=len(rows))), flush=True)
    group = Groups([row["id"] for row in records])
    edges = []
    for position, left in enumerate(records):
        for right in records[position+1:]:
            distance = (int(left["phash"], 16) ^ int(right["phash"], 16)).bit_count()
            if left["sha256"] == right["sha256"] or distance <= 6:
                group.join(left["id"], right["id"])
                edges.append(dict(left=left["id"], right=right["id"], reason="sha256" if left["sha256"] == right["sha256"] else "phash<=6", distance=distance))
            a, b = left["exif"], right["exif"]
            # A body serial + closely spaced capture time gives stronger burst evidence
            # than a shared camera model or generic landscape title.
            if a["body_serial"] and a["body_serial"] == b["body_serial"] and a["datetime_original"] and b["datetime_original"]:
                try:
                    seconds = abs((datetime.strptime(a["datetime_original"], "%Y:%m:%d %H:%M:%S") -
                                   datetime.strptime(b["datetime_original"], "%Y:%m:%d %H:%M:%S")).total_seconds())
                except ValueError:
                    seconds = 9999
                if seconds <= 3:
                    group.join(left["id"], right["id"])
                    edges.append(dict(left=left["id"], right=right["id"], reason="same-body-capture-within-3s"))
    members = {}
    for record in records:
        members.setdefault(group.find(record["id"]), []).append(record)
    for identity, items in members.items():
        splits = {item["split"] for item in items}
        locked = splits & {"val", "test"}
        if locked and ("train" in splits or len(locked) > 1):
            conflicts.append(dict(group=identity, members=[item["id"] for item in items], splits=sorted(splits)))
        for item in items:
            item["group_id"] = identity
            item["global_split"] = next(iter(locked)) if len(locked) == 1 else item["split"]
    sheets(records, output)
    by_id = {row["id"]: row for row in records}
    dust_missing = [row["id"] for row in dust_rows if row["id"] not in by_id]
    history = dict(total_registry_rows=len(legacy), evicted=sum(row["state"] == "evicted" for row in legacy),
                   retained_phashes=sum(bool(row.get("phash")) for row in legacy),
                   caveat="Missing historical pixels are not reconstructed or claimed visually re-audited")
    report = dict(created_at=stamp(), pool=str(arguments.pool.resolve()), registry_sha256=database_hash,
                  source_manifest=str(arguments.manifest.resolve()), source_manifest_sha256=digest(arguments.manifest),
                  dust_run=str(arguments.dust_run.resolve()), dust_manifest=dust_config["manifest"],
                  dust_manifest_sha256=digest(dust_config["manifest"]), campaign=settings.get("campaign"),
                  originals=len(records), splits=dict(Counter(row["split"] for row in records)),
                  licences=dict(Counter(row["licence"] for row in records)),
                  licence_metadata_failures=[row["id"] for row in records if not row["licence_metadata_pass"]],
                  phash_mismatches=[row["id"] for row in records if not row["legacy_phash_matches"]],
                  exact_unique_sha256=len({row["sha256"] for row in records}), group_count=len(members),
                  candidate_edges=edges, split_conflicts=conflicts, dust_manifest_missing_ids=dust_missing,
                  historical=history, records=records,
                  state="awaiting-visual-review-and-checkpoint-validation", long_training_authorized=False,
                  same_scene_review="Contact sheets and original-size crops require visual review; phash is not a semantic scene guarantee")
    write_json(output / "audit.json", report)
    update(output, "audit", state=report["state"], originals=len(records), processed=len(records),
           licence_metadata_failures=len(report["licence_metadata_failures"]), phash_mismatches=len(report["phash_mismatches"]),
           candidate_edges=len(edges), split_conflicts=len(conflicts))


if __name__ == "__main__":
    main()
