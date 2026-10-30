# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest

from common import gpu_slot, read_json, update, write_json
from concurrent.futures import ThreadPoolExecutor
from coordinator import finish_metering_stage, reserve_metering_stage
from ledger import Ledger, legacy_fingerprint
from validate import finite, tree_hash
import torch


class SharedContracts(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.ledger = Ledger(self.root / "registry.sqlite3")
        self.ledger.initialize()
        self.rows = []
        groups = {}
        for index, split in enumerate(("train", "val", "test")):
            sha = str(index) * 64
            record = dict(sha256=sha, id=f"image-{index}", image=str(self.root / f"{index}.png"), phash="0" * 16,
                          phash_version="test", group_id=f"group-{index}", global_split=split,
                          quality_state="accepted", licence="CC0-1.0", source_record={})
            groups[record["group_id"]] = dict(split=split, evidence=[])
            self.rows.append(record)
        self.ledger.register(self.rows, groups)
        self.checkpoint = self.root / "checkpoint.bin"
        self.checkpoint.write_bytes(b"committed test state")

    def tearDown(self):
        self.ledger.close()
        self.temporary.cleanup()

    def add(self, consumer, role="train", index=0, required=1):
        row = dict(sha256=self.rows[index]["sha256"], split=role)
        self.ledger.batch(consumer, "batch-1", "frozen", [row], "parent", dict(cursor=0), required)

    def commit(self, consumer, units=1):
        return self.ledger.commit(consumer, "batch-1", self.checkpoint, dict(cursor=units), {self.rows[0]["sha256"]: units})

    def test_unknown_or_unreferenced_asset_cannot_be_reclaimed(self):
        self.assertFalse(self.ledger.may_reclaim("unknown"))
        self.assertFalse(self.ledger.may_reclaim(self.rows[0]["sha256"]))

    def test_one_consumer_completion_is_insufficient(self):
        self.add("dust")
        self.add("metering")
        self.commit("dust")
        self.assertFalse(self.ledger.may_reclaim(self.rows[0]["sha256"]))

    def test_all_consumers_must_have_committed_complete(self):
        for consumer in ("dust", "metering"):
            self.add(consumer)
            self.commit(consumer)
        self.assertTrue(self.ledger.may_reclaim(self.rows[0]["sha256"]))

    def test_partial_consumption_stays_protected(self):
        for consumer in ("dust", "metering"):
            self.add(consumer, required=2)
            self.commit(consumer, units=1)
        self.assertFalse(self.ledger.may_reclaim(self.rows[0]["sha256"]))

    def test_active_read_lease_blocks_gc(self):
        for consumer in ("dust", "metering"):
            self.add(consumer)
            self.commit(consumer)
        with self.ledger.db:
            self.ledger.db.execute("INSERT INTO shared_read_leases VALUES(?,?,?,?,?)", ("metering", "batch-1", self.rows[0]["sha256"], "reader", "now"))
        self.assertFalse(self.ledger.may_reclaim(self.rows[0]["sha256"]))

    def test_both_heldout_splits_forbid_other_model_training(self):
        for index in (1, 2):
            with self.assertRaisesRegex(sqlite3.IntegrityError, "validation/test"):
                self.add("metering", index=index)

    def test_heldout_identity_and_group_are_immutable(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.db.execute("UPDATE shared_assets SET global_split='train' WHERE sha256=?", (self.rows[1]["sha256"],))
        self.ledger.db.rollback()
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.db.execute("UPDATE shared_assets SET group_id='group-0' WHERE sha256=?", (self.rows[1]["sha256"],))

    def test_quarantined_input_cannot_enter_new_training(self):
        with self.ledger.db:
            self.ledger.db.execute("UPDATE shared_assets SET quality='quarantined' WHERE sha256=?", (self.rows[0]["sha256"],))
        with self.assertRaisesRegex(ValueError, "Quarantined"):
            self.add("dust")

    def test_reference_asset_cannot_be_swapped_to_heldout(self):
        self.add("dust")
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.db.execute("UPDATE shared_refs SET asset_sha256=? WHERE consumer='dust'", (self.rows[1]["sha256"],))

    def test_group_split_cannot_be_reclassified(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.db.execute("UPDATE shared_groups SET split='train' WHERE group_id='group-1'")

    def test_trained_asset_cannot_be_relabeled_as_validation(self):
        self.add("dust")
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.db.execute("UPDATE shared_assets SET group_id='group-1',global_split='val' WHERE sha256=?", (self.rows[0]["sha256"],))

    def test_frozen_manifest_hash_cannot_change(self):
        self.add("dust")
        with self.assertRaisesRegex(ValueError, "manifest changed"):
            self.ledger.batch("dust", "batch-1", "different", [], "parent", {})

    def test_missing_checkpoint_cannot_commit(self):
        self.add("dust")
        with self.assertRaises(FileNotFoundError):
            self.ledger.commit("dust", "batch-1", self.root / "absent", {}, {self.rows[0]["sha256"]: 1})
        self.assertFalse(self.ledger.may_reclaim(self.rows[0]["sha256"]))

    def test_state_fingerprints_do_not_mix_consumers(self):
        a = dict(model=dict(weight=torch.tensor([1.])), optimizer=dict(step=torch.tensor(4.)))
        b = dict(model=dict(weight=torch.tensor([2.])), optimizer=dict(step=torch.tensor(7.)))
        self.assertNotEqual(tree_hash(a), tree_hash(b))
        self.assertTrue(finite(a))
        self.assertFalse(finite(dict(value=torch.tensor(float("nan")))))

    def test_legacy_rows_are_unaffected_by_additive_schema(self):
        connection = sqlite3.connect(self.root / "legacy.sqlite3")
        for table in ("originals", "settings", "batches"):
            connection.execute(f"CREATE TABLE {table}(id TEXT,value TEXT)")
            connection.execute(f"INSERT INTO {table} VALUES('fixed','unchanged')")
        connection.commit()
        before = legacy_fingerprint(connection)
        connection.close()
        ledger = Ledger(self.root / "legacy.sqlite3")
        ledger.initialize()
        self.assertEqual(before, legacy_fingerprint(ledger.db))
        ledger.close()

    def test_two_gpu_workers_are_serialized(self):
        children = [subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--lock-worker", str(self.root), consumer])
                    for consumer in ("dust", "metering")]
        for child in children:
            self.assertEqual(child.wait(timeout=30), 0)
        events = [json.loads(line) for line in (self.root / "events.jsonl").read_text().splitlines()]
        self.assertEqual([event["event"] for event in events], ["start", "end", "start", "end"])
        self.assertNotEqual(events[0]["consumer"], events[2]["consumer"])

    def test_paused_metering_intent_keeps_original_parent_and_stage(self):
        manifest = self.root / "metering.jsonl"
        manifest.write_text("frozen mapping", encoding="utf-8")
        state = dict(metering_stage=0, metering_parent="original-full-state.pt", last_metering_catalog=None)
        intent = reserve_metering_stage(state, self.root, manifest, "catalog-a")
        self.assertFalse(finish_metering_stage(state, dict(stop_reason="user-pause", checkpoint="partial.pt")))
        resumed = reserve_metering_stage(state, self.root, manifest, "newly-arrived-catalog")
        self.assertEqual(resumed, intent)
        self.assertEqual(state["metering_stage"], 1)
        self.assertEqual(state["metering_parent"], "original-full-state.pt")
        self.assertIsNone(state["last_metering_catalog"])
        self.assertTrue(finish_metering_stage(state, dict(stop_reason="stage-budget", checkpoint="complete.pt")))
        self.assertEqual(state["metering_parent"], "complete.pt")
        self.assertEqual(state["last_metering_catalog"], "catalog-a")
        self.assertNotIn("pending_metering", state)

    def test_scheduler_status_accepts_saved_timestamp(self):
        update(self.root, "scheduler", state="paused", updated_at="old-checkpoint-timestamp")
        observed = read_json(self.root / "scheduler-status.json")
        self.assertEqual(observed["state"], "paused")
        self.assertNotEqual(observed["updated_at"], "old-checkpoint-timestamp")

    def test_parallel_collection_status_writes_are_atomic(self):
        path = self.root / "collection-status.json"
        with ThreadPoolExecutor(max_workers=4) as executor:
            results = list(executor.map(lambda value: write_json(path, dict(value=value, payload="x"*1000)), range(64)))
        self.assertEqual(len(results), 64)
        self.assertIn(read_json(path)["value"], range(64))
        self.assertEqual(list(self.root.glob("*.tmp")), [])


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--lock-worker":
        root, consumer = Path(sys.argv[2]), sys.argv[3]
        with gpu_slot(root, consumer):
            with (root / "events.jsonl").open("a") as stream:
                stream.write(json.dumps(dict(event="start", consumer=consumer)) + "\n")
            time.sleep(0.2)
            with (root / "events.jsonl").open("a") as stream:
                stream.write(json.dumps(dict(event="end", consumer=consumer)) + "\n")
    else:
        unittest.main(verbosity=2)
