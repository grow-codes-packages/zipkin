"""ZipkinClient / AsyncZipkinClient: health, ping, server version, close, sub-clients and
init_tracing wiring; plus the core polling helpers."""

from __future__ import annotations

import asyncio
import time
from typing import Any

import pytest

import grow_zipkin as sdk
from _fixtures import fixture_reply
from _support import MockServer, Reply, closed_port_url, make_config, spawn_capturing

TOKEN = "client-token-789"


def client(url: str, **kw: Any) -> sdk.ZipkinClient:
    return sdk.ZipkinClient(make_config(url, **kw))


def aclient(url: str, **kw: Any) -> sdk.AsyncZipkinClient:
    return sdk.AsyncZipkinClient(make_config(url, **kw))


# --------------------------------------------------------------------------- sync


def test_health_ping_version_from_fixtures(server: MockServer) -> None:
    server.enqueue("/health", fixture_reply("health.ok"))
    server.enqueue("/api/v2/services", fixture_reply("listServices.ok"))
    server.enqueue("/info", fixture_reply("serverVersion.ok"), fixture_reply("serverVersion.ok"))
    with client(server.url, token=TOKEN) as c:
        health = c.health()
        assert health.healthy and health.status == "UP"
        assert health.details["zipkin"] == "UP"
        assert "InMemoryStorage" in health.details["storage"]
        latency = c.ping()
        assert isinstance(latency, float) and latency >= 0
        assert c.server_version() == "3.6.1"
        assert c.server_info() == sdk.ZipkinServerInfo("3.6.1", "b2b0c98")
        assert TOKEN not in repr(c)
    ping = next(s for s in server.seen if s.path == "/api/v2/services")
    assert ping.headers["Authorization"] == f"Bearer {TOKEN}"
    assert ping.headers["User-Agent"].startswith("grow-codes-zipkin/")


def test_health_variants(server: MockServer) -> None:
    c = client(server.url)
    server.enqueue("/health", Reply(503, {"status": "DOWN"}))
    assert c.health() == sdk.HealthStatus(False, "DOWN", {"httpStatus": "503"})
    server.enqueue("/health", Reply(500, "boom", content_type="text/*"))
    assert c.health() == sdk.HealthStatus(
        False, "unhealthy", {"error": "UNAVAILABLE", "httpStatus": "500"}
    )
    server.enqueue("/health", Reply(200, "OK"))
    assert c.health() == sdk.HealthStatus(True, "UP", {})
    server.enqueue("/health", Reply(200, [1, 2]))
    assert c.health().healthy
    server.enqueue("/health", Reply(200, b""))
    assert c.health().healthy
    server.enqueue("/health", Reply(200, {"status": "DOWN", "zipkin": {"status": 1}}))
    assert c.health() == sdk.HealthStatus(False, "DOWN", {})
    assert len([s for s in server.seen if s.path == "/health"]) == 6  # health never retries


def test_core_health_unreachable() -> None:
    """core.health.unreachable"""
    c = client(closed_port_url())
    status = c.health()
    assert status.healthy is False
    assert status.status == "unreachable"
    assert status.details == {"error": "UNAVAILABLE"}


def test_health_timeout_is_unreachable(server: MockServer) -> None:
    server.enqueue("/health", Reply(200, delay=1.0))
    c = client(server.url, request_timeout=0.1)
    assert c.health().status == "unreachable"


def test_health_raises_only_cancelled_and_config(server: MockServer) -> None:
    c = client(server.url)
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.health()
    token = sdk.CancelToken()
    token.cancel()
    with pytest.raises(sdk.CancelledError):
        client(server.url).health(cancel=token)
    bad = sdk.ZipkinClient.__new__(sdk.ZipkinClient)
    bad.config = sdk.ZipkinConfig(url="ftp://svc", endpoint="http://svc/api/v2/spans")
    bad._transport = sdk.HttpTransport(bad.config)
    with pytest.raises(sdk.ConfigError):
        bad.health()


def test_ping_errors_carry_the_text_body(server: MockServer) -> None:
    server.enqueue("/api/v2/services", Reply(401, "gateway says no", content_type="text/*"))
    with pytest.raises(sdk.AuthenticationError) as info:
        client(server.url).ping()
    assert "gateway says no" in str(info.value)
    server.enqueue("/api/v2/services", Reply(403, "forbidden " + TOKEN, content_type="text/*"))
    with pytest.raises(sdk.PermissionDeniedError) as denied:
        client(server.url, token=TOKEN).ping()
    assert TOKEN not in str(denied.value)


