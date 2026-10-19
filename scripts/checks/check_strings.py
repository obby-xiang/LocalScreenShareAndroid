#!/usr/bin/env python3
"""Validate Android string resources across the default and Chinese locales.

Checks that both files parse as XML, contain no duplicate keys, expose the same
key set, and use identical numbered placeholders per key. Exits non-zero and
prints file:line diagnostics when a check fails.
"""

from __future__ import annotations

import collections
import re
import sys
import xml.parsers.expat
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_STRINGS = ROOT / "app/src/main/res/values/strings.xml"
CHINESE_STRINGS = ROOT / "app/src/main/res/values-zh/strings.xml"

NUMBERED_PLACEHOLDER = re.compile(r"%(\d+)\$([sd])")
ANY_PERCENT = re.compile(r"%(?!%)")


def parse_strings(path: Path):
    """Return ({name: (line, text)}, [duplicate diagnostics]) using expat."""
    entries = {}
    duplicates = []
    state = {"current": None}

    parser = xml.parsers.expat.ParserCreate()
    parser.buffer_text = True

    def start_element(name, attrs):
        if name == "string":
            state["current"] = {
                "name": attrs.get("name"),
                "line": parser.CurrentLineNumber,
                "text": [],
            }

    def end_element(name):
        if name == "string" and state["current"] is not None:
            current = state["current"]
            key = current["name"]
            if key is None:
                duplicates.append(
                    f"{path}:{current['line']}: <string> without a name attribute"
                )
            elif key in entries:
                duplicates.append(
                    f"{path}:{current['line']}: duplicate string key '{key}' "
                    f"(first defined at line {entries[key][0]})"
                )
            else:
                entries[key] = (current["line"], "".join(current["text"]))
            state["current"] = None

    def char_data(data):
        if state["current"] is not None:
            state["current"]["text"].append(data)

    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element
    parser.CharacterDataHandler = char_data

    try:
        with path.open("rb") as handle:
            parser.ParseFile(handle)
    except xml.parsers.expat.ExpatError as error:
        return None, [f"{path}:{error.lineno}: XML parse error: {error}"]

    return entries, duplicates


def placeholders(text: str):
    """Return a Counter of numbered placeholders plus unsupported syntax flags."""
    matches = collections.Counter(
        f"{index}:{kind}" for index, kind in NUMBERED_PLACEHOLDER.findall(text)
    )
    remaining = NUMBERED_PLACEHOLDER.sub("", text.replace("%%", ""))
    unsupported = bool(ANY_PERCENT.search(remaining))
    return matches, unsupported


def main() -> int:
    errors = []
    parsed = {}

    for path in (DEFAULT_STRINGS, CHINESE_STRINGS):
        entries, problems = parse_strings(path)
        errors.extend(problems)
        if entries is not None:
            parsed[path] = entries

    if len(parsed) != 2:
        for error in errors:
            print(error)
        print(f"FAILED: {len(errors)} error(s)")
        return 1

    english = parsed[DEFAULT_STRINGS]
    chinese = parsed[CHINESE_STRINGS]

    for key in sorted(set(english) - set(chinese)):
        errors.append(
            f"{DEFAULT_STRINGS}:{english[key][0]}: key '{key}' is missing from "
            f"{CHINESE_STRINGS.relative_to(ROOT)}"
        )
    for key in sorted(set(chinese) - set(english)):
        errors.append(
            f"{CHINESE_STRINGS}:{chinese[key][0]}: key '{key}' is missing from "
            f"{DEFAULT_STRINGS.relative_to(ROOT)}"
        )

    for key in sorted(set(english) & set(chinese)):
        expected, expected_unsupported = placeholders(english[key][1])
        actual, actual_unsupported = placeholders(chinese[key][1])
        english_line = english[key][0]
        chinese_line = chinese[key][0]
        if expected_unsupported:
            errors.append(
                f"{DEFAULT_STRINGS}:{english_line}: key '{key}' contains an "
                "unsupported placeholder (expected %N$s or %N$d)"
            )
        if actual_unsupported:
            errors.append(
                f"{CHINESE_STRINGS}:{chinese_line}: key '{key}' contains an "
                "unsupported placeholder (expected %N$s or %N$d)"
            )
        if expected != actual:
            errors.append(
                f"{CHINESE_STRINGS}:{chinese_line}: key '{key}' placeholders "
                f"{dict(actual)} do not match {DEFAULT_STRINGS.relative_to(ROOT)}:"
                f"{english_line} {dict(expected)}"
            )

    if errors:
        for error in errors:
            print(error)
        print(f"FAILED: {len(errors)} error(s)")
        return 1

    print(
        f"OK: {len(english)} string keys in both locales; "
        "keys and placeholders match"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
