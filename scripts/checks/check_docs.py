#!/usr/bin/env python3
"""Check Markdown documentation for structural and hygiene problems.

Validates relative link targets, table column consistency, paired code fences,
local absolute paths, and obvious inline credential assignments.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
DOC_FILES = [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]

FENCE = re.compile(r"^\s{0,3}(`{3,})")
LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
INLINE_CODE = re.compile(r"(`+)(.+?)\1")
WINDOWS_ABSOLUTE = re.compile(r"(?<![A-Za-z0-9_])[A-Za-z]:[\\/]")
USER_HOME = re.compile(r"(?<![A-Za-z0-9_.-])/Users/[A-Za-z0-9_.-]+")
CREDENTIAL = re.compile(
    r"(?i)(?:password|passwd|pwd|secret|token|api[_-]?key)\s*[:=]\s*"
    r"(?P<value>\"[^\"]*\"|'[^']*'|[^\s`]+)"
)
SKIP_PREFIXES = (
    "http://",
    "https://",
    "mailto:",
    "tel:",
    "data:",
    "//",
    "/",
    "#",
)
PLACEHOLDER_VALUES = {
    "...",
    "***",
    "xxx",
    "redacted",
    "changeme",
    "placeholder",
    "example",
}


def strip_inline_code(line: str) -> str:
    return INLINE_CODE.sub("", line)


def link_destination(raw: str) -> str:
    destination = raw.strip()
    if destination.startswith("<") and ">" in destination:
        destination = destination[1 : destination.index(">")]
    else:
        parts = destination.split()
        if parts:
            destination = parts[0]
    return destination


def is_placeholder(value: str) -> bool:
    cleaned = value.strip("\"'")
    lowered = cleaned.lower()
    if len(cleaned) < 6:
        return True
    if cleaned[0] in "$<%{":
        return True
    if lowered in PLACEHOLDER_VALUES:
        return True
    if lowered.startswith(("your", "example", "placeholder", "replace")):
        return True
    if re.fullmatch(r"[.x*]+", lowered):
        return True
    return False


def main() -> int:
    errors = []
    link_count = 0
    table_count = 0

    for path in DOC_FILES:
        if not path.is_file():
            errors.append(f"{path}: expected documentation file is missing")
            continue
        relative = path.relative_to(ROOT)
        lines = path.read_text(encoding="utf-8").splitlines()

        in_fence = False
        fence_line = 0
        table_start = 0
        table_pipes = 0

        for number, line in enumerate(lines, start=1):
            fence = FENCE.match(line)
            if fence:
                if in_fence:
                    in_fence = False
                    fence_line = 0
                else:
                    in_fence = True
                    fence_line = number
                table_start = 0
                continue

            if in_fence:
                continue

            stripped = strip_inline_code(line)

            for match in LINK.finditer(stripped):
                destination = link_destination(match.group(1))
                if not destination or destination.startswith(SKIP_PREFIXES):
                    continue
                link_count += 1
                target = destination.split("#", 1)[0].split("?", 1)[0]
                if not target:
                    continue
                target = unquote(target)
                resolved = (path.parent / target).resolve()
                if not resolved.exists():
                    errors.append(
                        f"{relative}:{number}: relative link target does not "
                        f"exist: {destination}"
                    )

            if line.lstrip().startswith("|"):
                pipes = stripped.replace("\\|", "").count("|")
                if table_start == 0:
                    table_start = number
                    table_pipes = pipes
                    table_count += 1
                elif pipes != table_pipes:
                    errors.append(
                        f"{relative}:{number}: table has {pipes} pipe(s), "
                        f"expected {table_pipes} (table starts at line "
                        f"{table_start})"
                    )
            else:
                table_start = 0

            for match in WINDOWS_ABSOLUTE.finditer(line):
                errors.append(
                    f"{relative}:{number}: local absolute path is not allowed: "
                    f"{match.group(0)}"
                )
            if USER_HOME.search(line):
                errors.append(
                    f"{relative}:{number}: local user home path is not allowed"
                )
            credential = CREDENTIAL.search(line)
            if credential and not is_placeholder(credential.group("value")):
                errors.append(
                    f"{relative}:{number}: possible inline credential "
                    "assignment"
                )

        if in_fence:
            errors.append(
                f"{relative}:{fence_line}: code fence is never closed"
            )

    if errors:
        for error in errors:
            print(error)
        print(f"FAILED: {len(errors)} documentation error(s)")
        return 1

    print(
        f"OK: {len(DOC_FILES)} Markdown file(s), {link_count} relative link(s), "
        f"{table_count} table(s); fences, paths and credentials clean"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
