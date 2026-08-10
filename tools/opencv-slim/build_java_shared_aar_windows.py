"""Run OpenCV's official AAR packager with its Gradle call adapted for Windows."""

from __future__ import annotations

import os
import runpy
import subprocess
import sys
from collections.abc import Sequence
from typing import Any


_subprocess_run = subprocess.run


def _windows_subprocess_run(command: Any, *args: Any, **kwargs: Any) -> Any:
    if (
        os.name == "nt"
        and isinstance(command, Sequence)
        and not isinstance(command, (str, bytes))
        and command
        and command[0] == "./gradlew"
    ):
        command = list(command)
        working_directory = kwargs.get("cwd") or os.getcwd()
        command[0] = os.path.abspath(os.path.join(working_directory, "gradlew.bat"))
    return _subprocess_run(command, *args, **kwargs)


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(
            "Usage: build_java_shared_aar_windows.py <official-script> [arguments...]"
        )

    official_script = os.path.abspath(sys.argv[1])
    if not os.path.isfile(official_script):
        raise SystemExit(f"Official OpenCV AAR script not found: {official_script}")

    subprocess.run = _windows_subprocess_run
    sys.argv = [official_script, *sys.argv[2:]]
    runpy.run_path(official_script, run_name="__main__")


if __name__ == "__main__":
    main()
