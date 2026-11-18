#!/usr/bin/env python3
"""Licence allowlist check over ``pip-licenses --format=json`` output (SPEC section 7).

Usage: pip-licenses --python <venv python> --from=mixed --format=json | licensecheck.py

A package passes when its licence expression is allowed: ``A OR B`` needs one allowed
alternative, ``A AND B`` needs all parts, ``;``-separated trove classifiers count as
alternatives. Exits 1 and lists the offending packages otherwise.
"""

from __future__ import annotations

import json
import re
import sys

ALLOWED = frozenset(
    {
        # SPDX identifiers from the SPEC allowlist
        "apache-2.0",
        "mit",
        "bsd-2-clause",
        "bsd-3-clause",
        "isc",
        "zlib",
        "unicode-3.0",
        "unicode-dfs-2016",
        "0bsd",
        "cc0-1.0",
        "mpl-2.0",
        # trove classifier spellings of the same licences
        "apache software license",
        "apache license 2.0",
        "apache license, version 2.0",
        "mit license",
        "bsd license",
        "bsd",
        "3-clause bsd license",
        "isc license (iscl)",
        "mozilla public license 2.0 (mpl 2.0)",
        # typing_extensions (permissive; reported to the spec owner, see Makefile)
        "psf-2.0",
        "python software foundation license",
    }
)


def allowed(expression: str) -> bool:
    text = expression.strip()
    if text.startswith("(") and text.endswith(")"):
        text = text[1:-1].strip()
    if not text:
        return False
    alternatives = re.split(r"\s+OR\s+|;\s*", text)
    if len(alternatives) > 1:
        return any(allowed(a) for a in alternatives)
    parts = re.split(r"\s+AND\s+", text)
    if len(parts) > 1:
        return all(allowed(p) for p in parts)
    return text.lower() in ALLOWED


def main() -> int:
    packages = json.load(sys.stdin)
    bad = [p for p in packages if not allowed(str(p.get("License", "")))]
    for p in packages:
        print(f"{p['Name']:<40} {p['Version']:<14} {p['License']}")
    if bad:
        for p in bad:
            print(f"NOT ALLOWED: {p['Name']} {p['Version']}: {p['License']}", file=sys.stderr)
        return 1
    print(f"licence check passed ({len(packages)} runtime packages)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
