"""Core round trips against the real service plus the core conformance scenarios."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest
from factories import CORE_CAPABILITIES, async_core_client, core_client

import grow_zipkin as sdk
from grow_zipkin.testing import ConformanceRunner, assert_scenario, pytest_params


def test_health(client: sdk.ZipkinClient) -> None:
    status = client.health()
    assert status.healthy, status


def test_ping(client: sdk.ZipkinClient) -> None:
    assert client.ping() >= 0


def test_server_version(client: sdk.ZipkinClient) -> None:
    if "version" not in CORE_CAPABILITIES:
        with pytest.raises(sdk.UnsupportedError):
            client.server_version()
        return
    assert client.server_version()


def test_close_is_idempotent(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()


def test_async_client(env: dict[str, str]) -> None:
    async def run() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() >= 0

    asyncio.run(run())


def test_bad_token_is_rejected(env: dict[str, str]) -> None:
    with core_client({**env, "GROW_ZIPKIN_TOKEN": "definitely-wrong-token"}) as c:
        with pytest.raises(sdk.AuthenticationError) as info:
            c.ping()
        assert "definitely-wrong-token" not in str(info.value)


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)
