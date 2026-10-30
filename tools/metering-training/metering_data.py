# SPDX-License-Identifier: Apache-2.0
"""Read-only shared originals and immutable, versioned uint8 sampling caches."""
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageOps
import torch

CACHE_VERSION = "srgb-point-roi-v1"
SAMPLE_WIDTH, SAMPLE_HEIGHT = 96, 64


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def now():
    return datetime.now(timezone.utc).isoformat()


def roi_definitions():
    result = [dict(name="full", zoom=1.0, x=0.5, y=0.5)]
    for zoom in (1.5, 2.0, 4.0):
        result.append(dict(name=f"center-{zoom:g}x", zoom=zoom, x=0.5, y=0.5))
    for zoom in (1.5, 2.0, 4.0):
        for name, x, y in (("tl", 0, 0), ("tr", 1, 0), ("bl", 0, 1), ("br", 1, 1)):
            result.append(dict(name=f"{name}-{zoom:g}x", zoom=zoom, x=x, y=y))
    return result


def roi_bounds(width, height, definition):
    crop_width, crop_height = width / definition["zoom"], height / definition["zoom"]
    left = (width - crop_width) * definition["x"]
    top = (height - crop_height) * definition["y"]
    return left, top, left + crop_width, top + crop_height


def sample_roi(rgb, definition):
    height, width, channels = rgb.shape
    if channels != 3 or min(width, height) < 2:
        raise ValueError("Expected a nonempty RGB original")
    left, top, right, bottom = roi_bounds(width, height, definition)
    xs = np.floor(left + (np.arange(SAMPLE_WIDTH) + 0.5) * (right - left) / SAMPLE_WIDTH).astype(np.int64)
    ys = np.floor(top + (np.arange(SAMPLE_HEIGHT) + 0.5) * (bottom - top) / SAMPLE_HEIGHT).astype(np.int64)
    xs, ys = np.clip(xs, 0, width - 1), np.clip(ys, 0, height - 1)
    return np.ascontiguousarray(rgb[ys[:, None], xs[None, :], :].transpose(2, 0, 1))


def read_manifest(path):
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        raise ValueError("Empty manifest")
    identities, digests, groups = {}, {}, {}
    for row in rows:
        if row.get("split") not in ("train", "val", "test"):
            raise ValueError("Unknown split")
        record = row.get("source_record", row)
        for seen, key in ((identities, row["id"]), (digests, row["sha256"]), (groups, record.get("group", row["id"]))):
            if key in seen and seen[key] != row["split"]:
                raise ValueError(f"Cross-split leakage: {key}")
            if seen is not groups and key in seen:
                raise ValueError(f"Duplicate original: {key}")
            seen[key] = row["split"]
        if row.get("source_license") not in ("CC0-1.0", "CC-BY-4.0"):
            raise ValueError("Unreviewed source licence")
        if not record.get("source_url") or not record.get("license_url"):
            raise ValueError("Missing provenance")
        if row["source_license"] == "CC-BY-4.0" and not record.get("author"):
            raise ValueError("CC-BY source lacks attribution")
    if {row["split"] for row in rows} != {"train", "val", "test"}:
        raise ValueError("All three splits are required")
    return rows


def decode_srgb(path):
    with Image.open(path) as original:
        profile = original.info.get("icc_profile")
        image = ImageOps.exif_transpose(original)
        if profile:
            source_profile = ImageCms.ImageCmsProfile(io.BytesIO(profile))
            image = ImageCms.profileToProfile(image, source_profile, ImageCms.createProfile("sRGB"), outputMode="RGB")
            profile_status = "embedded-icc-to-srgb"
        else:
            if image.mode not in ("RGB", "RGBA", "L", "LA", "P"):
                raise ValueError(f"Cannot assume sRGB for mode {image.mode}")
            image = image.convert("RGB")
            profile_status = "assumed-srgb-no-icc"
        return np.array(image, dtype=np.uint8, copy=True), profile_status


