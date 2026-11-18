"""Synchronous transport: core-unit conformance ids (conformance/core-unit.md)."""

from __future__ import annotations

import logging
import threading
import time
from typing import Any

import httpx
import pytest
import respx

import grow_zipkin as sdk
from _support import MockServer, Reply, closed_port_url, fast_retry, make_config

TOKEN = "super-secret-token-123"


def transport(url: str, **kw: Any) -> sdk.HttpTransport:
    return sdk.HttpTransport(make_config(url, **kw))


def get(path: str = "/x", **kw: Any) -> sdk.Request:
    return sdk.Request("GET", path, **kw)


# --------------------------------------------------------------------------- retries


def test_core_retry_429_retry_after(server: MockServer) -> None:
    """core.retry.429-retry-after"""
    server.enqueue("/x", Reply(429, headers={"Retry-After": "1"}), Reply(200, {"ok": True}))
    t = transport(server.url)
    started = time.monotonic()
    resp = t.request(get())
    assert time.monotonic() - started >= 1.0
    assert resp.status == 200
    assert resp.attempts == 2
    assert resp.json() == {"ok": True}
    t.close()


def test_core_retry_503_backoff(server: MockServer) -> None:
    """core.retry.503-backoff"""
    calls: list[int] = []

    def rnd() -> float:
        calls.append(1)
        return 0.5

    policy = sdk.RetryPolicy(initial_backoff=0.04, max_backoff=1.0, random=rnd)
    server.enqueue("/x", Reply(503), Reply(503), Reply(200))
    t = transport(server.url, retry=policy)
    started = time.monotonic()
    resp = t.request(get())
    elapsed = time.monotonic() - started
    assert resp.attempts == 3
    assert len(calls) == 2  # one jittered sleep per retry
    # sleeps are 0.5 * 0.04 and 0.5 * 0.08
    assert elapsed >= 0.06
    assert policy.backoff_cap(0) == 0.04 and policy.backoff_cap(1) == 0.08
    assert len(server.seen) == 3


def test_core_retry_exhausted(server: MockServer) -> None:
    """core.retry.exhausted"""
    server.defaults["/x"] = Reply(503, {"message": "maintenance"})
    t = transport(server.url)
    with pytest.raises(sdk.UnavailableError) as info:
        t.request(get(operation="thing.get"))
    err = info.value
    assert err.retryable is True
    assert err.http_status == 503
    assert err.message == "thing.get: maintenance (HTTP 503)"
    assert len(server.seen) == 4


def test_core_retry_post_not_replayed(server: MockServer) -> None:
    """core.retry.post-not-replayed"""
    server.enqueue("/p", Reply(500))
    t = transport(server.url)
    with pytest.raises(sdk.UnavailableError):
        t.request(sdk.Request("POST", "/p", json={"a": 1}))
    assert len(server.seen) == 1

    server.enqueue("/p", Reply(503))
    with pytest.raises(sdk.UnavailableError):
        t.request(sdk.Request("POST", "/p"))
    assert len(server.seen) == 2

    server.enqueue("/p", Reply(429), Reply(201, {"id": 1}))
    resp = t.request(sdk.Request("POST", "/p"))
    assert resp.status == 201
    assert len(server.seen) == 4

    # explicitly idempotent POST (idempotency key) is retried on 500
    server.enqueue("/p", Reply(500), Reply(200))
    resp = t.request(sdk.Request("POST", "/p", idempotency_key="k-1"))
    assert resp.attempts == 2
    assert server.seen[-1].headers["Idempotency-Key"] == "k-1"


def test_post_retried_on_connection_refused() -> None:
    attempts: list[int] = []
    hooks = sdk.Hooks(on_request=lambda info: attempts.append(info.attempt))
    t = transport(closed_port_url(), hooks=hooks)
    with pytest.raises(sdk.UnavailableError) as info:
        t.request(sdk.Request("POST", "/p"))
    assert attempts == [1, 2, 3, 4]
    assert "connection failed" in info.value.message


