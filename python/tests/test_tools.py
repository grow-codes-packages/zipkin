"""The repository tools used by the Makefile (coverage gate, licence check)."""

from __future__ import annotations

import io
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

import covergate
import licensecheck


def _report(tmp_path: Path, lines: tuple[int, int], branches: tuple[int, int] | None) -> Path:
    totals = {"num_statements": lines[1], "covered_lines": lines[0]}
    if branches is not None:
        totals |= {"num_branches": branches[1], "covered_branches": branches[0]}
    path = tmp_path / "coverage.json"
    path.write_text(json.dumps({"totals": totals}))
    return path


def test_covergate(tmp_path: Path) -> None:
    summary = tmp_path / "out" / "summary.json"
    assert covergate.main([str(_report(tmp_path, (95, 100), (9, 10))), str(summary)]) == 0
    assert json.loads(summary.read_text()) == {
        "lines": {"covered": 95, "total": 100, "pct": 95.0},
        "branches": {"covered": 9, "total": 10, "pct": 90.0},
    }
    assert covergate.main([str(_report(tmp_path, (80, 100), (1, 10))), str(summary)]) == 1
    assert covergate.main([str(_report(tmp_path, (100, 100), None)), str(summary)]) == 1
    assert covergate.summarize({"totals": {"num_statements": 0, "covered_lines": 0}}) == {
        "lines": {"covered": 0, "total": 0, "pct": 100.0},
        "branches": None,
    }


@pytest.mark.parametrize(
    ("expression", "ok"),
    [
        ("MIT", True),
        ("MIT OR Apache-2.0", True),
        ("(Apache-2.0 OR BSD-3-Clause)", True),
        ("Apache-2.0 AND MIT", True),
        ("BSD License; MIT License", True),
        ("Mozilla Public License 2.0 (MPL 2.0)", True),
        ("ISC License (ISCL)", True),
        ("3-Clause BSD License", True),
        ("PSF-2.0", True),
        ("GPL-3.0-only", False),
        ("MIT AND GPL-2.0", False),
        ("LGPL-2.1 OR GPL-2.0", False),
        ("UNKNOWN", False),
        ("", False),
    ],
)
def test_licence_expressions(expression: str, ok: bool) -> None:
    assert licensecheck.allowed(expression) is ok


def test_licensecheck_main(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    good = [{"Name": "httpx", "Version": "0.28.1", "License": "BSD-3-Clause"}]
    monkeypatch.setattr(sys, "stdin", io.StringIO(json.dumps(good)))
    assert licensecheck.main() == 0
    bad = [*good, {"Name": "gpl-thing", "Version": "1", "License": "GPL-3.0"}]
    monkeypatch.setattr(sys, "stdin", io.StringIO(json.dumps(bad)))
    assert licensecheck.main() == 1
    assert "gpl-thing" in capsys.readouterr().err
