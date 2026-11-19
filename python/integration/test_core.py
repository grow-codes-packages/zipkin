"""Core round trips against the real Zipkin plus the core conformance scenarios."""

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
    assert status.status == "UP"
    assert "InMemoryStorage" in status.details["storage"]


def test_ping(client: sdk.ZipkinClient) -> None:
    assert client.ping() >= 0


def test_server_version_and_info(client: sdk.ZipkinClient, env: dict[str, str]) -> None:
    expected = env.get("ZIPKIN_VERSION", "3.6.1")
    assert client.server_version() == expected
    info = client.server_info()
    assert info.version == expected
    assert info.commit
    assert all(c in "0123456789abcdef" for c in info.commit)


def test_close_is_idempotent(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.ping()


def test_from_env_endpoint_and_gateway_headers(env: dict[str, str]) -> None:
    config = sdk.ZipkinConfig.from_env(env, headers={"Authorization": "Bearer gateway-token-xyz"})
    assert config.endpoint == config.url.rstrip("/") + "/api/v2/spans"
    assert "gateway-token-xyz" not in repr(config)
    with sdk.ZipkinClient(config) as c:
        # Stock Zipkin ignores the header; through a gateway it would authenticate the call.
        assert c.ping() >= 0


def test_async_client(env: dict[str, str]) -> None:
    async def run() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() >= 0
            assert await c.server_version() == env.get("ZIPKIN_VERSION", "3.6.1")
            assert (await c.server_info()).version

    asyncio.run(run())


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)
