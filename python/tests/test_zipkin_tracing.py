"""ZipkinSpanExporter mapping, the tracing preset (init_tracing, env precedence, propagators,
OTLP alternative) and the Zipkin conformance scenarios against a mock server."""

from __future__ import annotations

import dataclasses
import gzip
import json
import time
from collections.abc import Iterator
from typing import Any

import pytest
from opentelemetry import trace as otel_trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import ReadableSpan, SpanLimits, TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor, SpanExportResult
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import SpanKind, Status, StatusCode

import grow_zipkin as sdk
from _support import MockServer, Reply, make_config

TOKEN = "tracing-token-5678"


@pytest.fixture
def recorded() -> Iterator[tuple[TracerProvider, InMemorySpanExporter]]:
    memory = InMemorySpanExporter()
    provider = TracerProvider(
        resource=Resource.create({"service.name": "svc-a", "service.version": "1"}),
        span_limits=SpanLimits(max_span_attributes=8, max_events=1),
    )
    provider.add_span_processor(SimpleSpanProcessor(memory))
    yield provider, memory
    provider.shutdown()


def sent_spans(server: MockServer, path: str = "/api/v2/spans") -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for seen in server.seen:
        if seen.path.split("?")[0] != path:
            continue
        raw = seen.body
        if seen.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        out.extend(json.loads(raw))
    return out


# --------------------------------------------------------------------------- mapping


def test_to_zipkin_span_mapping(recorded: tuple[TracerProvider, InMemorySpanExporter]) -> None:
    provider, memory = recorded
    tracer = provider.get_tracer("scope-a", "0.1.0")
    with tracer.start_as_current_span(
        "GET /orders",
        kind=SpanKind.CLIENT,
        attributes={"peer.service": "peer", "server.port": 9000, "f": 1.5, "arr": ["a", "b"]},
    ) as root:
        root.set_status(Status(StatusCode.OK))
        with tracer.start_as_current_span("handle", kind=SpanKind.SERVER) as child:
            child.add_event("dropped")  # max_events=1 keeps the newest event
            child.add_event("cache.miss", {"cache.key": "k1", "n": [1, 2]})
            child.set_status(Status(StatusCode.ERROR, "boom"))
            for i in range(10):
                child.set_attribute(f"a{i}", i)
    child_span, root_span = memory.get_finished_spans()
    r = sdk.to_zipkin_span(root_span).to_json()
    assert r["kind"] == "CLIENT" and "parentId" not in r
    assert r["localEndpoint"] == {"serviceName": "svc-a"}
    assert r["remoteEndpoint"] == {"serviceName": "peer"}
    assert r["tags"] == {
        "peer.service": "peer",
        "server.port": "9000",
        "f": "1.5",
        "arr": '["a","b"]',
        "otel.scope.name": "scope-a",
        "otel.scope.version": "0.1.0",
        "otel.status_code": "OK",
    }
    assert r["duration"] >= 1 and len(r["traceId"]) == 32
    c = sdk.to_zipkin_span(child_span).to_json()
    assert c["kind"] == "SERVER" and c["parentId"] == r["id"]
    assert "remoteEndpoint" not in c
    assert c["annotations"][0]["value"] == '{"cache.miss":{"cache.key":"k1","n":[1,2]}}'
    assert c["tags"]["otel.status_code"] == "ERROR" and c["tags"]["error"] == "boom"
    assert c["tags"]["otel.dropped_attributes_count"] == "2"
    assert c["tags"]["otel.dropped_events_count"] == "1"
    assert "debug" not in c and "shared" not in c