def test_post_not_retried_after_uncertain_send() -> None:
    with respx.mock(base_url="http://svc") as mock:
        route = mock.post("/p").mock(side_effect=httpx.ReadError("reset"))
        t = transport("http://svc")
        with pytest.raises(sdk.UnavailableError) as info:
            t.request(sdk.Request("POST", "/p"))
        assert route.call_count == 1
        assert "network error" in info.value.message
        mock.get("/g").mock(side_effect=[httpx.ReadError("reset"), httpx.Response(200)])
        assert t.request(get("/g")).attempts == 2


def test_core_retry_retry_after_too_long(server: MockServer) -> None:
    """core.retry.retry-after-too-long"""
    server.enqueue("/x", Reply(429, headers={"Retry-After": "3600"}))
    t = transport(server.url)
    started = time.monotonic()
    with pytest.raises(sdk.RateLimitError) as info:
        t.request(get())
    assert time.monotonic() - started < 1.0
    assert info.value.retry_after == 3600
    assert info.value.http_status == 429
    assert len(server.seen) == 1


def test_retry_after_beyond_total_budget(server: MockServer) -> None:
    server.enqueue("/x", Reply(503, headers={"Retry-After": "5"}))
    t = transport(server.url, total_timeout=2.0)
    with pytest.raises(sdk.UnavailableError) as info:
        t.request(get())
    assert info.value.retry_after == 5
    assert len(server.seen) == 1


def test_retry_after_exhausted_rate_limit(server: MockServer) -> None:
    server.defaults["/x"] = Reply(429, headers={"Retry-After": "0"})
    t = transport(server.url, retry=fast_retry(max_retries=1))
    with pytest.raises(sdk.RateLimitError) as info:
        t.request(get())
    assert info.value.retry_after == 0
    assert len(server.seen) == 2


# --------------------------------------------------------------------------- timeouts


def test_core_timeout_request(server: MockServer) -> None:
    """core.timeout.request"""
    server.enqueue("/x", Reply(200, delay=1.5))
    t = transport(server.url, request_timeout=0.2, retry=fast_retry(max_retries=0))
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError) as info:
        t.request(get())
    assert time.monotonic() - started < 1.0
    assert info.value.retryable is True
    assert "request timeout" in info.value.message


def test_request_timeout_covers_slow_body(server: MockServer) -> None:
    server.enqueue("/x", Reply(200, "x" * 40, drip=0.03))
    t = transport(server.url, request_timeout=0.3, retry=fast_retry(max_retries=0))
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError):
        t.request(get())
    assert time.monotonic() - started < 1.0


def test_per_request_timeout_override_and_retry(server: MockServer) -> None:
    server.enqueue("/x", Reply(200, delay=0.6), Reply(200, {"n": 2}))
    t = transport(server.url, request_timeout=5.0)
    resp = t.request(get(timeout=0.2))
    assert resp.attempts == 2


def test_core_timeout_total(server: MockServer) -> None:
    """core.timeout.total"""
    server.defaults["/x"] = Reply(503)
    policy = sdk.RetryPolicy(initial_backoff=0.2, max_backoff=0.2, random=lambda: 1.0)
    t = transport(server.url, total_timeout=0.5, retry=policy.with_max_retries(10))
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError) as info:
        t.request(get())
    assert time.monotonic() - started < 1.0
    assert "total timeout" in info.value.message
    assert isinstance(info.value.cause, sdk.UnavailableError)


def test_total_timeout_cuts_attempt(server: MockServer) -> None:
    server.defaults["/x"] = Reply(200, delay=1.0)
    t = transport(server.url, total_timeout=0.3, request_timeout=5.0)
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError) as info:
        t.request(get())
    assert time.monotonic() - started < 0.9
    assert "total timeout" in info.value.message


def test_connect_timeout_maps_to_timeout() -> None:
    with respx.mock(base_url="http://svc") as mock:
        mock.get("/x").mock(side_effect=httpx.ConnectTimeout("slow"))
        t = transport("http://svc", retry=fast_retry(max_retries=1))
        with pytest.raises(sdk.TimeoutError) as info:
            t.request(get())
        assert "connect timeout" in info.value.message
        mock.get("/pool").mock(side_effect=httpx.PoolTimeout("busy"))
        with pytest.raises(sdk.TimeoutError, match="pool"):
            t.request(get("/pool"))


