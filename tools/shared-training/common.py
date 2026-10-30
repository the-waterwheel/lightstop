# SPDX-License-Identifier: Apache-2.0
"""Independent shared-data coordination; model implementations remain separate."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time
import uuid


def stamp():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".{os.getpid()}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    for attempt in range(5):
        try:
            os.replace(temporary, path)
            break
        except PermissionError:
            if attempt == 4:
                raise
            time.sleep(0.02 * (attempt + 1))


@contextmanager
def gpu_slot(root, consumer):
    """All managed GPU workers take this one OS lock; no stale-PID killing."""
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    path = root / "gpu0.lock"
    handle = path.open("a+b")
    # Windows denies reading a byte locked by the current GPU owner. Inspect
    # file size instead; initialization races may append a harmless extra byte.
    if path.stat().st_size == 0:
        handle.write(b"0")
        handle.flush()
    handle.seek(0)
    if os.name == "nt":
        import msvcrt
        while True:
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                time.sleep(1)
    else:
        import fcntl
        fcntl.flock(handle, fcntl.LOCK_EX)
    write_json(root / "gpu-owner.json", dict(consumer=consumer, pid=os.getpid(), state="running", since=stamp()))
    try:
        yield
    finally:
        write_json(root / "gpu-owner.json", dict(consumer=None, state="idle", updated_at=stamp()))
        handle.close()


def update(root, consumer, **values):
    path = Path(root) / f"{consumer}-status.json"
    current = read_json(path) if path.exists() else {}
    current.update(values)
    current.update(consumer=consumer, updated_at=stamp())
    write_json(path, current)
