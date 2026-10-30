# SPDX-License-Identifier: Apache-2.0
"""Freeze our queue and metering sources; never copy the external GPL dust tree."""
import argparse
from pathlib import Path
import shutil
from common import digest, read_json, stamp, write_json


def freeze(workspace, root, name="runtime-snapshot"):
    if not name.startswith("runtime-snapshot") or Path(name).name != name:
        raise ValueError("Runtime name must be a local runtime-snapshot version")
    target = root / name
    files = {}
    for directory in ("shared-training", "metering-training"):
        source = workspace / "tools" / directory
        paths = list(source.glob("*.py"))
        if directory == "metering-training":
            paths.append(source / "config.json")
        for path in paths:
            files["tools/" + directory + "/" + path.name] = path
    files["LICENSE"] = workspace / "LICENSE"
    checksums = {name: digest(path) for name, path in files.items()}
    if (target / "files.json").exists():
        if read_json(target / "files.json")["files"] != checksums:
            raise RuntimeError("Runtime snapshot already exists with different sources; preserve it")
        if any(digest(target / name) != checksum for name, checksum in checksums.items()):
            raise RuntimeError("Frozen runtime changed")
        return target
    for name, source in files.items():
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    write_json(target / "files.json", dict(created_at=stamp(), files=checksums,
               scope="independently authored shared coordination + metering; external GPL dust snapshot referenced separately"))
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--name", default="runtime-snapshot")
    args = parser.parse_args()
    print(freeze(Path(__file__).resolve().parents[2], args.root.resolve(), args.name))
