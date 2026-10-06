"""Check the AAR's profile, alignment and Java/JNI surface against a known baseline."""

import argparse
import hashlib
import io
import json
import re
import struct
import zipfile
from pathlib import Path


def elf_details(data):
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Expected little-endian Android ELF")
    wide = data[4] == 2
    word = "Q" if wide else "I"
    section_offset = struct.unpack_from("<" + word, data, 40 if wide else 32)[0]
    section_size, section_count, string_index = struct.unpack_from(
        "<HHH", data, 58 if wide else 46)
    sections = []
    for i in range(section_count):
        offset = section_offset + i * section_size
        sections.append({
            "name": struct.unpack_from("<I", data, offset)[0],
            "type": struct.unpack_from("<I", data, offset + 4)[0],
            "offset": struct.unpack_from("<" + word, data, offset + (24 if wide else 16))[0],
            "size": struct.unpack_from("<" + word, data, offset + (32 if wide else 20))[0],
            "link": struct.unpack_from("<I", data, offset + (40 if wide else 24))[0],
            "entry": struct.unpack_from("<" + word, data, offset + (56 if wide else 36))[0],
        })
    names_section = sections[string_index]
    names = data[names_section["offset"]:names_section["offset"] + names_section["size"]]
    for section in sections:
        start = section["name"]
        section["name"] = names[start:names.index(0, start)].decode("ascii")
    exported = set()
    for section in sections:
        if section["type"] != 11:  # SHT_DYNSYM
            continue
        strings_section = sections[section["link"]]
        strings = data[strings_section["offset"]:strings_section["offset"] + strings_section["size"]]
        for offset in range(section["offset"], section["offset"] + section["size"], section["entry"]):
            if wide:
                name, info, other, index, _, _ = struct.unpack_from("<IBBHQQ", data, offset)
            else:
                name, _, _, info, other, index = struct.unpack_from("<IIIBBH", data, offset)
            if name and index and info >> 4 in (1, 2) and other & 3 in (0, 3):
                exported.add(strings[name:strings.index(0, name)].decode("ascii"))
    program_offset = struct.unpack_from("<" + word, data, 32 if wide else 28)[0]
    program_size, program_count = struct.unpack_from("<HH", data, 54 if wide else 42)
    alignments = [
        struct.unpack_from("<" + word, data, program_offset + i * program_size + (48 if wide else 28))[0]
        for i in range(program_count)
        if struct.unpack_from("<I", data, program_offset + i * program_size)[0] == 1
    ]
    return exported, alignments


def inspect(path):
    libraries = {}
    with zipfile.ZipFile(path) as archive:
        with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
            class_names = sorted(name for name in classes.namelist() if name.endswith(".class"))
        for entry in archive.infolist():
            if not re.fullmatch(r"jni/[^/]+/libopencv_java4\.so", entry.filename):
                continue
            abi = entry.filename.split("/")[1]
            data = archive.read(entry)
            start = data.index(b"General configuration for OpenCV")
            info = data[start:data.index(b"\0", start)].decode("utf-8", errors="replace")
            exported, alignments = elf_details(data)
            libraries[abi] = {"bytes": entry.file_size, "zip_bytes": entry.compress_size,
                              "elf_alignment": alignments, "info": info,
                              "jni": {name for name in exported if name.startswith("Java_org_opencv_") or name == "JNI_OnLoad"}}
    return class_names, libraries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--profile", choices=("compact", "performance"), default="compact")
    args = parser.parse_args()
    classes, libraries = inspect(args.aar)
    baseline_classes, baseline = inspect(args.baseline)
    failures = []
    if classes != baseline_classes:
        failures.append("Java class surface changed")
    if set(libraries) != {"arm64-v8a", "armeabi-v7a", "x86_64"}:
        failures.append("Expected all three runtime ABIs")
    rows = []
    for abi, library in libraries.items():
        info = library["info"]
        if library["jni"] != baseline[abi]["jni"]:
            failures.append(abi + ": JNI exports changed")
        if not library["elf_alignment"] or min(library["elf_alignment"]) < 16384:
            failures.append(abi + ": missing 16 KB ELF alignment")
        framework = "TBB" if args.profile == "performance" else "pthreads"
        if not re.search(r"Parallel framework:\s+" + framework + r"\b", info):
            failures.append(abi + ": expected " + framework + " threading")
        for field in ("Baseline", "Dispatched code generation"):
            pattern = re.escape(field) + r":\s*([^\n]+)"
            current_cpu = re.search(pattern, info)
            baseline_cpu = re.search(pattern, baseline[abi]["info"])
            if (current_cpu.group(1).strip() if current_cpu else None) != (
                    baseline_cpu.group(1).strip() if baseline_cpu else None):
                failures.append(abi + ": CPU " + field + " changed")
        if args.profile == "performance" and abi == "arm64-v8a" and "KleidiCV" not in info:
            failures.append(abi + ": KleidiCV acceleration missing")
        if abi.startswith("arm") and "carotene" not in info:
            failures.append(abi + ": Carotene acceleration missing")
        if args.profile == "performance" and abi == "x86_64" and not re.search(r"Intel IPP:\s+\S", info):
            failures.append(abi + ": IPP acceleration missing")
        if args.profile == "compact":
            if "KleidiCV" in info or re.search(r"Intel IPP:\s+(?!NO\b)\S", info):
                failures.append(abi + ": compact profile contains an optional acceleration backend")
        flags = next((line for line in info.splitlines() if "C++ flags (Release):" in line), "")
        if "-O3" not in flags or re.search(r"\s-O[szt]\b", flags):
            failures.append(abi + ": expected performance-oriented -O3")
        if not re.search(r"JPEG:\s+build-libjpeg-turbo", info) or not re.search(r"PNG:\s+build", info):
            failures.append(abi + ": PNG/JPEG support missing")
        if re.search(r"^\s*(WEBP|TIFF|JPEG 2000|OpenEXR|AVIF):\s+(?!NO\b|OFF\b)\S", info, re.MULTILINE):
            failures.append(abi + ": unused codec still included")
        modules = re.search(r"To be built:\s+([^\n]+)", info).group(1).split()
        if set(modules) != {"core", "imgproc", "imgcodecs", "video", "videoio", "features2d", "calib3d", "flann", "java"}:
            failures.append(abi + ": module surface changed")
        rows.append({"abi": abi, "bytes": library["bytes"], "zip_bytes": library["zip_bytes"],
                     "baseline_bytes": baseline[abi]["bytes"], "jni_exports": len(library["jni"]),
                     "elf_alignment": library["elf_alignment"],
                     "build_information": [line.strip() for line in info.splitlines()
                                           if re.search(r"Baseline:|Dispatched code|Parallel framework:|Custom HAL:|Intel IPP:|JPEG:|PNG:", line)]})
    print(json.dumps({"aar": str(args.aar), "sha256": hashlib.sha256(args.aar.read_bytes()).hexdigest(),
                      "aar_bytes": args.aar.stat().st_size, "java_classes": len(classes),
                      "native_bytes": sum(row["bytes"] for row in rows), "libraries": rows,
                      "profile": args.profile, "passed": not failures, "failures": failures}, indent=2))
    raise SystemExit(bool(failures))


if __name__ == "__main__":
    main()
