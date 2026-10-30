# SPDX-License-Identifier: Apache-2.0
"""Read-only terminal monitor. Closing it never stops a training worker."""
import argparse
import ctypes
import os
from pathlib import Path
import sys
import time

from monitor_view import render, snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--consumer", choices=["dust", "metering"], required=True)
    parser.add_argument("--once", action="store_true")
    arguments = parser.parse_args()
    if os.name == "nt":
        kernel = ctypes.windll.kernel32
        handle = kernel.GetStdHandle(-11)
        mode = ctypes.c_uint()
        if kernel.GetConsoleMode(handle, ctypes.byref(mode)):
            kernel.SetConsoleMode(handle, mode.value | 4)
        kernel.SetConsoleTitleW("Shared Training - " + arguments.consumer.upper())
    try:
        while True:
            if not arguments.once and sys.stdout.isatty():
                print("\033[H\033[J", end="")
            print(render(snapshot(arguments.root, arguments.consumer)), flush=True)
            if arguments.once:
                return
            time.sleep(3)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