# --------------------------------------------------------------------------- cancellation


def _cancel_later(token: sdk.CancelToken, delay: float) -> None:
    threading.Timer(delay, token.cancel).start()


def test_core_cancel_during_request(server: MockServer) -> None:
    """core.cancel (during the request)"""
    server.enqueue("/x", Reply(200, delay=2.0))
    t = transport(server.url)
    token = sdk.CancelToken()
    _cancel_later(token, 0.1)
    started = time.monotonic()
    with pytest.raises(sdk.CancelledError):
        t.request(get(), cancel=token)
    assert time.monotonic() - started < 1.5


def test_core_cancel_during_backoff(server: MockServer) -> None:
    """core.cancel (during the backoff sleep)"""
    server.defaults["/x"] = Reply(503, headers={"Retry-After": "5"})
    t = transport(server.url)
    token = sdk.CancelToken()
    _cancel_later(token, 0.2)
    started = time.monotonic()
    with pytest.raises(sdk.CancelledError):
        t.request(get(), cancel=token)
    assert time.monotonic() - started < 2.0
    assert len(server.seen) == 1


def test_cancel_token_already_cancelled_and_success(server: MockServer) -> None:
    t = transport(server.url)
    token = sdk.CancelToken()
    assert t.request(get(), cancel=token).status == 200
    token.cancel()
    token.cancel()
    assert token.cancelled and token.wait(0)
    with pytest.raises(sdk.CancelledError):
        t.request(get(), cancel=token)
    with pytest.raises(sdk.CancelledError):
        token.raise_if_cancelled("op")
    called: list[int] = []
    token.on_cancel(lambda: called.append(1))()
    assert called == [1]
    fresh = sdk.CancelToken()
    fresh.raise_if_cancelled()
    unregister = fresh.on_cancel(lambda: called.append(2))
    unregister()
    unregister()
    fresh.cancel()
    assert called == [1]


def test_cancellable_request_propagates_errors() -> None:
    t = transport(closed_port_url(), retry=fast_retry(max_retries=0))
    with pytest.raises(sdk.UnavailableError):
        t.request(get(), cancel=sdk.CancelToken())


def test_close_during_backoff_cancels(server: MockServer) -> None:
    server.defaults["/x"] = Reply(503, headers={"Retry-After": "5"})
    t = transport(server.url)
    threading.Timer(0.2, t.close).start()
    with pytest.raises(sdk.CancelledError, match="closed"):
        t.request(get())
    assert t.closed
    with pytest.raises(sdk.CancelledError, match="closed"):
        t.request(get())


def test_close_during_request_cancels(server: MockServer) -> None:
    server.defaults["/x"] = Reply(200, delay=1.0)
    t = transport(server.url)
    threading.Timer(0.2, t.close).start()
    with pytest.raises(sdk.CancelledError, match="closed"):
        t.request(get())


# --------------------------------------------------------------------------- status mapping


@pytest.mark.parametrize(
    ("status", "cls", "retryable"),
    [
        (400, sdk.ValidationError, False),
        (401, sdk.AuthenticationError, False),
        (403, sdk.PermissionDeniedError, False),
        (404, sdk.NotFoundError, False),
        (408, sdk.TimeoutError, True),
        (409, sdk.ConflictError, False),
        (412, sdk.ConflictError, False),
        (413, sdk.ValidationError, False),
        (418, sdk.ValidationError, False),
        (422, sdk.ValidationError, False),
        (429, sdk.RateLimitError, True),
        (500, sdk.UnavailableError, True),
        (501, sdk.UnsupportedError, False),
        (502, sdk.UnavailableError, True),
        (503, sdk.UnavailableError, True),
        (504, sdk.TimeoutError, True),
        (599, sdk.UnavailableError, True),
    ],
)
def test_core_errors_mapping(status: int, cls: type[Exception], retryable: bool) -> None:
    """core.errors.mapping"""
    with respx.mock(base_url="http://svc") as mock:
        mock.get("/x").mock(return_value=httpx.Response(status, headers={"x-request-id": "rid-1"}))
        t = transport("http://svc", retry=fast_retry(max_retries=0))
        with pytest.raises(cls) as info:
            t.request(get(operation="kv.read secret/app"))
    err = info.value
    assert type(err) is cls
    assert err.http_status == status
    assert err.request_id == "rid-1"
    assert err.retryable is retryable
    assert err.message.startswith("kv.read secret/app: ")
    assert err.message.endswith(f"(HTTP {status})")


