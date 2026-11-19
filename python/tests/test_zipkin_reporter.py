"""ZipkinReporter / AsyncZipkinReporter and ZipkinConfig (endpoint derivation, env, secrets)."""

from __future__ import annotations

import gzip
import json
from typing import Any

import pytest

import grow_zipkin as sdk
from _fixtures import fixture_reply, load
from _support import MockServer, Reply, make_config

TOKEN = "gateway-token-4321"


def spans(n: int = 1) -> list[dict[str, Any]]:
    return [{"traceId": "A" * 32, "id": f"{i + 1:016X}", "tags": {"n": i}} for i in range(n)]


def body(seen: Any) -> Any:
    raw = seen.body
    if seen.headers.get("Content-Encoding") == "gzip":
        raw = gzip.decompress(raw)
    return json.loads(raw)


# --------------------------------------------------------------------------- config


def test_config_endpoint_derivation_and_validation() -> None:
    assert sdk.ZipkinConfig().endpoint == "http://127.0.0.1:9411/api/v2/spans"
    cfg = sdk.ZipkinConfig(url="https://gw.example/zipkin/")
    assert cfg.endpoint == "https://gw.example/zipkin/api/v2/spans"
    assert sdk.spans_endpoint("http://h/api/v2/spans/") == "http://h/api/v2/spans"
    assert sdk.base_from_endpoint("http://h/p/api/v2/spans") == "http://h/p"
    assert sdk.base_from_endpoint("http://h/other") == "http://h/other"
    with pytest.raises(sdk.ConfigError, match="endpoint"):
        sdk.ZipkinConfig(endpoint="ftp://x").validate()
    with pytest.raises(sdk.ConfigError, match="endpoint"):
        sdk.ZipkinConfig(endpoint="http://[::1").validate()


def test_config_from_env_precedence() -> None:
    p = sdk.ENV_PREFIX
    otel = "https://gw.example/zipkin/api/v2/spans"
    cfg = sdk.ZipkinConfig.from_env({"OTEL_EXPORTER_ZIPKIN_ENDPOINT": otel})
    assert (cfg.url, cfg.endpoint) == ("https://gw.example/zipkin", otel)
    cfg = sdk.ZipkinConfig.from_env(
        {"ZIPKIN_URL": "http://vendor:9411", "OTEL_EXPORTER_ZIPKIN_ENDPOINT": otel}
    )
    assert (cfg.url, cfg.endpoint) == ("http://vendor:9411", otel)
    cfg = sdk.ZipkinConfig.from_env(
        {
            p + "URL": "http://grow:9411",
            "ZIPKIN_URL": "http://vendor:9411",
            p + "ENDPOINT": "http://e:1/x",
        }
    )
    assert (cfg.url, cfg.endpoint) == ("http://grow:9411", "http://e:1/x")
    cfg = sdk.ZipkinConfig.from_env({"ZIPKIN_URL": "http://vendor:9411"}, url="http://explicit:1")
    assert (cfg.url, cfg.endpoint) == ("http://explicit:1", "http://explicit:1/api/v2/spans")
    assert sdk.ZipkinConfig.from_env({"OTEL_EXPORTER_ZIPKIN_ENDPOINT": ""}).url == sdk.DEFAULT_URL


def test_config_secrets_are_redacted() -> None:
    cfg = sdk.ZipkinConfig(
        headers={"Authorization": f"Bearer {TOKEN}", "X-Api-Key": "", "Accept": "x"},
        endpoint="http://u:pw@h:1/api/v2/spans",
    )
    assert cfg.known_secrets() == [f"Bearer {TOKEN}", TOKEN]
    assert TOKEN not in repr(cfg) and ":pw@" not in repr(cfg)
    assert sdk.ZipkinConfig(headers={"Cookie": "a=b"}).known_secrets() == ["a=b"]


# --------------------------------------------------------------------------- reporter


def test_report_json_from_fixture(server: MockServer) -> None:
    server.enqueue("/api/v2/spans", fixture_reply("report.json"))
    recorded = load("report.json")["request"]["body"]
    with sdk.ZipkinReporter(make_config(server.url)) as reporter:
        reporter.report(recorded)
        reporter.report([])  # nothing sent
    assert len(server.seen) == 1
    seen = server.seen[0]
    assert seen.method == "POST" and seen.headers["Content-Type"] == "application/json"
    sent = body(seen)
    assert [s["id"] for s in sent] == [s["id"] for s in recorded]
    assert "Content-Encoding" not in seen.headers


def test_report_gzip_batches_and_normalization(server: MockServer) -> None:
    server.defaults["/api/v2/spans"] = Reply(202, b"")
    reporter = sdk.ZipkinReporter(make_config(server.url), compression="gzip", max_batch_size=2)
    reporter.report(spans(5))
    assert [len(body(s)) for s in server.seen] == [2, 2, 1]
    first = body(server.seen[0])[0]
    assert first == {"traceId": "a" * 32, "id": "0000000000000001", "tags": {"n": "0"}}
    assert all(s.headers["Content-Encoding"] == "gzip" for s in server.seen)
    reporter.close()
    reporter.close()


