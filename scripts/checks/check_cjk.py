#!/usr/bin/env python3
"""Reject CJK characters in Java sources and default resources.

Only the untranslated resource directory (values/) and main Java sources are
checked; values-zh/ is expected to contain Chinese text.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGETS = (
    ROOT / "app/src/main/java",
    ROOT / "app/src/main/res/values",
)
CJK = re.compile("[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def iter_files(directory: Path):
    if not directory.is_dir():
        return
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            yield path


def main() -> int:
    errors = []
    checked = 0

    for directory in TARGETS:
        if not directory.is_dir():
            errors.append(f"{directory}: expected directory is missing")
            continue
        for path in iter_files(directory):
            checked += 1
            try:
                lines = path.read_text(encoding="utf-8").splitlines()
            except UnicodeDecodeError as error:
                errors.append(f"{path}: cannot decode as UTF-8: {error}")
                continue
            relative = path.relative_to(ROOT)
            for line_number, line in enumerate(lines, start=1):
                for match in CJK.finditer(line):
                    character = match.group(0)
                    column = match.start() + 1
                    errors.append(
                        f"{relative}:{line_number}:{column}: CJK character "
                        f"U+{ord(character):04X} '{character}'"
                    )

    if errors:
        for error in errors:
            print(error)
        print(f"FAILED: {len(errors)} CJK character(s)")
        return 1

    print(f"OK: no CJK characters in {checked} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