def test_error_message_vendor_text() -> None:
    cases = [
        ({"errors": ["permission denied"]}, "permission denied"),
        ({"error": {"message": "nested msg"}}, "nested msg"),
        ({"detail": [{"msg": "bad field"}]}, "bad field"),
        ({"other": 1}, "not found"),
        ([], "not found"),
        ({"a": {"b": {"c": {"d": {"message": "too deep"}}}}}, "not found"),
    ]
    for body, reason in cases:
        err = sdk.error_from_status(404, "op", content=httpx.Response(404, json=body).content)
        assert err.message == f"op: {reason} (HTTP 404)"
    text = sdk.error_from_status(
        400, "op", content=b"plain failure", headers=httpx.Headers({"content-type": "text/plain"})
    )
    assert text.message == "op: plain failure (HTTP 400)"
    html = sdk.error_from_status(
        400, "op", content=b"<html>x</html>", headers=httpx.Headers({"content-type": "text/html"})
    )
    assert html.message == "op: bad request (HTTP 400)"
    broken = sdk.error_from_status(500, "op", content=b"{not json")
    assert broken.message == "op: internal server error (HTTP 500)"
    blank = sdk.error_from_status(
        400, "op", content=b"   ", headers=httpx.Headers({"content-type": "text/plain"})
    )
    assert blank.message == "op: bad request (HTTP 400)"
    unknown = sdk.error_from_status(599, "op")
    assert unknown.message == "op: error (HTTP 599)"
    long = sdk.error_from_status(
        400, "op", content=httpx.Response(400, json={"message": "y" * 900}).content
    )
    assert len(long.message) < 330


def test_error_status_raise_for_status_false(server: MockServer) -> None:
    server.enqueue("/x", Reply(404, {"message": "nope"}))
    t = transport(server.url)
    resp = t.request(get(raise_for_status=False))
    assert resp.status == 404 and not resp.ok
    assert resp.text == '{"message": "nope"}'
    server.enqueue("/x", Reply(503), Reply(503, {"status": "down"}))
    resp = t.request(get(raise_for_status=False, max_retries=1))
    assert resp.status == 503 and resp.attempts == 2
    server.enqueue("/x", Reply(429, headers={"Retry-After": "3600"}))
    resp = t.request(get(raise_for_status=False))
    assert resp.status == 429


# --------------------------------------------------------------------------- redaction / hooks / UA


def test_core_redaction_error_text(server: MockServer, caplog: pytest.LogCaptureFixture) -> None:
    """core.redaction.error-text"""
    server.enqueue("/x", Reply(401, {"error": f"invalid token {TOKEN}"}))
    seen: list[Any] = []
    hooks = sdk.Hooks(on_request=seen.append, on_response=seen.append, on_error=seen.append)
    t = transport(server.url, token=TOKEN, hooks=hooks)
    caplog.set_level(logging.DEBUG, logger=sdk.LOGGER_NAME)
    with pytest.raises(sdk.AuthenticationError) as info:
        t.request(
            get(
                params={"token": TOKEN},
                headers={"Authorization": f"Bearer {TOKEN}", "X-Vault-Token": TOKEN},
            )
        )
    assert TOKEN not in str(info.value)
    assert TOKEN not in repr(info.value)
    assert "[REDACTED]" in info.value.message
    for item in seen:
        assert TOKEN not in repr(item)
    assert seen[0].headers["Authorization"] == "[REDACTED]"
    assert "token=[REDACTED]" in seen[0].url
    assert caplog.records, "debug logging expected"
    assert TOKEN not in caplog.text
    # the real request still carried the credential
    assert server.seen[0].headers["Authorization"] == f"Bearer {TOKEN}"