def test_to_zipkin_span_defaults() -> None:
    memory = InMemorySpanExporter()
    provider = TracerProvider(resource=Resource({}))
    provider.add_span_processor(SimpleSpanProcessor(memory))
    tracer = provider.get_tracer("")
    with tracer.start_as_current_span("internal") as s:
        s.add_event("plain")
        s.set_status(Status(StatusCode.ERROR))
    tracer.start_span("producer", kind=SpanKind.PRODUCER).end()
    tracer.start_span(
        "consumer", kind=SpanKind.CONSUMER, attributes={"network.peer.address": "1.2.3.4"}
    ).end()
    internal, producer, consumer = (
        sdk.to_zipkin_span(x).to_json() for x in memory.get_finished_spans()
    )
    assert internal["localEndpoint"] == {"serviceName": sdk.UNKNOWN_SERVICE}
    assert "kind" not in internal
    assert internal["annotations"][0]["value"] == "plain"
    assert internal["tags"] == {"otel.status_code": "ERROR", "error": ""}
    assert producer["kind"] == "PRODUCER" and "remoteEndpoint" not in producer
    assert consumer["kind"] == "CONSUMER" and "remoteEndpoint" not in consumer
    assert sdk.attribute_to_tag(("x", 1)) == '["x",1]'
    assert sdk.attribute_to_tag(True) == "true"
    provider.shutdown()


def test_exporter_success_failure_and_shutdown(
    server: MockServer, recorded: tuple[TracerProvider, InMemorySpanExporter]
) -> None:
    provider, memory = recorded
    provider.get_tracer("t").start_span("one").end()
    finished: list[ReadableSpan] = list(memory.get_finished_spans())
    server.enqueue("/api/v2/spans", Reply(202, b""), Reply(400, "bad", content_type="text/*"))
    errors: list[BaseException] = []
    exporter = sdk.ZipkinSpanExporter(config=make_config(server.url), on_error=errors.append)
    assert exporter.export(finished) is SpanExportResult.SUCCESS
    assert server.seen[0].headers["Content-Encoding"] == "gzip"
    assert exporter.export(finished) is SpanExportResult.FAILURE
    assert isinstance(errors[0], sdk.ValidationError)
    assert exporter.force_flush() is True
    exporter.shutdown()
    exporter.shutdown()
    assert exporter.export(finished) is SpanExportResult.FAILURE

    def broken(_: BaseException) -> None:
        raise RuntimeError("hook")

    server.enqueue("/api/v2/spans", Reply(400, "bad", content_type="text/*"))
    shared = sdk.ZipkinReporter(make_config(server.url))
    quiet = sdk.ZipkinSpanExporter(reporter=shared, on_error=broken)
    assert quiet.export(finished) is SpanExportResult.FAILURE
    quiet.shutdown()  # the reporter is not owned
    server.enqueue("/api/v2/spans", Reply(202, b""))
    shared.report([])
    shared.close()
    silent = sdk.ZipkinSpanExporter(
        config=make_config("http://127.0.0.1:9", retry=sdk.RetryPolicy(max_retries=0))
    )
    assert silent.export(finished) is SpanExportResult.FAILURE
    silent.shutdown()


