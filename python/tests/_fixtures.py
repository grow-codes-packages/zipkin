"""Recorded responses of the real openzipkin/zipkin:3.6.1 (``tests/data/fixtures``) as mock
replies."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from _support import Reply

FIXTURES = Path(__file__).parent / "data" / "fixtures"


def load(name: str) -> dict[str, Any]:
    """The ``{request, response}`` document of a fixture (``name`` without ``.json``)."""
    data: dict[str, Any] = json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))
    return data


def response_body(name: str) -> Any:
    return load(name)["response"]["body"]


def fixture_reply(name: str) -> Reply:
    """The recorded status, body and content type as a :class:`Reply`."""
    response = load(name)["response"]
    content_type = None
    for header in response.get("headers") or []:
        key, _, value = str(header).partition(":")
        if key.strip().lower() == "content-type":
            content_type = value.strip()
    body = response.get("body")
    if body is None:
        body = b""
    return Reply(int(response["status"]), body, content_type=content_type)