def test_network_error_text_redacted() -> None:
    with respx.mock(base_url="http://svc") as mock:
        mock.get("/x").mock(side_effect=httpx.ReadError(f"boom {TOKEN}"))
        t = transport("http://svc", token=TOKEN, retry=fast_retry(max_retries=0))
        with pytest.raises(sdk.UnavailableError) as info:
            t.request(get())
    assert TOKEN not in str(info.value)


def test_core_useragent(server: MockServer) -> None:
    """core.useragent"""
    t = transport(server.url)
    t.request(get())
    ua = server.seen[0].headers["User-Agent"]
    assert ua == f"grow-codes-zipkin/{sdk.__version__} (python)"
    assert ua == sdk.user_agent()
    t2 = transport(server.url, user_agent_suffix="my-app/1.0", headers={"X-Extra": "1"})
    t2.request(get())
    assert server.seen[1].headers["User-Agent"] == f"{ua} my-app/1.0"
    assert server.seen[1].headers["X-Extra"] == "1"


def test_core_hooks(server: MockServer) -> None:
    """core.hooks"""
    events: list[tuple[str, Any]] = []

    def boom(_: Any) -> None:
        raise RuntimeError("hook failure is ignored")

    hooks = sdk.Hooks(
        on_request=lambda i: events.append(("request", i)),
        on_response=lambda i: events.append(("response", i)),
        on_error=lambda i: events.append(("error", i)),
    )
    server.enqueue("/x", Reply(503, headers={"x-amzn-requestid": "aws-1"}), Reply(200))
    t = transport(server.url, hooks=hooks)
    t.request(get(headers={"X-Api-Key": "k-123456"}))
    kinds = [k for k, _ in events]
    assert kinds == ["request", "response", "error", "request", "response"]
    req1, resp1, err1, req2, resp2 = (i for _, i in events)
    assert (req1.attempt, req2.attempt) == (1, 2)
    assert req1.method == "GET" and req1.url.endswith("/x")
    assert req1.headers["X-Api-Key"] == "[REDACTED]"
    assert resp1.status == 503 and resp1.request_id == "aws-1" and resp1.duration_ms >= 0
    assert err1.will_retry is True and isinstance(err1.error, sdk.UnavailableError)
    assert resp2.status == 200 and resp2.attempt == 2

    failing = transport(
        server.url, hooks=sdk.Hooks(on_request=boom, on_response=boom, on_error=boom)
    )
    server.enqueue("/x", Reply(404))
    with pytest.raises(sdk.NotFoundError):
        failing.request(get())
    assert failing.request(get()).status == 200


class ListLogger:
    def __init__(self) -> None:
        self.lines: list[tuple[str, str, Any]] = []

    def debug(self, msg: str, fields: Any = None) -> None:
        self.lines.append(("debug", msg, fields))

    def info(self, msg: str, fields: Any = None) -> None:
        self.lines.append(("info", msg, fields))

    def warn(self, msg: str, fields: Any = None) -> None:
        self.lines.append(("warn", msg, fields))

    def error(self, msg: str, fields: Any = None) -> None:
        raise RuntimeError("logger failures are ignored")


def test_custom_logger_receives_redacted_fields(server: MockServer) -> None:
    log = ListLogger()
    server.enqueue("/x", Reply(503, {"message": f"bad {TOKEN}"}), Reply(200))
    t = transport(server.url, logger=log, token=TOKEN)
    t.request(get(params={"q": "1"}))
    levels = [lvl for lvl, _, _ in log.lines]
    assert "debug" in levels and "warn" in levels
    assert TOKEN not in repr(log.lines)
    from grow_zipkin._core.hooks import SdkLogger

    sdk_log = SdkLogger(log, lambda: [TOKEN])
    sdk_log.info(f"hello {TOKEN}", {"password": "x", "n": 1})
    sdk_log.error("ignored")
    assert log.lines[-1] == ("info", "hello [REDACTED]", {"password": "[REDACTED]", "n": 1})
    quiet = logging.getLogger("grow-test-quiet")
    quiet.setLevel(logging.ERROR)
    SdkLogger(quiet).debug("skipped", {"a": 1})