def test_report_validation_happens_before_sending(server: MockServer) -> None:
    reporter = sdk.ZipkinReporter(make_config(server.url))
    with pytest.raises(sdk.ValidationError):
        reporter.report([*spans(1), {"traceId": "zz", "id": "b" * 16}])
    assert server.seen == []


def test_report_errors_and_retries(server: MockServer) -> None:
    server.enqueue("/api/v2/spans", fixture_reply("report.invalidJson"))
    reporter = sdk.ZipkinReporter(make_config(server.url))
    with pytest.raises(sdk.ValidationError, match="JSON_V2"):
        reporter.report(spans())
    server.enqueue("/api/v2/spans", Reply(503, "busy", content_type="text/*"), Reply(202, b""))
    reporter.report(spans())  # idempotent ingest: 503 is retried
    server.enqueue("/api/v2/spans", Reply(413, "too big", content_type="text/plain"))
    with pytest.raises(sdk.ValidationError, match="too big"):
        reporter.report(spans())
    server.enqueue(
        "/api/v2/spans",
        Reply(429, "slow down", headers={"Retry-After": "120"}, content_type="text/*"),
    )
    with pytest.raises(sdk.RateLimitError) as info:
        reporter.report(spans())
    assert info.value.retry_after == 120


def test_reporter_options_validation(server: MockServer) -> None:
    cfg = make_config(server.url)
    for kwargs in (
        {"compression": "brotli"},
        {"max_batch_size": 0},
        {"max_batch_size": True},
        {"endpoint": "ftp://x"},
    ):
        with pytest.raises(sdk.ConfigError):
            sdk.ZipkinReporter(cfg, **kwargs)  # type: ignore[arg-type]


def test_reporter_endpoints(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    server.defaults["/collector/spans"] = Reply(202, b"")
    server.defaults["/gw/api/v2/spans"] = Reply(202, b"")
    # an endpoint outside the client URL gets its own transport (query string kept)
    client = sdk.ZipkinClient(make_config("http://127.0.0.1:9", token=TOKEN))
    other = client.create_reporter(endpoint=server.url + "/collector/spans?x=1")
    other.report(spans())
    assert server.seen[-1].path == "/collector/spans?x=1"
    assert server.seen[-1].headers["Authorization"] == f"Bearer {TOKEN}"
    assert "collector" in repr(other) and TOKEN not in repr(other)
    client.close()
    # a gateway prefix stays on the shared transport
    gw = sdk.ZipkinClient(make_config(server.url + "/gw"))
    gw.reporter.report(spans())
    assert server.seen[-1].path == "/gw/api/v2/spans"
    gw.close()
    from_env = sdk.ZipkinReporter.from_env(
        {"OTEL_EXPORTER_ZIPKIN_ENDPOINT": server.url + "/collector/spans"}, max_batch_size=3
    )
    assert from_env.max_batch_size == 3
    from_env.report(spans())
    from_env.close()
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url + "/gw")
    default = sdk.ZipkinReporter()
    assert default.endpoint == server.url + "/gw/api/v2/spans"
    default.close()
    host_only = sdk.ZipkinReporter(make_config(server.url), endpoint="http://127.0.0.1:9")
    assert host_only.endpoint == "http://127.0.0.1:9"
    host_only.close()


async def test_async_reporter(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    server.defaults["/api/v2/spans"] = Reply(202, b"")
    server.defaults["/elsewhere"] = Reply(202, b"")
    async with sdk.AsyncZipkinReporter(make_config(server.url), compression="gzip") as reporter:
        await reporter.report(spans(2))
        assert "AsyncZipkinReporter" in repr(reporter)
    assert len(body(server.seen[-1])) == 2
    client = sdk.AsyncZipkinClient(make_config(server.url))
    shared = sdk.AsyncZipkinReporter(client)
    await shared.report(spans())
    await shared.aclose()  # shared transport: no-op
    other = client.create_reporter(endpoint=server.url + "/elsewhere")
    await other.report(spans())
    assert server.seen[-1].path == "/elsewhere"
    await client.aclose()
    env_reporter = sdk.AsyncZipkinReporter.from_env({sdk.ENV_PREFIX + "URL": server.url})
    server.enqueue(
        "/api/v2/spans", Reply(400, "Expected a JSON_V2 encoded list", content_type="text/*")
    )
    with pytest.raises(sdk.ValidationError, match="JSON_V2"):
        await env_reporter.report(spans())
    await env_reporter.close()
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    default = sdk.AsyncZipkinReporter()
    await default.aclose()