def verify_cache(cache, originals=True):
    cache = Path(cache).resolve()
    index = json.loads((cache / "index.json").read_text(encoding="utf-8"))
    if index["cache_version"] != CACHE_VERSION or index["rois"] != roi_definitions():
        raise ValueError("Cache definition mismatch")
    if sha256(index["source_manifest"]) != index["source_manifest_sha256"]:
        raise ValueError("Source manifest changed")
    rows = read_manifest(index["source_manifest"])
    if [row["id"] for row in rows] != [item["record"]["id"] for item in index["items"]]:
        raise ValueError("Cache membership/order mismatch")
    for item in index["items"]:
        data_path = (cache / item["file"]).resolve()
        if data_path.parent != cache or sha256(data_path) != item["cache_sha256"]:
            raise ValueError("Corrupt/unexpected sampling cache")
        row = item["record"]
        if originals and sha256(row["image"]) != row["sha256"]:
            raise ValueError(f"Original changed: {row['id']}")
    return index


def prepare_cache(manifest, cache):
    manifest, cache = Path(manifest).resolve(), Path(cache).resolve()
    if (cache / "index.json").is_file():
        return verify_cache(cache)
    rows = read_manifest(manifest)
    cache.mkdir(parents=True, exist_ok=True)
    items = []
    for ordinal, row in enumerate(rows):
        source = Path(row["image"]).resolve()
        if source == cache or cache in source.parents or source.parent == cache:
            raise ValueError("Sampling cache must be separate from originals")
        if sha256(source) != row["sha256"]:
            raise ValueError(f"Original hash mismatch: {row['id']}")
        rgb, profile_status = decode_srgb(source)
        samples = np.stack([sample_roi(rgb, roi) for roi in roi_definitions()])
        filename = row["sha256"] + ".npy"
        target = cache / filename
        if target.exists():
            existing = np.load(target, allow_pickle=False)
            if not np.array_equal(existing, samples):
                raise ValueError("Existing cache differs; choose a new cache version/directory")
        else:
            with target.with_suffix(".tmp").open("wb") as stream:
                np.save(stream, samples, allow_pickle=False)
            os.replace(target.with_suffix(".tmp"), target)
        items.append(dict(record=row, file=filename, cache_sha256=sha256(target),
                          oriented_width=int(rgb.shape[1]), oriented_height=int(rgb.shape[0]),
                          colour_profile=profile_status))
        if (ordinal + 1) % 16 == 0 or ordinal + 1 == len(rows):
            print(json.dumps(dict(event="prepare", completed=ordinal + 1, total=len(rows))), flush=True)
        del rgb, samples
    index = dict(cache_version=CACHE_VERSION, created_at=now(), source_manifest=str(manifest),
                 source_manifest_sha256=sha256(manifest), rois=roi_definitions(),
                 sampling="nearest native RGB pixel at uniform ROI point centres; no full-frame float image",
                 shape=[16, 3, SAMPLE_HEIGHT, SAMPLE_WIDTH], dtype="uint8-sRGB", items=items)
    atomic_json(cache / "index.json", index)
    return verify_cache(cache)


def load_bank(cache, index, split, device):
    arrays, metadata = [], []
    for item in index["items"]:
        row = item["record"]
        if row["split"] != split:
            continue
        array = np.load(Path(cache) / item["file"], allow_pickle=False)
        if array.dtype != np.uint8 or list(array.shape) != [16, 3, SAMPLE_HEIGHT, SAMPLE_WIDTH]:
            raise ValueError("Unexpected sampling cache layout")
        arrays.append(array)
        metadata.extend(dict(id=row["id"], split=split, roi=roi["name"], source=row.get("source", "unknown"))
                        for roi in roi_definitions())
    if not arrays:
        raise ValueError(f"Empty split: {split}")
    return torch.from_numpy(np.concatenate(arrays)).to(device=device), metadata