def test_server_version_errors(server: MockServer) -> None:
    c = client(server.url)
    server.enqueue("/info", Reply(404, "404 Not Found", content_type="text/*"))
    with pytest.raises(sdk.UnsupportedError):
        c.server_version()
    server.enqueue("/info", Reply(200, {"nope": 1}))
    with pytest.raises(sdk.UnsupportedError):
        c.server_version()
    server.enqueue("/info", Reply(200, {"zipkin": {"version": ""}}))
    assert c.server_info() == sdk.ZipkinServerInfo()
    server.enqueue("/info", Reply(403))
    with pytest.raises(sdk.PermissionDeniedError):
        c.server_version()
    server.enqueue("/info", Reply(200, "not json", content_type="application/json"))
    with pytest.raises(sdk.ValidationError):
        c.server_version()


def test_core_close_idempotent(server: MockServer) -> None:
    """core.close.idempotent"""
    c = client(server.url)
    stopped: list[int] = []

    class Res:
        def stop(self) -> None:
            stopped.append(1)

    c.resources.register(Res())
    c.close()
    c.close()
    assert stopped == [1]
    c.resources.register(Res())  # registering after close stops immediately
    assert stopped == [1, 1]
    with pytest.raises(sdk.ConfigError):
        c.init_tracing(service_name="late")


