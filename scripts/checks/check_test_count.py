#!/usr/bin/env python3
"""Guard the JVM unit test inventory against the recorded baseline.

Aggregates the JUnit XML reports written by the Gradle unit test tasks and
fails when any test did not pass or when the inventory shrank below the
baseline committed in test_baseline.json. Growing the inventory is allowed;
the baseline must then be updated in the same commit.
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RESULTS = ROOT / "app/build/test-results/testDebugUnitTest"
DEFAULT_BASELINE = ROOT / "scripts/checks/test_baseline.json"


def parse_args() -> argparse.Namespace:
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Compare JVM unit test counts with scripts/checks/test_baseline.json"
    )
    parser.add_argument(
        "--results",
        nargs="+",
        default=[str(DEFAULT_RESULTS)],
        metavar="DIR",
        help=(
            "report directory containing TEST-*.xml files; pass several "
            f"directories to also require matching totals (default: {DEFAULT_RESULTS})"
        ),
    )
    parser.add_argument(
        "--baseline",
        default=str(DEFAULT_BASELINE),
        metavar="FILE",
        help=f"baseline JSON file (default: {DEFAULT_BASELINE})",
    )
    return parser.parse_args()


def summarize(directory: Path) -> dict:
    """Return {tests, failures, errors, skipped, classes} for a report directory."""
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    class_names = set()

    reports = sorted(directory.rglob("TEST-*.xml"))
    for report in reports:
        try:
            root = ElementTree.parse(report).getroot()
        except ElementTree.ParseError as error:
            raise RuntimeError(f"{report}: XML parse error: {error}") from error
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            class_names.add(suite.get("name") or report.stem)
            for key in totals:
                value = suite.get(key)
                if value is None:
                    continue
                try:
                    totals[key] += int(value)
                except ValueError as error:
                    raise RuntimeError(
                        f"{report}: <testsuite> attribute '{key}' is not an integer: {value}"
                    ) from error

    totals["classes"] = len(class_names)
    totals["reports"] = len(reports)
    return totals


def display(path: Path) -> str:
    """Return a repository-relative POSIX form when possible."""
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def load_baseline(path: Path) -> dict:
    """Load and validate the baseline file."""
    if not path.is_file():
        raise RuntimeError(f"{path}: baseline file is missing")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{path}: invalid JSON: {error}") from error
    for key in ("tests", "classes"):
        value = data.get(key)
        if not isinstance(value, int) or value < 0:
            raise RuntimeError(f"{path}: '{key}' must be a non-negative integer")
    return data


def main() -> int:
    args = parse_args()
    baseline_path = Path(args.baseline)

    try:
        baseline = load_baseline(baseline_path)
    except RuntimeError as error:
        print(error)
        print("FAILED: cannot read the test baseline")
        return 1

    summaries = []
    for raw in args.results:
        directory = Path(raw)
        if not directory.is_dir():
            print(f"{directory}: report directory does not exist; run the unit test task first")
            return 1
        try:
            summary = summarize(directory)
        except RuntimeError as error:
            print(error)
            return 1
        if summary["reports"] == 0:
            print(f"{directory}: no TEST-*.xml report found")
            return 1
        summaries.append((directory, summary))

    current = summaries[0][1]
    variant_errors = []
    run_errors = []

    if len(summaries) > 1:
        for directory, summary in summaries:
            if (summary["tests"], summary["classes"]) != (
                current["tests"],
                current["classes"],
            ):
                variant_errors.append(
                    f"{directory}: {summary['tests']} tests / {summary['classes']} "
                    "classes differ from the first report directory "
                    f"({current['tests']} tests / {current['classes']} classes)"
                )

    for key in ("failures", "errors", "skipped"):
        if current[key]:
            run_errors.append(f"{current[key]} test(s) reported '{key}'")

    if variant_errors:
        for error in variant_errors:
            print(error)
        print("FAILED: report directories disagree on the test inventory")
        return 1

    if run_errors:
        for error in run_errors:
            print(error)
        print("FAILED: unit test run is not clean")
        return 1

    for key in ("tests", "classes"):
        if current[key] < baseline[key]:
            print(
                f"{key}: {current[key]} < baseline {baseline[key]} "
                f"(-{baseline[key] - current[key]})"
            )
            print(
                "FAILED: test inventory shrank; restore the removed tests or "
                f"update {display(baseline_path)} in the same commit"
            )
            return 1

    print(
        f"OK: {current['tests']} tests / {current['classes']} classes, "
        "0 failures / 0 errors / 0 skipped "
        f"(baseline {baseline['tests']} / {baseline['classes']})"
    )
    if (
        current["tests"] > baseline["tests"]
        or current["classes"] > baseline["classes"]
    ):
        print(
            "NOTE: the inventory grew; update "
            f"{display(baseline_path)} in the same commit "
            f"(tests {current['tests']}, classes {current['classes']})"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