def test_exporter_default_config(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    exporter = sdk.ZipkinSpanExporter(compression="none", max_batch_size=7)
    assert exporter.reporter.endpoint == server.url + "/api/v2/spans"
    assert exporter.reporter.max_batch_size == 7
    exporter.shutdown()


# --------------------------------------------------------------------------- preset


def test_order_propagators() -> None:
    assert sdk.order_propagators([" TraceContext", "baggage", "b3multi", "b3", "b3"]) == [
        "b3multi",
        "b3",
        "tracecontext",
        "baggage",
    ]


def test_tracing_config_from_env_native() -> None:
    env = {
        "GROW_ZIPKIN_URL": "http://zipkin:9411/",
        "OTEL_EXPORTER_ZIPKIN_TIMEOUT": "10s",
        "GROW_ZIPKIN_COMPRESSION": "NONE",
        "OTEL_RESOURCE_ATTRIBUTES": "service.name=from-res,team=a%20b,,",
        "OTEL_TRACES_SAMPLER": "parentbased_traceidratio",
        "OTEL_TRACES_SAMPLER_ARG": "0.25",
        "OTEL_PROPAGATORS": "tracecontext, b3multi,",
    }
    cfg = sdk.tracing_config_from_env(env=env)
    assert cfg.protocol == "native"
    assert cfg.endpoint == "http://zipkin:9411/api/v2/spans"
    assert cfg.batch.export_timeout_s == 10.0
    assert cfg.compression == "none"
    assert cfg.service_name == "from-res"
    assert dict(cfg.resource_attributes) == {"team": "a b"}
    assert (cfg.sampler, cfg.sampler_ratio) == ("parentbased_traceidratio", 0.25)
    assert cfg.propagators == ("tracecontext", "b3multi")
    env2 = {
        "OTEL_SERVICE_NAME": "named",
        "OTEL_EXPORTER_ZIPKIN_ENDPOINT": "http://otel:9411/api/v2/spans",
        "GROW_ZIPKIN_ENDPOINT": "http://grow:9411/api/v2/spans",
        "OTEL_TRACES_SAMPLER": "always_off",
        "OTEL_EXPORTER_ZIPKIN_TIMEOUT": "1500",
    }
    explicit = sdk.tracing_config_from_env(
        sdk.TracingConfig(
            "explicit", compression="none", batch=sdk.SpanBatchOptions(max_queue_size=10)
        ),
        env=env2,
        sampler="always_on",
        service_version=None,
    )
    assert explicit.service_name == "explicit"
    assert explicit.endpoint == "http://grow:9411/api/v2/spans"
    assert explicit.sampler == "always_on"
    assert explicit.batch == sdk.SpanBatchOptions(max_queue_size=10, export_timeout_s=1.5)
    assert sdk.tracing_config_from_env(service_name=" ", env=env2).service_name == "named"
    assert sdk.tracing_config_from_env(env={}).service_name == ""
    kept = sdk.tracing_config_from_env(
        batch=sdk.SpanBatchOptions(export_timeout_s=3), env={"OTEL_EXPORTER_ZIPKIN_TIMEOUT": "9s"}
    )
    assert kept.batch.export_timeout_s == 3


def test_tracing_config_from_env_otlp() -> None:
    env = {
        "GROW_ZIPKIN_PROTOCOL": "HTTP/PROTOBUF",
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://col:4318",
    }
    assert sdk.tracing_config_from_env(env=env).endpoint == "http://col:4318/v1/traces"
    env["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"] = "http://col:4318/custom"
    assert sdk.tracing_config_from_env(env=env).endpoint == "http://col:4318/custom"
    grpc = sdk.tracing_config_from_env(
        protocol="grpc", env={"OTEL_EXPORTER_OTLP_ENDPOINT": "http://col:4317"}
    )
    assert grpc.endpoint == "http://col:4317"
    assert sdk.resolve_otlp_endpoint("http/protobuf", "http://c:1/") == "http://c:1/v1/traces"
    assert sdk.resolve_otlp_endpoint("http/protobuf", "http://c:1/p") == "http://c:1/p"
    assert sdk.resolve_otlp_endpoint("grpc", "http://c:1") == "http://c:1"


@pytest.mark.parametrize(
    "env",
    [
        {"GROW_ZIPKIN_PROTOCOL": "thrift"},
        {"OTEL_EXPORTER_ZIPKIN_TIMEOUT": "soon"},
        {"OTEL_EXPORTER_ZIPKIN_TIMEOUT": "0"},
        {"GROW_ZIPKIN_COMPRESSION": "zstd"},
        {"OTEL_RESOURCE_ATTRIBUTES": "novalue"},
        {"OTEL_RESOURCE_ATTRIBUTES": "a=%ff"},
        {"OTEL_TRACES_SAMPLER": "sometimes"},
        {"OTEL_TRACES_SAMPLER": "traceidratio", "OTEL_TRACES_SAMPLER_ARG": "2"},
        {"OTEL_TRACES_SAMPLER": "traceidratio", "OTEL_TRACES_SAMPLER_ARG": "x"},
    ],
)
def test_tracing_config_from_env_invalid(env: dict[str, str]) -> None:
    with pytest.raises(sdk.ConfigError) as info:
        sdk.tracing_config_from_env(env=env)
    name = next(iter(env)) if len(env) == 1 else "OTEL_TRACES_SAMPLER_ARG"
    assert name in str(info.value)
    for value in env.values():
        if value not in ("traceidratio",):
            assert f": {value}" not in str(info.value)


def test_init_tracing_native_end_to_end(server: MockServer) -> None:
    server.defaults["/api/v2/spans"] = Reply(202, b"")
    errors: list[BaseException] = []
    handle = sdk.init_tracing(
        service_name="svc-native",
        env={"GROW_ZIPKIN_URL": server.url, "GROW_ZIPKIN_COMPRESSION": "none"},
        client_config=None,
        headers={"Authorization": f"Bearer {TOKEN}"},
        set_global=False,
        on_export_error=errors.append,
    )
    assert isinstance(handle, sdk.ZipkinTracingHandle)
    assert handle.endpoint == server.url + "/api/v2/spans"
    assert handle.config.propagators == ("b3", "tracecontext", "baggage")
    with handle.tracer("t").start_as_current_span("root", kind=SpanKind.SERVER):
        carrier: dict[str, str] = {}
        handle.propagator.inject(carrier)
        assert "traceparent" in carrier and "b3" in carrier
    handle.force_flush()
    sent = sent_spans(server)
    assert sent[0]["name"] == "root" and sent[0]["localEndpoint"] == {"serviceName": "svc-native"}
    assert server.seen[0].headers["Authorization"] == f"Bearer {TOKEN}"
    assert "Content-Encoding" not in server.seen[0].headers
    handle.shutdown()
    assert errors == []


def test_init_tracing_env_false_and_client_config(server: MockServer) -> None:
    server.defaults["/custom"] = Reply(202, b"")
    base = make_config(server.url, headers={"X-Base": "1"}, tls=sdk.TlsConfig(ca_cert_path=None))
    handle = sdk.init_tracing(
        sdk.TracingConfig(
            "svc-b",
            endpoint=server.url + "/custom",
            tls=sdk.TlsConfig(insecure_skip_verify=False, ca_cert_pem=None),
            set_global=False,
            propagators=("tracecontext",),
        ),
        env=False,
        client_config=base,
    )
    assert handle.config.protocol == "native"  # plain TracingConfig default → Zipkin default
    assert handle.config.propagators == ("tracecontext",)
    handle.tracer("t").start_span("x").end()
    handle.force_flush()
    assert server.seen[-1].path == "/custom" and server.seen[-1].headers["X-Base"] == "1"
    handle.shutdown()
    default = sdk.init_tracing(service_name="svc-c", env=False, set_global=False)
    assert default.endpoint == "http://127.0.0.1:9411/api/v2/spans"
    default.shutdown()
    with pytest.raises(sdk.ConfigError):
        sdk.init_tracing(env=False, set_global=False)  # no service name


def test_init_tracing_merges_tls() -> None:
    factory = sdk.create_zipkin_exporter_factory(
        make_config("http://h:9411", tls=sdk.TlsConfig(ca_cert_path="/base.pem"))
    )
    cfg = sdk.ZipkinTracingConfig(
        "svc",
        tls=sdk.TlsConfig(insecure_skip_verify=True),
        batch=sdk.SpanBatchOptions(export_timeout_s=4),
    )
    exporter = factory(cfg)
    assert isinstance(exporter, sdk.ZipkinSpanExporter)
    reporter_config = exporter.reporter._config
    assert reporter_config.tls == sdk.TlsConfig(ca_cert_path="/base.pem", insecure_skip_verify=True)
    assert (reporter_config.request_timeout, reporter_config.total_timeout) == (4, 4)
    exporter.shutdown()
    plain = sdk.create_zipkin_exporter_factory()(sdk.ZipkinTracingConfig("svc"))
    plain.shutdown()


def test_init_tracing_otlp_exporters(tmp_path: Any) -> None:
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (
        OTLPSpanExporter as GrpcExporter,
    )
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
        OTLPSpanExporter as HttpExporter,
    )

    factory = sdk.create_zipkin_exporter_factory()
    http = factory(
        sdk.ZipkinTracingConfig(
            "svc",
            protocol="http/protobuf",
            endpoint="http://collector:4318",
            headers={"Authorization": "x"},
            tls=sdk.TlsConfig(ca_cert_pem="PEM", insecure_skip_verify=True),
            compression="none",
        )
    )
    assert isinstance(http, HttpExporter)
    http.shutdown()
    pem = tmp_path / "ca.pem"
    pem.write_text("not a real cert")
    grpc = factory(
        sdk.ZipkinTracingConfig("svc", protocol="grpc", endpoint="http://collector:4317")
    )
    assert isinstance(grpc, GrpcExporter)
    grpc.shutdown()
    factory(
        sdk.ZipkinTracingConfig(
            "svc",
            protocol="grpc",
            endpoint="https://collector:4317",
            tls=sdk.TlsConfig(ca_cert_path=str(pem)),
        )
    ).shutdown()
    with pytest.raises(sdk.ConfigError, match="insecure_skip_verify"):
        factory(
            sdk.ZipkinTracingConfig(
                "svc",
                protocol="grpc",
                endpoint="https://c:4317",
                tls=sdk.TlsConfig(insecure_skip_verify=True),
            )
        )
    with pytest.raises(sdk.ConfigError, match="TLS file"):
        factory(
            sdk.ZipkinTracingConfig(
                "svc",
                protocol="grpc",
                endpoint="https://c:4317",
                tls=sdk.TlsConfig(ca_cert_path="/nope"),
            )
        )
    for endpoint in (None, "http://zipkin:9411/api/v2/spans"):
        with pytest.raises(sdk.ConfigError, match="Collector"):
            factory(sdk.ZipkinTracingConfig("svc", protocol="grpc", endpoint=endpoint))
    otlp = sdk.init_tracing(
        service_name="svc",
        protocol="http/protobuf",
        env={"OTEL_EXPORTER_OTLP_ENDPOINT": "http://collector:4318"},
        set_global=False,
    )
    assert otlp.endpoint == "http://collector:4318/v1/traces"
    otlp.shutdown()