def test_from_env_and_raw_request(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    env = {sdk.ENV_PREFIX + "URL": server.url}
    with sdk.ZipkinClient.from_env(env) as c:
        assert c.config.url == server.url
        assert c.config.endpoint == server.url + "/api/v2/spans"
        resp = c.request(sdk.Request("GET", "/anything"))
        assert resp.status == 200
        server.enqueue("/bad", Reply(400, "limit <= 0", content_type="text/*"))
        with pytest.raises(sdk.ValidationError, match="limit <= 0"):
            c.request(sdk.Request("GET", "/bad"))
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    with sdk.ZipkinClient() as c2:
        assert c2.config.url == server.url
    with pytest.raises(sdk.ConfigError):
        sdk.ZipkinClient.from_env({sdk.ENV_PREFIX + "URL": "nope://x"})


def test_sub_clients_share_the_transport(server: MockServer) -> None:
    server.defaults["/api/v2/services"] = Reply(200, ["b", "a"])
    server.defaults["/api/v2/spans"] = Reply(202, b"")
    c = client(server.url)
    assert c.query is c.query
    assert c.reporter is c.reporter
    assert c.query.list_services() == ["a", "b"]
    c.reporter.report([{"traceId": "a" * 16, "id": "b" * 16}])
    gz = c.create_reporter(compression="gzip", max_batch_size=1)
    assert gz.compression == "gzip"
    gz.report([{"traceId": "a" * 16, "id": "b" * 16}])
    assert server.seen[-1].headers["Content-Encoding"] == "gzip"
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.query.list_services()


def test_client_init_tracing_uses_the_client_endpoint(server: MockServer) -> None:
    server.defaults["/gw/api/v2/spans"] = Reply(202, b"")
    c = client(server.url + "/gw", token=TOKEN)
    handle = c.init_tracing(service_name="svc-a", set_global=False, env={})
    assert handle.endpoint == server.url + "/gw/api/v2/spans"
    assert handle.config.protocol == "native"
    assert list(handle.config.propagators) == ["b3", "tracecontext", "baggage"]
    with handle.tracer("t").start_as_current_span("op"):
        pass
    handle.force_flush()
    sent = [s for s in server.seen if s.path == "/gw/api/v2/spans"]
    assert sent and sent[0].headers["Authorization"] == f"Bearer {TOKEN}"
    explicit = c.init_tracing(
        sdk.TracingConfig("svc-b", endpoint="http://elsewhere:9411/api/v2/spans", set_global=False),
        env=False,
    )
    assert explicit.endpoint == "http://elsewhere:9411/api/v2/spans"
    otlp = c.init_tracing(
        service_name="svc-c",
        protocol="http/protobuf",
        endpoint="http://collector:4318",
        set_global=False,
        env={},
    )
    assert otlp.endpoint == "http://collector:4318"
    c.close()  # shuts the handles down
    assert handle.is_shut_down and explicit.is_shut_down and otlp.is_shut_down


# --------------------------------------------------------------------------- async


async def test_async_client(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    server.enqueue("/health", fixture_reply("health.ok"))
    server.enqueue("/info", fixture_reply("serverVersion.ok"))
    server.defaults["/api/v2/services"] = Reply(200, ["svc"])
    server.defaults["/api/v2/spans"] = Reply(202, b"")
    async with aclient(server.url, token=TOKEN) as c:
        assert (await c.health()).healthy
        assert await c.ping() >= 0
        assert await c.server_version() == "3.6.1"
        assert (await c.request(sdk.Request("GET", "/raw"))).status == 200
        assert TOKEN not in repr(c)
        assert c.resources is not None
        assert c.transport is not None
        assert c.query is c.query
        assert await c.query.list_services() == ["svc"]
        assert c.reporter is c.reporter
        await c.reporter.report([{"traceId": "a" * 32, "id": "b" * 16}])
        handle = c.init_tracing(service_name="async-svc", set_global=False, env={})
        assert handle.endpoint == server.url + "/api/v2/spans"
    assert handle.is_shut_down
    await c.close()
    with pytest.raises(sdk.CancelledError):
        await c.health()
    with pytest.raises(sdk.ConfigError):
        c.init_tracing(service_name="late")
    env = {sdk.ENV_PREFIX + "URL": server.url}
    c2 = sdk.AsyncZipkinClient.from_env(env)
    server.enqueue("/info", Reply(501))
    with pytest.raises(sdk.UnsupportedError):
        await c2.server_version()
    server.enqueue("/info", Reply(404))
    with pytest.raises(sdk.UnsupportedError):
        await c2.server_version()
    server.enqueue("/health", Reply(503, "DOWN", content_type="text/*"))
    assert (await c2.health()).status == "DOWN"
    await c2.aclose()
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    c3 = sdk.AsyncZipkinClient()
    assert c3.config.url == server.url
    await c3.aclose()


async def test_async_health_unreachable() -> None:
    """core.health.unreachable (async)"""
    c = aclient(closed_port_url())
    status = await c.health()
    assert (status.healthy, status.status) == (False, "unreachable")
    await c.aclose()


async def test_async_health_cancel(server: MockServer) -> None:
    server.enqueue("/health", Reply(200, delay=2.0))
    c = aclient(server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(c.health(), errors)
    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)
    await c.aclose()


# --------------------------------------------------------------------------- polling


def test_poll_until() -> None:
    values = iter([None, [], [1]])
    assert sdk.poll_until(lambda: next(values), interval_s=0.001) == [1]
    attempts = iter([0, 1, 2, 3])
    assert sdk.poll_until(lambda: next(attempts), predicate=lambda v: v >= 2, interval_s=0) == 2
    errors = iter([sdk.UnavailableError("flaky"), "ok"])

    def flaky() -> str:
        item = next(errors)
        if isinstance(item, Exception):
            raise item
        return item

    assert sdk.poll_until(flaky, interval_s=0) == "ok"
    with pytest.raises(sdk.NotFoundError):
        sdk.poll_until(lambda: (_ for _ in ()).throw(sdk.NotFoundError("gone")))
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError, match=r"wait\.thing") as info:
        sdk.poll_until(
            lambda: (_ for _ in ()).throw(sdk.UnavailableError("down")),
            timeout_s=0.05,
            interval_s=0.01,
            operation="wait.thing",
        )
    assert isinstance(info.value.cause, sdk.UnavailableError)
    assert time.monotonic() - started < 1.0
    with pytest.raises(sdk.TimeoutError):
        sdk.poll_until(lambda: None, timeout_s=0.02, interval_s=0.005)


async def test_apoll_until() -> None:
    values = iter([None, "", "done"])

    async def fetch() -> str | None:
        return next(values)

    assert await sdk.apoll_until(fetch, interval_s=0.001) == "done"

    async def failing() -> str:
        raise sdk.UnavailableError("down")

    with pytest.raises(sdk.TimeoutError):
        await sdk.apoll_until(failing, timeout_s=0.03, interval_s=0.01)

    async def fatal() -> str:
        raise sdk.AuthenticationError("no")

    with pytest.raises(sdk.AuthenticationError):
        await sdk.apoll_until(fatal)

    async def numbers() -> int:
        return 5

    assert await sdk.apoll_until(numbers, predicate=lambda n: n == 5) == 5


def test_resource_registry() -> None:
    reg = sdk.ResourceRegistry()
    calls: list[str] = []

    class Good:
        def stop(self) -> None:
            calls.append("good")

    class Bad:
        def stop(self) -> None:
            raise RuntimeError("ignored")

    good, bad = Good(), Bad()
    reg.register(bad)
    reg.register(good)
    reg.unregister(good)
    reg.unregister(good)
    reg.register(good)
    assert isinstance(good, sdk.Stoppable)
    assert not reg.closed
    reg.stop_all()
    reg.stop_all()
    assert reg.closed and calls == ["good"]
    reg.register(bad)  # stopped immediately, error ignored


def test_get_logger_is_silent() -> None:
    logger = sdk.get_logger()
    assert logger.name == sdk.LOGGER_NAME == "grow_zipkin"
    assert any(type(h).__name__ == "NullHandler" for h in logger.handlers)
