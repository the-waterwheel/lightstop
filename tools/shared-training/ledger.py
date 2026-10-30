# SPDX-License-Identifier: Apache-2.0
"""Additive shared registry schema and fail-closed retention/partition contracts."""
import json
from pathlib import Path
import sqlite3

from common import digest, stamp

SCHEMA = """
CREATE TABLE IF NOT EXISTS shared_groups (
 group_id TEXT PRIMARY KEY, split TEXT NOT NULL CHECK(split IN ('train','val','test')), evidence TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS shared_assets (
 sha256 TEXT PRIMARY KEY, original_id TEXT NOT NULL UNIQUE, image TEXT NOT NULL, phash TEXT NOT NULL,
 phash_version TEXT NOT NULL, group_id TEXT NOT NULL REFERENCES shared_groups(group_id),
 global_split TEXT NOT NULL CHECK(global_split IN ('train','val','test')),
 quality TEXT NOT NULL CHECK(quality IN ('accepted','quarantined','pending')),
 licence TEXT NOT NULL, source_json TEXT NOT NULL, audit_json TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS shared_manifests (
 consumer TEXT NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL, sha256 TEXT NOT NULL,
 mapping_json TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(consumer,name));
CREATE TABLE IF NOT EXISTS shared_batches (
 consumer TEXT NOT NULL, batch_id TEXT NOT NULL, manifest_sha256 TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('planned','active','committed','complete','failed')),
 parent_checkpoint TEXT, committed_checkpoint TEXT, cursor_json TEXT NOT NULL,
 updated_at TEXT NOT NULL, PRIMARY KEY(consumer,batch_id));
CREATE TABLE IF NOT EXISTS shared_refs (
 consumer TEXT NOT NULL, batch_id TEXT NOT NULL, asset_sha256 TEXT NOT NULL REFERENCES shared_assets(sha256),
 role TEXT NOT NULL CHECK(role IN ('train','val','test')),
 state TEXT NOT NULL CHECK(state IN ('needed','active','complete','hold')),
 consumed INTEGER NOT NULL DEFAULT 0, required INTEGER NOT NULL DEFAULT 1,
 checkpoint_sha256 TEXT, updated_at TEXT NOT NULL,
 PRIMARY KEY(consumer,batch_id,asset_sha256),
 CHECK(state!='complete' OR (checkpoint_sha256 IS NOT NULL AND consumed>=required)));
CREATE TABLE IF NOT EXISTS shared_read_leases (
 consumer TEXT NOT NULL, batch_id TEXT NOT NULL, asset_sha256 TEXT NOT NULL,
 owner TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(consumer,batch_id,asset_sha256,owner));
CREATE TABLE IF NOT EXISTS shared_events (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, kind TEXT NOT NULL, details_json TEXT NOT NULL);
CREATE TRIGGER IF NOT EXISTS shared_heldout_immutable BEFORE UPDATE OF global_split ON shared_assets
 WHEN OLD.global_split IN ('val','test') AND NEW.global_split!=OLD.global_split
 BEGIN SELECT RAISE(ABORT,'fixed held-out membership is immutable'); END;
CREATE TRIGGER IF NOT EXISTS shared_group_partition_insert BEFORE INSERT ON shared_assets
 WHEN NEW.global_split!=(SELECT split FROM shared_groups WHERE group_id=NEW.group_id)
 BEGIN SELECT RAISE(ABORT,'asset/group split mismatch'); END;
CREATE TRIGGER IF NOT EXISTS shared_group_partition_update BEFORE UPDATE OF global_split,group_id ON shared_assets
 WHEN NEW.global_split!=(SELECT split FROM shared_groups WHERE group_id=NEW.group_id)
 BEGIN SELECT RAISE(ABORT,'asset/group split mismatch'); END;
CREATE TRIGGER IF NOT EXISTS shared_no_heldout_train_insert BEFORE INSERT ON shared_refs
 WHEN NEW.role='train' AND (SELECT global_split FROM shared_assets WHERE sha256=NEW.asset_sha256)!='train'
 BEGIN SELECT RAISE(ABORT,'shared validation/test cannot be used for training'); END;
CREATE TRIGGER IF NOT EXISTS shared_no_heldout_train_update BEFORE UPDATE OF role ON shared_refs
 WHEN NEW.role='train' AND (SELECT global_split FROM shared_assets WHERE sha256=NEW.asset_sha256)!='train'
 BEGIN SELECT RAISE(ABORT,'shared validation/test cannot be used for training'); END;
CREATE TRIGGER IF NOT EXISTS shared_no_heldout_asset_ref_update BEFORE UPDATE OF role,asset_sha256 ON shared_refs
 WHEN NEW.role='train' AND (SELECT global_split FROM shared_assets WHERE sha256=NEW.asset_sha256)!='train'
 BEGIN SELECT RAISE(ABORT,'shared validation/test cannot be used for training'); END;
CREATE TRIGGER IF NOT EXISTS shared_group_split_immutable BEFORE UPDATE OF split ON shared_groups
 WHEN NEW.split!=OLD.split
 BEGIN SELECT RAISE(ABORT,'registered group split is immutable'); END;
CREATE TRIGGER IF NOT EXISTS shared_trained_asset_cannot_become_heldout BEFORE UPDATE OF global_split ON shared_assets
 WHEN NEW.global_split IN ('val','test') AND EXISTS(SELECT 1 FROM shared_refs WHERE asset_sha256=OLD.sha256 AND role='train')
 BEGIN SELECT RAISE(ABORT,'previously trained asset cannot become held-out'); END;
"""


