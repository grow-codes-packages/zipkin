"""The tracing preset against the real Zipkin 3.6.1: ZipkinSpanExporter, W3C + B3 propagation,
span helpers and the tracing conformance scenarios. Spans are exported with OpenTelemetry and
read back through the query API."""

from __future__ import annotations

import os
import secrets
import time
from collections.abc import Iterator
from typing import Any

import pytest
from factories import CONFORMANCE_SERVICE, TRACING_CAPABILITIES, tracing_target
from opentelemetry import context as otel_context
from opentelemetry import trace as otel_trace
from opentelemetry.sdk.trace import ReadableSpan
from opentelemetry.sdk.trace.export import SpanExportResult
from opentelemetry.trace import SpanKind, Status, StatusCode

import grow_zipkin as sdk
from grow_zipkin.testing import ConformanceRunner, assert_scenario, pytest_params

RUN = "".join(c for c in os.environ.get("GROW_TEST_RUN_ID", "local").lower() if c.isalnum())[:16]
TAG = f"{RUN}-{secrets.token_hex(3)}"
GATEWAY = f"sdk-py-otel-gw-{TAG}"
WORKER = f"sdk-py-otel-wk-{TAG}"
PEER = f"sdk-py-otel-peer-{TAG}"
HELPERS = f"sdk-py-helpers-{TAG}"


def now_ms() -> int:
    return int(time.time() * 1000)


@pytest.fixture(scope="module")
def exported(env: dict[str, str]) -> Iterator[tuple[sdk.ZipkinClient, str]]:
    client = sdk.ZipkinClient.from_env(env)
    gw = client.init_tracing(
        service_name=GATEWAY,
        service_version="1.2.3",
        environment="sdk-test",
        set_global=False,
        env=env,
    )
    wk = client.init_tracing(
        sdk.TracingConfig(WORKER, set_global=False, compression="none"), env=env
    )
    assert gw.config.protocol == "native"
    assert list(gw.config.propagators) == ["b3", "tracecontext", "baggage"]
    root = gw.tracer("sdk-int", "0.1.0").start_span(
        "GET /orders",
        kind=SpanKind.CLIENT,
        attributes={"peer.service": PEER, "server.port": 9000, "order.id": TAG},
    )
    headers: dict[str, str] = {}
    gw.propagator.inject(headers, context=otel_trace.set_span_in_context(root))
    assert headers.get("traceparent")
    assert headers.get("b3")
    remote = wk.propagator.extract(headers)
    server = wk.tracer("sdk-int").start_span(
        "handle /orders",
        context=remote,
        kind=SpanKind.SERVER,
        attributes={
            "int.attr": 42,
            "double.attr": 1.5,
            "bool.attr": True,
            "array.attr": ["a", "b"],
        },
    )
    server.add_event("cache.miss", {"cache.key": "k1"})
    db = wk.tracer("sdk-int").start_span(
        "db.query", context=otel_trace.set_span_in_context(server, remote)
    )
    time.sleep(0.02)
    db.end()
    server.set_status(Status(StatusCode.ERROR, "boom"))
    server.end()
    root.set_status(Status(StatusCode.OK))
    root.end()
    trace_id = format(root.get_span_context().trace_id, "032x")
    # A CLIENT call without an instrumented server: the edge comes from remoteEndpoint.
    gw.tracer("sdk-int").start_span(
        "call peer", kind=SpanKind.CLIENT, attributes={"peer.service": PEER}
    ).end()
    gw.force_flush()
    wk.force_flush()
    yield client, trace_id
    client.close()


def find(trace: sdk.Trace, name: str) -> sdk.Span:
    for span in trace.spans:
        if span.name == name:
            return span
    raise AssertionError(f"span {name} missing in {[s.name for s in trace.spans]}")


def raw(spans: list[sdk.ZipkinSpan] | None, name: str) -> sdk.ZipkinSpan:
    assert spans is not None
    for span in spans:
        if span.name == name:
            return span
    raise AssertionError(f"zipkin span {name} missing")


def test_span_tree_across_both_services(exported: tuple[sdk.ZipkinClient, str]) -> None:
    client, trace_id = exported
    trace = client.query.wait_for_trace(trace_id, min_spans=3, interval_s=0.2)
    assert trace.trace_id == trace_id
    tree = sdk.build_tree(trace.spans)
    assert len(tree) == 1
    assert tree[0].span.name == "get /orders"
    assert [c.span.name for c in tree[0].children] == ["handle /orders"]
    assert [c.span.name for c in tree[0].children[0].children] == ["db.query"]
    server = find(trace, "handle /orders")
    assert (server.service_name, server.kind) == (WORKER, "server")
    assert server.status == sdk.SpanStatus("error", "boom")
    assert [(e.name, dict(e.attributes)) for e in server.events] == [
        ("cache.miss", {"cache.key": "k1"})
    ]
    assert find(trace, "get /orders").status.code == "ok"
    assert find(trace, "db.query").kind == "internal"


