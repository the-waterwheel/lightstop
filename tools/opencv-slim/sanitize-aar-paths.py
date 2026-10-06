"""Redact Windows home paths only in ELF read-only diagnostic strings.

Replacement preserves length; code, offsets, relocations and exported symbols
are unchanged. Unknown sections fail closed. Also covers duplicate Prefab libs.
"""

import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

HOME = re.compile(rb"[A-Za-z]:[/\\]Users[/\\][^/\\\x00\r\n]+(?=[/\\])", re.I)


def rodata_range(data):
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Expected little-endian ELF")
    wide = data[4] == 2
    word = "Q" if wide else "I"
    table = struct.unpack_from("<" + word, data, 40 if wide else 32)[0]
    size, count, names_index = struct.unpack_from("<HHH", data, 58 if wide else 46)
    sections = []
    for i in range(count):
        start = table + i * size
        sections.append((struct.unpack_from("<I", data, start)[0],
                         struct.unpack_from("<" + word, data, start + (24 if wide else 16))[0],
                         struct.unpack_from("<" + word, data, start + (32 if wide else 20))[0]))
    _, offset, length = sections[names_index]
    names = data[offset:offset + length]
    for name, offset, length in sections:
        if names[name:names.index(0, name)] == b".rodata":
            return offset, offset + length
    raise ValueError("Missing .rodata")


def sanitize(data):
    matches = list(HOME.finditer(data))
    if not matches:
        return data, 0
    lower, upper = rodata_range(data)
    result = bytearray(data)
    for match in matches:
        if not lower <= match.start() < match.end() <= upper:
            raise ValueError("Private path outside .rodata; rebuild instead")
        start = max(lower, data.rfind(b"\0", lower, match.start()) + 1)
        end = data.find(b"\0", match.end(), upper)
        if end < 0 or any(byte < 32 or byte > 126 for byte in data[start:end]):
            raise ValueError("Path is not an ASCII diagnostic string; rebuild instead")
        replacement = b"<HOME>".ljust(match.end() - match.start(), b"_")
        if len(replacement) != match.end() - match.start():
            raise ValueError("Cannot preserve diagnostic string length")
        result[match.start():match.end()] = replacement
    if HOME.search(result) or len(result) != len(data):
        raise ValueError("Incomplete redaction")
    return bytes(result), len(matches)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.destination.resolve():
        parser.error("Use a separate output file")
    entries = []
    changes = {}
    with zipfile.ZipFile(args.source) as archive:
        for info in archive.infolist():
            data = archive.read(info)
            if info.filename.endswith(".so"):
                data, count = sanitize(data)
                if count:
                    changes[info.filename] = count
            elif HOME.search(data):
                raise ValueError("Private path in non-ELF entry; rebuild instead")
            entries.append((info, data))
    with zipfile.ZipFile(args.destination, "w") as archive:
        for info, data in entries:
            archive.writestr(info, data)
    print(json.dumps({"redacted_strings": changes,
                      "sha256": hashlib.sha256(args.destination.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
