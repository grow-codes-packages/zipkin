"""Shared pytest fixtures."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from _support import MockServer


@pytest.fixture
def server() -> Iterator[MockServer]:
    srv = MockServer()
    yield srv
    srv.stop()
