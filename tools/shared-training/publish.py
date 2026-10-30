# SPDX-License-Identifier: Apache-2.0
"""Add shared tables to the original registry after all audit gates pass."""
import argparse
from pathlib import Path
import shutil

from common import digest, read_json, stamp, update, write_json
from ledger import Ledger, legacy_fingerprint


def gates(root):
    preflight = read_json(root / "shared-preflight.json")
    dust = read_json(root / "dust-validation.json")
    metering = read_json(root / "metering-validation.json")
    if not preflight["metadata_and_visual_passed"] or not preflight["same_image_membership"] or preflight["split_conflicts"] or not dust["passed"] or not metering["passed"]:
        raise RuntimeError("Audit/restore gates are not all satisfied")
    return preflight, dust, metering


def publish(root, apply=False):
    root = Path(root).resolve()
    preflight, dust, metering = gates(root)
    audit = read_json(root / "audit.json")
    reviewed = read_json(root / "reviewed-assets.json")
    pool = Path(audit["pool"]).resolve()
    target = pool / "continuous-registry.sqlite3"
    if target.is_symlink() or not (pool / "pause-all.json").exists() or digest(target) != preflight["source_registry_sha256"]:
        raise RuntimeError("Paused source registry changed; re-audit instead of overwriting it")
    if digest(dust["parent"]["checkpoint"]) != dust["parent"]["sha256"] or digest(metering["checkpoint"]) != metering["checkpoint_sha256"]:
        raise RuntimeError("Validated authoritative checkpoint changed")
    report = dict(at=stamp(), canonical_registry=str(target), source_registry_sha256=digest(target),
                  accepted=preflight["accepted_originals"], quarantined=preflight["quarantined_originals"],
                  dry_run=not apply, original_mutations=0, deletes=0, automatic_gc_enabled=False,
                  gate_reports=["shared-preflight.json", "dust-validation.json", "metering-validation.json"])
    if not apply:
        write_json(root / "publication-dry-run.json", report)
        return report
    backup = root / "pre-migration-registry.sqlite3"
    if backup.exists() and digest(backup) != digest(target):
        raise RuntimeError("Keep the existing migration backup; use a new audit directory")
    if not backup.exists():
        shutil.copyfile(target, backup)
    ledger = Ledger(target)
    before = legacy_fingerprint(ledger.db)
    if before != preflight["legacy_rows_fingerprint"]:
        raise RuntimeError("Legacy records changed before additive migration")
    ledger.initialize()
    ledger.register(reviewed["records"], reviewed["groups"])
    ledger.freeze_manifest("dust", "common-v1", preflight["dust_manifest"]["path"],
                           dict(task="dust detection + local repair", labels="independent GrainRx/defect synthesis", checkpoint=dust["parent"]["checkpoint"]))
    ledger.freeze_manifest("metering", "common-v1", preflight["metering_manifest"]["path"],
                           dict(task="reference exposure compensation", labels="reference zero; negative perturbation delta", checkpoint=metering["checkpoint"]))
    after = legacy_fingerprint(ledger.db)
    if before != after or ledger.db.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or ledger.db.execute("PRAGMA foreign_key_check").fetchall():
        raise RuntimeError("Additive registry integrity/legacy preservation check failed")
    ledger.close()
    report.update(legacy_rows_preserved=True, backup=str(backup), backup_sha256=digest(backup),
                  resulting_registry_sha256=digest(target), shared_schema_version=1)
    write_json(root / "publication.json", report)
    write_json(pool / "shared-training.json", dict(schema_version=1, canonical_registry=str(target),
                                                  control_root=str(root), automatic_gc_enabled=False,
                                                  consumers=["dust", "metering"], state="ready-for-queued-training"))
    update(root, "audit", state="published-all-gates-passed", accepted=report["accepted"], quarantined=report["quarantined"],
           canonical_registry=str(target), legacy_rows_preserved=True, automatic_gc_enabled=False)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    import json
    print(json.dumps(publish(args.root, args.apply), ensure_ascii=True, indent=2))
