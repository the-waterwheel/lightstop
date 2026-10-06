"""Create a Java/JNI-only AAR without changing any runtime payload.

Prefab serves native C++ consumers. The lightstop app uses Java/JNI and links its
own C++ library only against Android log; it does not enable Gradle Prefab.
Use the full AAR if native OpenCV C++ consumption is introduced later.
"""

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def runtime_aar(source, destination):
    if source.resolve() == destination.resolve():
        raise ValueError("Use a separate destination")
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if "classes.jar" not in names or not any(n.startswith("jni/") and n.endswith("libopencv_java4.so") for n in names):
            raise ValueError("Expected an OpenCV Java/JNI AAR")
        if not any(n.startswith("prefab/") for n in names):
            raise ValueError("Source has no Prefab payload to remove")
        with zipfile.ZipFile(destination, "w") as output:
            for info in archive.infolist():
                if not info.filename.startswith("prefab/"):
                    output.writestr(info, archive.read(info))
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination) as output:
        expected = {n for n in original.namelist() if not n.startswith("prefab/")}
        if set(output.namelist()) != expected:
            raise ValueError("Runtime entries changed")
        for name in expected:
            if original.read(name) != output.read(name):
                raise ValueError("Runtime entry bytes changed: " + name)
    return {"source_bytes": source.stat().st_size, "runtime_bytes": destination.stat().st_size,
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            "runtime_entries": len(expected), "runtime_payload_unchanged": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(runtime_aar(args.source, args.destination), indent=2))


if __name__ == "__main__":
    main()
