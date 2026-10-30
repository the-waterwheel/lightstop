# SPDX-License-Identifier: Apache-2.0
"""Export references to a paused image pool without modifying its data or code.

This is an inventory, not a cache-retention mechanism or an EV-labelled dataset.
Only Python's standard library is used; no film_dust modules are imported.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def export(pool, output, verify_hashes=False):
    pool, output = pool.resolve(), output.resolve()
    if output == pool or pool in output.parents:
        raise ValueError("Write the inventory outside the shared pool")
    database = pool / "continuous-registry.sqlite3"
    pause = pool / "pause-all.json"
    wal = Path(str(database) + "-wal")
    if not pause.is_file() or wal.exists():
        raise ValueError("Requires a paused pool without a WAL; never copy a live database")
    # A private metadata snapshot avoids SQLite trying to create SHM next to
    # the externally owned database. Do not use immutable=1 on the live file.
    raw = database.read_bytes()
    source_hash = hashlib.sha256(raw).hexdigest()
    if digest_file(database) != source_hash or wal.exists():
        raise ValueError("Registry changed while taking its metadata snapshot")
    with tempfile.TemporaryDirectory(prefix="metering-pool-metadata-") as temporary:
        snapshot = Path(temporary) / "registry.sqlite3"
        snapshot.write_bytes(raw)
        connection = sqlite3.connect(snapshot.as_uri() + "?immutable=1", uri=True)
        try:
            records = connection.execute(
                "SELECT id,source,state,row_json,pinned FROM originals "
                "WHERE state IN ('pinned','ready','leased','consumed') ORDER BY id"
            ).fetchall()
            states = dict(connection.execute("SELECT state,COUNT(*) FROM originals GROUP BY state"))
        finally:
            connection.close()
    rows, seen = [], set()
    for identity, source, state, encoded, pinned in records:
        data = json.loads(encoded)
        image = Path(data["image"])
        if image.is_symlink() or image.resolve().parent not in (
            pool / "images", pool / "continuous-images"
        ) or not image.is_file():
            raise ValueError(f"Missing or unexpected original: {identity}")
        sha256 = data["sha256"]
        if not re.fullmatch(r"[0-9a-f]{64}", sha256) or sha256 in seen:
            raise ValueError(f"Invalid/duplicate SHA256: {identity}")
        if data.get("split") not in ("train", "val", "test"):
            raise ValueError(f"Missing split: {identity}")
        if data.get("source_license") not in ("CC0-1.0", "CC-BY-4.0"):
            raise ValueError(f"Unreviewed source licence: {identity}")
        if not data.get("source_url") or not data.get("license_url"):
            raise ValueError(f"Missing provenance: {identity}")
        if verify_hashes and digest_file(image) != sha256:
            raise ValueError(f"Original hash mismatch: {identity}")
        seen.add(sha256)
        rows.append(dict(
            id=identity, image=str(image.resolve()), sha256=sha256,
            split=data["split"], source_license=data["source_license"],
            source_state=state, source=source, original_pinned=bool(pinned),
            source_record=data, metering_label=None,
            permitted_role="scene_prior_and_synthetic_augmentation",
        ))
    if not pause.is_file() or wal.exists() or digest_file(database) != source_hash:
        raise ValueError("Pool changed during inventory; retry while paused")
    summary = dict(
        generated_at=datetime.now(timezone.utc).isoformat(), pool=str(pool),
        registry_sha256=source_hash, originals=len(rows),
        splits=dict(Counter(r["split"] for r in rows)),
        licenses=dict(Counter(r["source_license"] for r in rows)),
        registry_states=states, hashes_verified=verify_hashes,
        original_bytes=sum(Path(r["image"]).stat().st_size for r in rows),
        copied_image_bytes=0, retention_installed=False,
        calibrated_ev_labels=0,
        note="References only. Keep source pause until consumer-aware retention is installed.",
    )
    output.mkdir(parents=True, exist_ok=True)
    (output / "manifest.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
    )
    (output / "inventory.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pool", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--verify-hashes", action="store_true")
    arguments = parser.parse_args()
    print(json.dumps(export(arguments.pool, arguments.output, arguments.verify_hashes),
                     ensure_ascii=False, indent=2))