class Ledger:
    def __init__(self, path):
        self.path = Path(path).resolve()
        self.db = sqlite3.connect(self.path, timeout=30)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA busy_timeout=30000")

    def initialize(self):
        self.db.executescript(SCHEMA)
        self.db.commit()

    def close(self):
        self.db.close()

    def event(self, kind, details):
        self.db.execute("INSERT INTO shared_events(at,kind,details_json) VALUES(?,?,?)",
                        (stamp(), kind, json.dumps(details, ensure_ascii=False)))

    def register(self, records, groups):
        with self.db:
            for group_id, value in groups.items():
                existing = self.db.execute("SELECT split FROM shared_groups WHERE group_id=?", (group_id,)).fetchone()
                if existing and existing["split"] != value["split"]:
                    raise ValueError("Cannot change a frozen group split")
                self.db.execute("INSERT OR IGNORE INTO shared_groups VALUES(?,?,?)",
                                (group_id, value["split"], json.dumps(value["evidence"], ensure_ascii=False)))
            for record in records:
                data = (record["sha256"], record["id"], record["image"], record["phash"], record["phash_version"],
                        record["group_id"], record["global_split"], record["quality_state"], record["licence"],
                        json.dumps(record["source_record"], ensure_ascii=False), json.dumps(record, ensure_ascii=False), stamp())
                self.db.execute("INSERT INTO shared_assets VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(sha256) DO UPDATE SET "
                                "quality=excluded.quality,audit_json=excluded.audit_json,updated_at=excluded.updated_at", data)
            self.event("audit-registration", dict(assets=len(records), groups=len(groups)))

    def freeze_manifest(self, consumer, name, path, mapping):
        checksum = digest(path)
        with self.db:
            previous = self.db.execute("SELECT sha256 FROM shared_manifests WHERE consumer=? AND name=?", (consumer, name)).fetchone()
            if previous and previous[0] != checksum:
                raise ValueError("Frozen manifest changed")
            self.db.execute("INSERT OR IGNORE INTO shared_manifests VALUES(?,?,?,?,?,?)",
                            (consumer, name, str(Path(path).resolve()), checksum, json.dumps(mapping), stamp()))
        return checksum

    def batch(self, consumer, batch_id, manifest_hash, rows, parent_checkpoint, cursor, required=1):
        with self.db:
            previous = self.db.execute("SELECT manifest_sha256 FROM shared_batches WHERE consumer=? AND batch_id=?", (consumer, batch_id)).fetchone()
            if previous and previous[0] != manifest_hash:
                raise ValueError("Batch manifest changed")
            self.db.execute("INSERT OR IGNORE INTO shared_batches VALUES(?,?,?,?,?,?,?,?)",
                            (consumer, batch_id, manifest_hash, "active", parent_checkpoint, None, json.dumps(cursor), stamp()))
            for row in rows:
                role = row["split"]
                asset = self.db.execute("SELECT quality FROM shared_assets WHERE sha256=?", (row["sha256"],)).fetchone()
                if not asset:
                    raise ValueError("Unknown original in shared batch")
                if role == "train" and asset[0] != "accepted":
                    raise ValueError("Quarantined/pending original cannot enter a new training batch")
                self.db.execute("INSERT OR IGNORE INTO shared_refs VALUES(?,?,?,?,?,?,?,?,?)",
                                (consumer, batch_id, row["sha256"], role, "active" if role == "train" else "hold",
                                 0, required if role == "train" else 1, None, stamp()))

    def commit(self, consumer, batch_id, checkpoint_path, cursor, consumed):
        checksum = digest(checkpoint_path)
        with self.db:
            batch = self.db.execute("SELECT state FROM shared_batches WHERE consumer=? AND batch_id=?", (consumer, batch_id)).fetchone()
            if not batch:
                raise ValueError("Unknown batch")
            for asset, units in consumed.items():
                self.db.execute("UPDATE shared_refs SET consumed=?,checkpoint_sha256=?,updated_at=? "
                                "WHERE consumer=? AND batch_id=? AND asset_sha256=? AND role='train'",
                                (units, checksum, stamp(), consumer, batch_id, asset))
            pending = self.db.execute("SELECT COUNT(*) FROM shared_refs WHERE consumer=? AND batch_id=? AND role='train' AND consumed<required",
                                      (consumer, batch_id)).fetchone()[0]
            state = "committed" if pending else "complete"
            self.db.execute("UPDATE shared_batches SET state=?,committed_checkpoint=?,cursor_json=?,updated_at=? WHERE consumer=? AND batch_id=?",
                            (state, checksum, json.dumps(cursor), stamp(), consumer, batch_id))
            if not pending:
                self.db.execute("UPDATE shared_refs SET state='complete',updated_at=? WHERE consumer=? AND batch_id=? AND role='train'",
                                (stamp(), consumer, batch_id))
            self.event("checkpoint-commit", dict(consumer=consumer, batch_id=batch_id, checkpoint=checksum, cursor=cursor, state=state))
        return state

    def may_reclaim(self, asset_sha256):
        asset = self.db.execute("SELECT global_split FROM shared_assets WHERE sha256=?", (asset_sha256,)).fetchone()
        if not asset or asset[0] != "train":
            return False
        if self.db.execute("SELECT 1 FROM shared_read_leases WHERE asset_sha256=?", (asset_sha256,)).fetchone():
            return False
        refs = self.db.execute("SELECT state,consumed,required,checkpoint_sha256 FROM shared_refs WHERE asset_sha256=?", (asset_sha256,)).fetchall()
        # Absence is not proof that both consumers are done.
        consumers = {row[0] for row in self.db.execute("SELECT DISTINCT consumer FROM shared_refs WHERE asset_sha256=?", (asset_sha256,))}
        return consumers >= {"dust", "metering"} and bool(refs) and all(
            row["state"] == "complete" and row["consumed"] >= row["required"] and row["checkpoint_sha256"] for row in refs)


def legacy_fingerprint(connection):
    """Guard the original campaign/optimizer bookkeeping during additive migration."""
    import hashlib
    value = hashlib.sha256()
    for table in ("originals", "settings", "batches"):
        for row in connection.execute(f"SELECT * FROM {table} ORDER BY 1"):
            value.update(json.dumps(tuple(row), ensure_ascii=False, separators=(",", ":")).encode())
    return value.hexdigest()
