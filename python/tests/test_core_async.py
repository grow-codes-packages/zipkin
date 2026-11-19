"""Asynchronous transport: the same core-unit ids against AsyncHttpTransport."""

from __future__ import annotations

import asyncio
import time
from typing import Any

import httpx
import pytest
import respx

import grow_zipkin as sdk
from _support import MockServer, Reply, closed_port_url, fast_retry, make_config, spawn_capturing

TOKEN = "async-secret-token-456"


def transport(url: str, **kw: Any) -> sdk.AsyncHttpTransport:
    return sdk.AsyncHttpTransport(make_config(url, **kw))


def get(path: str = "/x", **kw: Any) -> sdk.Request:
    return sdk.Request("GET", path, **kw)


async def test_core_retry_429_retry_after_async(server: MockServer) -> None:
    """core.retry.429-retry-after"""
    server.enqueue("/x", Reply(429, headers={"Retry-After": "1"}), Reply(200, {"ok": 1}))
    t = transport(server.url)
    started = time.monotonic()
    resp = await t.request(get())
    assert time.monotonic() - started >= 1.0
    assert resp.attempts == 2 and resp.json() == {"ok": 1}
    await t.aclose()
    await t.aclose()
    assert t.closed


async def test_core_retry_503_backoff_async(server: MockServer) -> None:
    """core.retry.503-backoff"""
    draws: list[float] = []

    def rnd() -> float:
        draws.append(0.25)
        return 0.25

    server.enqueue("/x", Reply(503), Reply(503), Reply(200))
    t = transport(server.url, retry=sdk.RetryPolicy(initial_backoff=0.08, random=rnd))
    started = time.monotonic()
    resp = await t.request(get())
    assert resp.attempts == 3 and len(draws) == 2
    assert time.monotonic() - started >= 0.25 * 0.08 + 0.25 * 0.16


async def test_core_retry_exhausted_async(server: MockServer) -> None:
    """core.retry.exhausted"""
    server.defaults["/x"] = Reply(503)
    t = transport(server.url)
    with pytest.raises(sdk.UnavailableError) as info:
        await t.request(get())
    assert info.value.retryable and info.value.http_status == 503
    assert len(server.seen) == 4


async def test_core_retry_post_not_replayed_async(server: MockServer) -> None:
    """core.retry.post-not-replayed"""
    server.enqueue("/p", Reply(500))
    t = transport(server.url)
    with pytest.raises(sdk.UnavailableError):
        await t.request(sdk.Request("POST", "/p"))
    assert len(server.seen) == 1
    server.enqueue("/p", Reply(429), Reply(200))
    assert (await t.request(sdk.Request("POST", "/p"))).attempts == 2


async def test_core_retry_retry_after_too_long_async(server: MockServer) -> None:
    """core.retry.retry-after-too-long"""
    server.enqueue("/x", Reply(429, headers={"Retry-After": "3600"}))
    t = transport(server.url)
    with pytest.raises(sdk.RateLimitError) as info:
        await t.request(get())
    assert info.value.retry_after == 3600


async def test_core_timeout_request_async(server: MockServer) -> None:
    """core.timeout.request"""
    server.enqueue("/x", Reply(200, delay=1.5))
    t = transport(server.url, request_timeout=0.2, retry=fast_retry(max_retries=0))
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError, match="request timeout"):
        await t.request(get())
    assert time.monotonic() - started < 1.0


async def test_core_timeout_total_async(server: MockServer) -> None:
    """core.timeout.total"""
    server.defaults["/x"] = Reply(503)
    policy = sdk.RetryPolicy(initial_backoff=0.2, max_backoff=0.2, random=lambda: 1.0)
    t = transport(server.url, total_timeout=0.5, retry=policy.with_max_retries(10))
    with pytest.raises(sdk.TimeoutError, match="total timeout"):
        await t.request(get())
    server.defaults["/y"] = Reply(200, delay=1.0)
    t2 = transport(server.url, total_timeout=0.3)
    with pytest.raises(sdk.TimeoutError, match="total timeout"):
        await t2.request(get("/y"))


