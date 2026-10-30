# SPDX-License-Identifier: Apache-2.0
"""One existing collector; newly obtained originals wait for shared quality review."""
import os
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
import argparse
import json
from pathlib import Path
import runpy
import sys
sys.dont_write_bytecode = True

from PIL import Image

from audit import PHASH_VERSION, exif_metadata
from common import digest, read_json, stamp, update
from ledger import Ledger


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "pool", "snapshot", "config"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(args.snapshot.resolve() / "src"))
    sys.path.insert(0, str(args.snapshot.resolve() / "tools"))
    from film_dust.continuous_pool import Registry
    original_admit, original_claim = Registry.admit, Registry.claim

    def claim(self, identity, reservation):
        pending = self.db.execute("SELECT COUNT(*) FROM shared_assets WHERE quality='pending'").fetchone()[0]
        if pending >= 32 or (args.root / "PAUSE.json").exists():
            return False
        return original_claim(self, identity, reservation)

    def admit(self, row):
        if not original_admit(self, row):
            return False
        path = Path(row["image"]).resolve()
        if path.parent != self.owned or digest(path) != row["sha256"]:
            raise ValueError("Shared collector ownership/checksum failure")
        stored = self.db.execute("SELECT phash FROM originals WHERE id=?", (row["id"],)).fetchone()[0]
        with Image.open(path) as image:
            width, height = image.size
            metadata = exif_metadata(image)
            file_format, mode = image.format, image.mode
        record = dict(id=row["id"], sha256=row["sha256"], image=str(path), phash=stored, phash_version=PHASH_VERSION,
                      group_id=row["id"], global_split="train", quality_state="pending", licence=row.get("source_license"),
                      source_record=row, exif=metadata, width=width, height=height, format=file_format, mode=mode,
                      bytes=path.stat().st_size, quality_review="new acquisition; never eligible until shared review")
        ledger = Ledger(args.pool / "continuous-registry.sqlite3")
        ledger.register([record], {row["id"]: dict(split="train", evidence=["pending source; review before scene grouping/admission"])})
        pending = ledger.db.execute("SELECT COUNT(*) FROM shared_assets WHERE quality='pending'").fetchone()[0]
        ledger.close()
        update(args.root, "collection", state="waiting-quality-review" if pending >= 32 else "collecting-to-review-queue",
               pending_originals=pending, latest_id=row["id"], model_training_eligible=False,
               policy="single collector; no new source enters either model without shared review")
        return True
    Registry.claim, Registry.admit = claim, admit
    sys.argv = [str(args.snapshot / "tools" / "continuous_sources.py"), str(args.pool), str(args.config)]
    runpy.run_path(str(args.snapshot / "tools" / "continuous_sources.py"), run_name="__main__")


if __name__ == "__main__":
    main()
