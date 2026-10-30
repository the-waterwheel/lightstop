# SPDX-License-Identifier: Apache-2.0
"""Freeze separate task manifests and stage additive shared-registry tables."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import shutil

from PIL import Image

from audit import Groups, exif_metadata, manifest_rows
from common import digest, read_json, stamp, update, write_json
from ledger import Ledger, legacy_fingerprint


def freeze_lines(path, rows):
    encoded = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows)
    if path.exists() and path.read_text(encoding="utf-8") != encoded:
        raise ValueError("Do not overwrite a frozen model manifest")
    if not path.exists():
        path.write_text(encoded, encoding="utf-8")
    return digest(path)


def prepare(root):
    root = Path(root).resolve()
    audit = read_json(root / "audit.json")
    records = audit["records"]
    by_ordinal = {row["ordinal"]: row for row in records}
    # Decisions recorded only after all nine sheets and both native-pixel sheets
    # have been inspected. No original is deleted, renamed or recoded.
    visual = dict(reviewed_at=stamp(), audit_sha256=digest(root / "audit.json"),
                  coverage=dict(contact_sheet_originals=263, native_resolution_first_originals=32),
                  quarantine=[dict(ordinal=1, id=by_ordinal[1]["id"],
                                   reason="digitized historical print; outside the existing natural-camera clean-base acquisition policy")],
                  same_scene_edges=[dict(left=82, right=83, reason="same author/date/site and explicitly numbered jellyfish photo set; visual/source-page confirmed"),
                                    dict(left=133, right=156, reason="conservative training-only session grouping: neighbouring upstream source IDs and autumn forest series")],
                  similar_scene_review=[dict(ordinals=[133, 156, 162],
                                             decision="No cross-split duplicate established: distinct visible scene/terrain; ORB ratio matches 0/2/3 and no homography inliers; category resemblance alone is not a scene ID",
                                             evidence="forest-matches.json and quality/forest-scene-comparison.jpg")],
                  licensing_source_checks=[
                      "https://structpku.github.io/LIU4K_Dataset/LIU4K_v2.html",
                      "https://creativecommons.org/licenses/by/4.0/",
                      "https://creativecommons.org/publicdomain/zero/1.0/",
                      by_ordinal[1]["source_record"]["source_url"], by_ordinal[18]["source_record"]["source_url"],
                      by_ordinal[82]["source_record"]["source_url"], by_ordinal[83]["source_record"]["source_url"]],
                  limitations=["No claim that visual/metadata review can identify every geographically identical scene without metadata",
                               "Historical evicted image pixels cannot be visually re-audited; historical ledger is retained",
                               "Image 1 was used in the earlier metering pretraining; quarantine is prospective and does not erase that history"])
    group = Groups([row["id"] for row in records])
    edges = list(audit["candidate_edges"])
    for edge in edges:
        group.join(edge["left"], edge["right"])
    for edge in visual["same_scene_edges"]:
        left, right = by_ordinal[edge["left"]], by_ordinal[edge["right"]]
        group.join(left["id"], right["id"])
        edges.append(dict(left=left["id"], right=right["id"], reason=edge["reason"]))
    # Read nested EXIF IFDs (not just the root) before auditing camera bursts.
    for row in records:
        if digest(row["image"]) != row["sha256"]:
            raise ValueError("Original changed during visual audit")
        with Image.open(row["image"]) as image:
            row["exif"] = exif_metadata(image)
    for position, left in enumerate(records):
        for right in records[position+1:]:
            a, b = left["exif"], right["exif"]
            same_body = a["body_serial"] and a["body_serial"] == b["body_serial"]
            same_author_camera = (left["source_record"].get("author") and left["source_record"].get("author") == right["source_record"].get("author") and
                                  a["make"] and a["model"] and a["make"] == b["make"] and a["model"] == b["model"])
            if not (same_body or same_author_camera) or not a["datetime_original"] or not b["datetime_original"]:
                continue
            try:
                seconds = abs((datetime.strptime(a["datetime_original"], "%Y:%m:%d %H:%M:%S") -
                               datetime.strptime(b["datetime_original"], "%Y:%m:%d %H:%M:%S")).total_seconds())
            except ValueError:
                continue
            if seconds <= 3:
                group.join(left["id"], right["id"])
                edges.append(dict(left=left["id"], right=right["id"], reason="same camera/author capture within 3 seconds"))
    groups, conflicts = {}, list(audit["split_conflicts"])
    for row in records:
        identity = group.find(row["id"])
        row["group_id"] = identity
        groups.setdefault(identity, dict(split=row["split"], evidence=[], members=[]))["members"].append(row["id"])
    for identity, value in groups.items():
        splits = {row["split"] for row in records if row["group_id"] == identity}
        if len(splits) > 1:
            conflicts.append(dict(group=identity, splits=sorted(splits), members=value["members"]))
        value["evidence"] = [edge for edge in edges if edge["left"] in value["members"] or edge["right"] in value["members"]]
    if conflicts:
        write_json(root / "split-conflicts.json", conflicts)
        raise ValueError("Cross-model fixed held-out grouping conflict: long training is blocked")
    for row in records:
        row["global_split"] = groups[row["group_id"]]["split"]
        row["quality_state"] = "quarantined" if row["ordinal"] == 1 else "accepted"
        row["quality_review"] = "nine whole-pool contact sheets and first-32 native crops; source-policy quarantine retained"
    accepted = [row for row in records if row["quality_state"] == "accepted"]
    dust_rows, metering_rows = [], []
    for row in accepted:
        source = dict(row["source_record"], image=row["image"], sha256=row["sha256"], id=row["id"],
                      split=row["global_split"], group=row["group_id"])
        dust_rows.append(dict(source, kind="synthetic"))
        metering_rows.append(dict(id=row["id"], image=row["image"], sha256=row["sha256"], split=row["global_split"],
                                  group=row["group_id"], source_license=row["licence"], source_record=source,
                                  metering_label=dict(kind="suitable-reference-exposure", compensation_ev=0.0,
                                                      augmentation="linear exposure delta -> negative delta target", version=1)))
    manifests = root / "manifests"
    manifests.mkdir(exist_ok=True)
    dust_path = manifests / "dust-common-v1.jsonl"
    metering_path = manifests / "metering-common-v1.jsonl"
    dust_sha, metering_sha = freeze_lines(dust_path, dust_rows), freeze_lines(metering_path, metering_rows)
    original_dust = manifest_rows(audit["dust_manifest"])
    accepted_ids = {row["id"] for row in accepted}
    rejected_active = [row["id"] for row in original_dust if row["split"] == "train" and row["id"] not in accepted_ids]
    if rejected_active:
        raise ValueError(f"Do not silently rewrite an active paused batch: {rejected_active}")
    staged = root / "staged-shared-registry.sqlite3"
    if not staged.exists():
        shutil.copyfile(root / "legacy-registry-snapshot.sqlite3", staged)
    ledger = Ledger(staged)
    before = legacy_fingerprint(ledger.db)
    ledger.initialize()
    ledger.register(records, groups)
    ledger.freeze_manifest("dust", "common-v1", dust_path,
                           dict(task="dust-and-line-defect detection plus local repair", labels="separate online GrainRx then defect synthesis", state="existing V8 detector/repair and both optimizers"))
    ledger.freeze_manifest("metering", "common-v1", metering_path,
                           dict(task="reference-exposure compensation", labels="reference zero; linear perturbation negative delta", state="independent ExposureMLP optimizer/scheduler/RNG"))
    after = legacy_fingerprint(ledger.db)
    if before != after:
        raise AssertionError("Staging altered legacy campaign rows")
    ledger.close()
    write_json(root / "visual-review.json", visual)
    write_json(root / "reviewed-assets.json", dict(groups=groups, records=records, edges=edges))
    report = dict(created_at=stamp(), metadata_and_visual_passed=True, checkpoint_validation_pending=True,
                  audited_originals=len(records), accepted_originals=len(accepted), quarantined_originals=len(records)-len(accepted),
                  splits=dict(Counter(row["global_split"] for row in accepted)), groups=len(groups),
                  held_out_unchanged=True, split_conflicts=conflicts, active_dust_manifest_unchanged=True,
                  active_dust_manifest_sha256=audit["dust_manifest_sha256"],
                  dust_manifest=dict(path=str(dust_path), sha256=dust_sha), metering_manifest=dict(path=str(metering_path), sha256=metering_sha),
                  staged_registry=str(staged), legacy_rows_fingerprint=before, source_registry_sha256=audit["registry_sha256"],
                  same_image_membership={row["id"] for row in dust_rows} == {row["id"] for row in metering_rows},
                  original_downloads=0, original_copies=0, original_deletions=0,
                  licence_policy="CC0 or CC-BY-4.0; per-file provenance retained; source images do not inherit project code licence")
    write_json(root / "shared-preflight.json", report)
    update(root, "audit", state="manifests-staged-awaiting-restore-tests", audited=len(records), accepted=len(accepted),
           quarantined=len(records)-len(accepted), groups=len(groups), split_conflicts=0, legacy_rows_preserved=True)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.root), ensure_ascii=True, indent=2))
