#!/usr/bin/env python3
"""Coverage gate: checks line and branch coverage separately from a coverage.py JSON report.

Usage: covergate.py coverage.json summary.json --lines 90 --branches 90

Writes the machine-readable summary shared by every language
(``{"lines": {...}, "branches": {...}}``) and exits 1 when a gate fails.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def _pct(covered: int, total: int) -> float:
    return 100.0 if total == 0 else round(covered * 100.0 / total, 2)


def summarize(report: dict[str, Any]) -> dict[str, Any]:
    totals = report["totals"]
    lines_total = int(totals["num_statements"])
    lines_covered = int(totals["covered_lines"])
    summary: dict[str, Any] = {
        "lines": {
            "covered": lines_covered,
            "total": lines_total,
            "pct": _pct(lines_covered, lines_total),
        },
        "branches": None,
    }
    if "num_branches" in totals:
        branches_total = int(totals["num_branches"])
        branches_covered = int(totals["covered_branches"])
        summary["branches"] = {
            "covered": branches_covered,
            "total": branches_total,
            "pct": _pct(branches_covered, branches_total),
        }
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("summary", type=Path)
    parser.add_argument("--lines", type=float, default=90.0)
    parser.add_argument("--branches", type=float, default=90.0)
    args = parser.parse_args(argv)

    summary = summarize(json.loads(args.report.read_text(encoding="utf-8")))
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    failures: list[str] = []
    lines = summary["lines"]
    print(
        f"lines:    {lines['pct']:.2f}% ({lines['covered']}/{lines['total']}), gate {args.lines}%"
    )
    if lines["pct"] < args.lines:
        failures.append("lines")
    branches = summary["branches"]
    if branches is None:
        print("branches: not measured (run pytest with --cov-branch)")
        failures.append("branches (not measured)")
    else:
        print(
            f"branches: {branches['pct']:.2f}% ({branches['covered']}/{branches['total']}), "
            f"gate {args.branches}%"
        )
        if branches["pct"] < args.branches:
            failures.append("branches")
    if failures:
        print("coverage gate FAILED: " + ", ".join(failures), file=sys.stderr)
        return 1
    print("coverage gate passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