async def test_core_cancel_during_request_async(server: MockServer) -> None:
    """core.cancel (task cancelled during the request)"""
    server.enqueue("/x", Reply(200, delay=2.0))
    t = transport(server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(t.request(get()), errors)
    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert task.cancelled()
    err = errors[0]
    assert isinstance(err, sdk.CancelledError)
    assert isinstance(err, asyncio.CancelledError)
    assert err.code == sdk.ErrorCode.CANCELLED


async def test_core_cancel_during_backoff_async(server: MockServer) -> None:
    """core.cancel (task cancelled during the backoff sleep)"""
    server.defaults["/x"] = Reply(503, headers={"Retry-After": "5"})
    t = transport(server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(t.request(get()), errors)
    await asyncio.sleep(0.3)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)
    assert len(server.seen) <= 1


async def test_cancellation_works_with_wait_for(server: MockServer) -> None:
    server.enqueue("/x", Reply(200, delay=2.0))
    t = transport(server.url)
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(t.request(get()), 0.2)


async def test_close_during_backoff_async(server: MockServer) -> None:
    server.defaults["/x"] = Reply(503, headers={"Retry-After": "5"})
    t = transport(server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(t.request(get()), errors)
    await asyncio.sleep(0.3)
    await t.aclose()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)
    assert "closed" in str(errors[0])
    with pytest.raises(sdk.CancelledError, match="closed"):
        await t.request(get())


async def test_close_during_request_async(server: MockServer) -> None:
    server.defaults["/x"] = Reply(200, delay=1.0)
    t = transport(server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(t.request(get()), errors)
    await asyncio.sleep(0.2)
    await t.aclose()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)


@pytest.mark.parametrize(
    ("status", "cls"),
    [
        (400, sdk.ValidationError),
        (401, sdk.AuthenticationError),
        (403, sdk.PermissionDeniedError),
        (404, sdk.NotFoundError),
        (409, sdk.ConflictError),
        (412, sdk.ConflictError),
        (413, sdk.ValidationError),
        (422, sdk.ValidationError),
        (429, sdk.RateLimitError),
        (500, sdk.UnavailableError),
        (501, sdk.UnsupportedError),
        (502, sdk.UnavailableError),
        (503, sdk.UnavailableError),
        (504, sdk.TimeoutError),
    ],
)
async def test_core_errors_mapping_async(status: int, cls: type[Exception]) -> None:
    """core.errors.mapping"""
    with respx.mock(base_url="http://svc") as mock:
        mock.get("/x").mock(return_value=httpx.Response(status))
        t = transport("http://svc", retry=fast_retry(max_retries=0))
        with pytest.raises(cls):
            await t.request(get())


async def test_async_network_errors_and_raise_for_status() -> None:
    with respx.mock(base_url="http://svc") as mock:
        mock.get("/x").mock(side_effect=[httpx.ReadError("x"), httpx.Response(200)])
        mock.post("/p").mock(side_effect=httpx.ReadError(f"x {TOKEN}"))
        mock.get("/s").mock(
            side_effect=[httpx.Response(503), httpx.Response(503, json={"status": "down"})]
        )
        mock.get("/r").mock(return_value=httpx.Response(429, headers={"Retry-After": "999"}))
        t = transport("http://svc", token=TOKEN)
        assert (await t.request(get())).attempts == 2
        with pytest.raises(sdk.UnavailableError) as info:
            await t.request(sdk.Request("POST", "/p"))
        assert TOKEN not in str(info.value)
        resp = await t.request(get("/s", raise_for_status=False, max_retries=1))
        assert resp.status == 503
        assert (await t.request(get("/r", raise_for_status=False))).status == 429
    refused = transport(closed_port_url())
    with pytest.raises(sdk.UnavailableError):
        await refused.request(sdk.Request("POST", "/p"))


async def test_core_useragent_and_hooks_async(server: MockServer) -> None:
    """core.useragent + core.hooks"""
    events: list[str] = []

    def boom(_: Any) -> None:
        events.append("boom")
        raise RuntimeError("ignored")

    server.enqueue("/x", Reply(404))
    t = transport(server.url, hooks=sdk.Hooks(on_request=boom, on_response=boom, on_error=boom))
    with pytest.raises(sdk.NotFoundError):
        await t.request(get())
    assert events == ["boom", "boom", "boom"]
    assert server.seen[0].headers["User-Agent"] == sdk.user_agent()
    assert t.log is not None


async def test_invalid_url_async() -> None:
    t = sdk.AsyncHttpTransport(sdk.ZipkinConfig(url="ftp://svc"))
    with pytest.raises(sdk.ConfigError):
        await t.request(get())