def test_insecure_skip_verify_warns_once(caplog: pytest.LogCaptureFixture) -> None:
    from grow_zipkin._core import http as http_mod

    http_mod._insecure_warned = False
    caplog.set_level(logging.WARNING, logger=sdk.LOGGER_NAME)
    tls = sdk.TlsConfig(insecure_skip_verify=True)
    transport("https://svc", tls=tls).close()
    transport("https://svc", tls=tls).close()
    warnings = [r for r in caplog.records if "verification is disabled" in r.getMessage()]
    assert len(warnings) == 1


def test_tls_settings(tmp_path: Any) -> None:
    bad = tmp_path / "ca.pem"
    bad.write_text("not a certificate")
    with pytest.raises(sdk.ConfigError, match="TLS"):
        transport("https://svc", tls=sdk.TlsConfig(ca_cert_path=str(bad)))
    with pytest.raises(sdk.ConfigError, match="TLS"):
        transport(
            "https://svc",
            tls=sdk.TlsConfig(client_cert_path=str(bad), client_key_path=str(bad)),
        )
    import certifi

    ok = transport("https://svc", tls=sdk.TlsConfig(ca_cert_path=certifi.where()))
    ok.close()
    proxied = transport("http://svc", proxy="http://127.0.0.1:9")
    proxied.close()


def test_invalid_url_maps_to_config_error() -> None:
    t = sdk.HttpTransport(sdk.ZipkinConfig(url="ftp://svc"))
    with pytest.raises(sdk.ConfigError):
        t.request(get())


def test_request_building(server: MockServer) -> None:
    t = transport(server.url + "/base/")
    t.request(
        sdk.Request(
            "PUT",
            "v1/items/a%2Fb",
            params={"q": "a b", "flag": True, "off": False, "skip": None, "multi": ["1", "2"]},
            json={"x": [1, 2]},
        )
    )
    seen = server.seen[0]
    assert seen.method == "PUT"
    assert seen.path == "/base/v1/items/a%2Fb?q=a%20b&flag=true&off=false&multi=1&multi=2"
    assert seen.body == b'{"x":[1,2]}'
    assert seen.headers["Content-Type"] == "application/json"
    t.request(sdk.Request("POST", "/raw?x=1", params={"y": 2}, content=b"\x00\x01"))
    assert server.seen[1].path == "/base/raw?x=1&y=2"
    assert server.seen[1].body == b"\x00\x01"
    t.request(sdk.Request("GET", "/empty", params={"none": None}))
    assert server.seen[2].path == "/base/empty"


def test_response_helpers() -> None:
    resp = sdk.Response(200, httpx.Headers(), b"not json", operation="op")
    with pytest.raises(sdk.ValidationError, match="invalid JSON"):
        resp.json()
    anonymous = sdk.Response(200, httpx.Headers(), b"{")
    with pytest.raises(sdk.ValidationError, match="response"):
        anonymous.json()
    req = sdk.Request("delete", "/x")
    assert req.is_idempotent and req.op == "DELETE /x"
    assert not sdk.Request("POST", "/x").is_idempotent
    assert sdk.Request("POST", "/x", idempotent=True).is_idempotent
    assert not sdk.Request("GET", "/x", idempotent=False).is_idempotent
    assert len(sdk.new_idempotency_key()) == 36


def test_known_secrets_source_failure_is_ignored(server: MockServer) -> None:
    def broken() -> list[str]:
        raise RuntimeError("no secrets today")

    t = sdk.HttpTransport(make_config(server.url), known_secrets=broken)
    server.enqueue("/x", Reply(404))
    with pytest.raises(sdk.NotFoundError):
        t.request(get())
    assert t.log is not None