def test_wire_mapping(exported: tuple[sdk.ZipkinClient, str]) -> None:
    client, trace_id = exported
    spans = client.query.get_trace_spans(trace_id)
    gw = raw(spans, "get /orders")
    assert gw.kind == "CLIENT"
    assert gw.local_endpoint == sdk.ZipkinEndpoint(GATEWAY)
    assert gw.remote_endpoint == sdk.ZipkinEndpoint(PEER)
    assert gw.parent_id is None
    assert gw.tags["otel.status_code"] == "OK"
    assert gw.tags["otel.scope.name"] == "sdk-int"
    assert gw.tags["otel.scope.version"] == "0.1.0"
    assert "service.version" not in gw.tags  # resource attributes are not copied
    assert gw.tags["server.port"] == "9000"
    wk = raw(spans, "handle /orders")
    assert wk.kind == "SERVER"
    assert wk.parent_id == gw.id
    assert wk.remote_endpoint is None
    assert wk.tags["int.attr"] == "42"
    assert wk.tags["double.attr"] == "1.5"
    assert wk.tags["bool.attr"] == "true"
    assert wk.tags["array.attr"] == '["a","b"]'
    assert wk.tags["otel.status_code"] == "ERROR"
    assert wk.tags["error"] == "boom"
    assert wk.annotations[0].value == '{"cache.miss":{"cache.key":"k1"}}'
    assert wk.debug is False
    db = raw(spans, "db.query")
    assert db.kind is None
    assert (db.duration or 0) >= 15_000


def test_dependencies_and_names_from_exported_spans(exported: tuple[sdk.ZipkinClient, str]) -> None:
    client, _ = exported
    links = client.query.get_dependencies(end_ts=now_ms() + 60_000, lookback_s=3600)
    assert [link for link in links if link.parent == GATEWAY] == [
        sdk.ZipkinDependencyLink(GATEWAY, PEER, 1, 0),
        sdk.ZipkinDependencyLink(GATEWAY, WORKER, 1, 1),
    ]
    assert client.query.list_remote_services(GATEWAY) == [PEER]
    assert WORKER in client.query.list_services()
    assert client.query.list_operations(WORKER) == ["db.query", "handle /orders"]


def test_standalone_exporter_round_trip(exported: tuple[sdk.ZipkinClient, str]) -> None:
    client, _ = exported
    handle = sdk.init_tracing(service_name=WORKER, set_global=False, env=False)
    exporter = sdk.ZipkinSpanExporter(config=client.config, compression="none")
    try:
        span = handle.tracer("direct").start_span("direct-export")
        span.end()
        assert isinstance(span, ReadableSpan)
        mapped = sdk.to_zipkin_span(span)
        assert exporter.export([span]) is SpanExportResult.SUCCESS
        back = client.query.get_trace_spans(mapped.trace_id)
        assert back is not None
        assert back[0].id == mapped.id
        assert back[0].name == "direct-export"
    finally:
        exporter.shutdown()
        handle.shutdown()


def test_tracing_config_from_env_reads_the_zipkin_endpoint(env: dict[str, str]) -> None:
    cfg = sdk.tracing_config_from_env(service_name="x", env=env)
    expected = (
        env.get("GROW_ZIPKIN_ENDPOINT")
        or env.get("OTEL_EXPORTER_ZIPKIN_ENDPOINT")
        or env["GROW_ZIPKIN_URL"].rstrip("/") + "/api/v2/spans"
    )
    assert cfg.endpoint == expected


def test_span_helpers_with_the_global_provider(env: dict[str, str]) -> None:
    client = sdk.ZipkinClient.from_env(env)
    handle = sdk.init_tracing(service_name=HELPERS, env=env)
    trace_id = ""
    try:
        with sdk.with_span("helper-root", attributes={"helper": True}, kind="server"):
            trace_id = sdk.current_trace_id() or ""
            assert len(trace_id) == 32
            assert len(sdk.current_span_id() or "") == 16
            assert sdk.trace_fields()["trace_id"] == trace_id
            sdk.add_event("helper.event", {"n": 1})
            carrier: dict[str, str] = {}
            sdk.inject(carrier)
            assert trace_id in carrier["traceparent"]
            assert carrier["b3"].startswith(trace_id)
            remote = sdk.extract(carrier)
            assert (
                format(otel_trace.get_current_span(remote).get_span_context().trace_id, "032x")
                == trace_id
            )

            @sdk.traced("helper-traced")
            def work(x: int) -> int:
                return x * 2

            assert work(21) == 42
            with sdk.with_span("helper-failing"):
                err = RuntimeError("helper boom")
                sdk.record_exception(err)
                sdk.set_error(err)
            with pytest.raises(RuntimeError, match="thrown"), sdk.with_span("helper-throws"):
                raise RuntimeError("thrown")
        assert otel_trace.get_current_span(otel_context.get_current()) is otel_trace.INVALID_SPAN
        handle.force_flush()
        trace = client.query.wait_for_trace(trace_id, min_spans=4, interval_s=0.2)
        root = find(trace, "helper-root")
        assert root.attributes["helper"] == "true"
        assert next(e for e in root.events if e.name == "helper.event").attributes == {"n": 1}
        assert find(trace, "helper-traced").parent_span_id == root.span_id
        failing = find(trace, "helper-failing")
        assert failing.status.code == "error"
        assert any(e.name == "exception" for e in failing.events)
        assert find(trace, "helper-throws").status.code == "error"
    finally:
        handle.shutdown()
        client.close()


@pytest.mark.parametrize("scenario", pytest_params(categories=["tracing"]))
def test_tracing_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"tracing": lambda ctx: tracing_target(env, ctx.scenario)},
        capabilities=TRACING_CAPABILITIES,
        service_name=CONFORMANCE_SERVICE,
    )
    assert_scenario(runner, scenario)
