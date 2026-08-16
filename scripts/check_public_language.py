#!/usr/bin/env python3

from __future__ import annotations

import pathlib
import re
import subprocess
import sys


CYRILLIC = re.compile(r"[\u0400-\u04ff]")


def public_files() -> list[pathlib.Path]:
    result = subprocess.run(
        [
            "git",
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
        ],
        check=True,
        capture_output=True,
    )
    return [pathlib.Path(path) for path in result.stdout.decode().split("\0") if path]


def cyrillic_lines(path: pathlib.Path) -> list[tuple[int, str]]:
    try:
        content = path.read_bytes()
        if b"\0" in content:
            return []
        text = content.decode("utf-8")
    except (OSError, UnicodeDecodeError):
        return []

    return [
        (line_number, line)
        for line_number, line in enumerate(text.splitlines(), start=1)
        if CYRILLIC.search(line)
    ]


def main() -> int:
    violations = [
        (path, line_number, line)
        for path in public_files()
        for line_number, line in cyrillic_lines(path)
    ]

    if not violations:
        print("Public language check passed.")
        return 0

    print("Cyrillic text was found outside ignored private project notes:", file=sys.stderr)
    for path, line_number, line in violations:
        print(f"{path}:{line_number}: {line}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