def test_init_tracing_global_and_helpers(server: MockServer) -> None:
    server.defaults["/api/v2/spans"] = Reply(202, b"")
    handle = sdk.init_tracing(service_name="svc-global", env={"GROW_ZIPKIN_URL": server.url})
    try:
        with sdk.with_span("outer", attributes={"k": "v"}):
            trace_id = sdk.current_trace_id()
            carrier: dict[str, str] = {}
            sdk.inject(carrier)
            assert carrier["b3"].startswith(str(trace_id))
        handle.force_flush()
    finally:
        handle.shutdown()
        otel_trace.set_tracer_provider(otel_trace.NoOpTracerProvider())  # best effort
    assert sent_spans(server)[0]["traceId"] == trace_id


# --------------------------------------------------------------------------- conformance


class _FakeZipkin:
    """A tiny in-process Zipkin: stores reported spans and serves them back."""

    def __init__(self, server: MockServer) -> None:
        self.server = server

    def spans(self) -> list[dict[str, Any]]:
        return sent_spans(self.server)


def test_tracing_conformance_against_mock(server: MockServer) -> None:
    from grow_zipkin.testing import ConformanceRunner, TracingTarget

    server.defaults["/api/v2/spans"] = Reply(202, b"")
    client = sdk.ZipkinClient(make_config(server.url))
    fake = _FakeZipkin(server)

    class Query(sdk.ZipkinQueryClient):
        def get_trace_spans(self, trace_id: str, **_: Any) -> list[sdk.ZipkinSpan] | None:
            found = [sdk.ZipkinSpan.from_json(s) for s in fake.spans() if s["traceId"] == trace_id]
            return found or None

        def list_services(self, **_: Any) -> list[str]:
            return sorted({s["localEndpoint"]["serviceName"] for s in fake.spans()})

    runner = ConformanceRunner(
        {
            "tracing": lambda ctx: TracingTarget(
                client.init_tracing(service_name=str(ctx.service_name), set_global=False, env={}),
                Query(client),
            )
        },
        capabilities={"query", "services"},
        service_name="conformance-py-unit",
    )
    report = runner.run()
    assert report.ok, report.summary()
    assert len(report.passed) >= 2
    client.close()


def test_handle_endpoint_attribute_defaults() -> None:
    assert sdk.ZipkinTracingHandle.endpoint == ""
    assert dataclasses.is_dataclass(sdk.ZipkinTracingConfig)
    time.sleep(0)
