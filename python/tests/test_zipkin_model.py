"""Zipkin span model: ingest normalization, decoding and conversion to the SPEC §5 model."""

from __future__ import annotations

from typing import Any

import pytest

import grow_zipkin as sdk
from _fixtures import response_body

TRACE = "5af7183fb1d4cf5fad1c0df61d0a9b8c"


def test_tag_value_forms() -> None:
    assert sdk.tag_value("x") == "x"
    assert sdk.tag_value(True) == "true"
    assert sdk.tag_value(False) == "false"
    assert sdk.tag_value(7) == "7"
    assert sdk.tag_value(1.5) == "1.5"
    assert sdk.tag_value(2.0) == "2"
    assert sdk.tag_value(1e-7) == "0.0000001"
    assert sdk.tag_value(1e22) == "10000000000000000000000"
    assert sdk.tag_value(float("nan")) == "NaN"
    assert sdk.tag_value(float("inf")) == "Infinity"
    assert sdk.tag_value(float("-inf")) == "-Infinity"
    assert sdk.tag_value(None) == ""
    assert sdk.tag_value(["a", 1.0, 2.5, True, None]) == '["a",1,2.5,true,null]'
    assert sdk.tag_value({"k": ("é", float("nan"))}) == '{"k":["é",null]}'
    assert sdk.tag_value({1: object}).startswith('{"1":"<class')


def test_trace_id_validation() -> None:
    assert sdk.zipkin_trace_id(" " + TRACE.upper() + " ") == TRACE
    assert sdk.zipkin_trace_id("463AC35CAD1C0DF6") == "463ac35cad1c0df6"
    for bad in ("zz", "0" * 16, "a" * 20, "", 5):
        with pytest.raises(sdk.ValidationError, match="op: trace id"):
            sdk.zipkin_trace_id(bad, "op")  # type: ignore[arg-type]


def test_normalize_span_from_mapping_and_dataclass() -> None:
    span = sdk.normalize_zipkin_span(
        {
            "traceId": TRACE.upper(),
            "parentId": "000000000000000A",
            "id": "000000000000000B",
            "kind": "client",
            "name": "Get /Orders",
            "timestamp": 1_000_000.4,
            "duration": 0,
            "localEndpoint": {"serviceName": "svc", "ipv4": "", "port": 0},
            "remoteEndpoint": {"serviceName": "", "ipv6": "::1", "port": 9000},
            "annotations": [{"timestamp": 1_000_001, "value": "cache.miss"}],
            "tags": {"b": True, "n": 7, "f": 1.5, "skip": None, "s": "x"},
            "debug": True,
            "shared": True,
        }
    )
    assert span.to_json() == {
        "traceId": TRACE,
        "parentId": "000000000000000a",
        "id": "000000000000000b",
        "kind": "CLIENT",
        "name": "Get /Orders",
        "timestamp": 1_000_000,
        "localEndpoint": {"serviceName": "svc"},
        "remoteEndpoint": {"ipv6": "::1", "port": 9000},
        "annotations": [{"timestamp": 1_000_001, "value": "cache.miss"}],
        "tags": {"b": "true", "n": "7", "f": "1.5", "s": "x"},
        "debug": True,
        "shared": True,
    }
    minimal = sdk.normalize_zipkin_span(
        sdk.ZipkinSpan(
            trace_id="a" * 16,
            id="b" * 16,
            local_endpoint=sdk.ZipkinEndpoint(),
            annotations=[sdk.ZipkinAnnotation(5, "x")],
        )
    )
    assert minimal.to_json() == {
        "traceId": "a" * 16,
        "id": "b" * 16,
        "annotations": [{"timestamp": 5, "value": "x"}],
    }
    assert minimal.local_endpoint is None


@pytest.mark.parametrize(
    ("span", "fragment"),
    [
        ({"traceId": "zz", "id": "b" * 16}, "trace id"),
        ({"id": "b" * 16}, "traceId and id"),
        ({"traceId": "a" * 16, "id": "0" * 16}, "span id"),
        ({"traceId": "a" * 16, "id": "b" * 16, "parentId": "xyz"}, "parentId"),
        ({"traceId": "a" * 16, "id": "b" * 16, "kind": "internal"}, "kind"),
        ({"traceId": "a" * 16, "id": "b" * 16, "timestamp": -1}, "timestamp"),
        ({"traceId": "a" * 16, "id": "b" * 16, "duration": "1"}, "duration"),
        ({"traceId": "a" * 16, "id": "b" * 16, "duration": True}, "duration"),
        ({"traceId": "a" * 16, "id": "b" * 16, "localEndpoint": "svc"}, "endpoint"),
        ({"traceId": "a" * 16, "id": "b" * 16, "localEndpoint": {"port": 70000}}, "port"),
        ({"traceId": "a" * 16, "id": "b" * 16, "localEndpoint": {"port": "80"}}, "port"),
        (
            {"traceId": "a" * 16, "id": "b" * 16, "annotations": [{"timestamp": 1, "value": ""}]},
            "annotation value",
        ),
        (
            {"traceId": "a" * 16, "id": "b" * 16, "annotations": [{"value": "x"}]},
            "annotation timestamp",
        ),
        ({"traceId": "a" * 16, "id": "b" * 16, "annotations": ["x"]}, "annotation value"),
    ],
)
def test_normalize_span_rejects(span: dict[str, Any], fragment: str) -> None:
    with pytest.raises(sdk.ValidationError, match=fragment):
        sdk.normalize_zipkin_span(span)


def test_normalize_span_rejects_other_types() -> None:
    with pytest.raises(sdk.ValidationError, match="ZipkinSpan or a mapping"):
        sdk.normalize_zipkin_span("span")  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError, match="kind"):
        sdk.normalize_zipkin_span(sdk.ZipkinSpan("a" * 16, "b" * 16, kind=5))  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError, match="port"):
        sdk.normalize_zipkin_span(
            sdk.ZipkinSpan("a" * 16, "b" * 16, local_endpoint=sdk.ZipkinEndpoint(port=-1))
        )
    with pytest.raises(sdk.ValidationError):
        sdk.ZipkinSpan.from_json({"traceId": 1, "id": "x"})


def test_decode_helpers() -> None:
    spans = sdk.zipkin_spans_from_body(response_body("getTrace.ok"))
    assert [s.id for s in spans][:1] == ["0000000000000001"]
    assert spans[0].local_endpoint == sdk.ZipkinEndpoint("grow-frontend", "10.0.0.1", None, 8080)
    assert sdk.zipkin_spans_from_body({"x": 1}) == []
    assert sdk.zipkin_spans_from_body([{"traceId": "a"}, 1]) == []
    traces = sdk.zipkin_traces_from_body([[], [{"traceId": "a" * 16, "id": "b" * 16}], "x"])
    assert len(traces) == 1
    assert sdk.zipkin_traces_from_body(None) == []
    assert sdk.strings_from_body(["a", 1, "b"]) == ["a", "b"]
    assert sdk.strings_from_body("a") == []
    links = sdk.dependencies_from_body(response_body("getDependencies.ok"))
    assert links == [
        sdk.ZipkinDependencyLink("grow-frontend", "grow-backend", 1, 1),
        sdk.ZipkinDependencyLink("grow-mixed", "kafka-broker", 1, 0),
    ]
    assert sdk.dependencies_from_body(
        [{"parent": "a", "child": "b", "callCount": True}, {"parent": 1}]
    ) == [sdk.ZipkinDependencyLink("a", "b", 0, 0)]
    assert sdk.dependencies_from_body({}) == []
    endpoint = sdk.ZipkinEndpoint.from_json({"serviceName": "", "port": True})
    assert endpoint == sdk.ZipkinEndpoint()
    assert sdk.ZipkinEndpoint.from_json(endpoint) is endpoint
    span = sdk.ZipkinSpan.from_json(
        {"traceId": "a" * 16, "id": "b" * 16, "annotations": [sdk.ZipkinAnnotation(1, "x")]}
    )
    assert span.annotations == (sdk.ZipkinAnnotation(1, "x"),)
    assert sdk.ZipkinSpan.from_json({"traceId": "a" * 16, "id": "b" * 16, "tags": []}).tags == {}


def test_annotation_to_event() -> None:
    event = sdk.annotation_to_event(sdk.ZipkinAnnotation(5, '{"cache.miss":{"k":"v","n":1}}'))
    assert (event.name, event.time_micros, dict(event.attributes)) == (
        "cache.miss",
        5,
        {"k": "v", "n": 1},
    )
    for value in ("{not json}", '{"a":1}', '{"a":{},"b":{}}', "plain"):
        assert sdk.annotation_to_event(sdk.ZipkinAnnotation(1, value)).name == value


def test_span_conversion_and_summary() -> None:
    spans = sdk.zipkin_spans_from_body(response_body("getTrace.ok"))
    trace = sdk.zipkin_trace_to_trace(spans)
    assert trace is not None
    assert trace.trace_id == TRACE
    root = trace.spans[0]
    assert isinstance(root, sdk.ZipkinTraceSpan)
    assert (root.kind, root.service_name, root.name) == (
        "server",
        "grow-frontend",
        "get /api/orders",
    )
    assert root.events[0].name == "cache.miss"
    assert root.local_endpoint is not None and root.local_endpoint.port == 8080
    summary = sdk.summarize_zipkin_trace(spans)
    assert summary is not None
    assert summary.root_name == "get /api/orders"
    assert summary.span_count == len(spans)
    assert sdk.zipkin_trace_to_trace([]) is None
    assert sdk.summarize_zipkin_trace([]) is None


@pytest.mark.parametrize(
    ("tags", "kind", "expected_kind", "status"),
    [
        ({"error": "boom"}, "CLIENT", "client", sdk.SpanStatus("error", "boom")),
        ({"error": ""}, "PRODUCER", "producer", sdk.SpanStatus("error")),
        ({"otel.status_code": "ERROR"}, "CONSUMER", "consumer", sdk.SpanStatus("error")),
        ({"otel.status_code": "ok"}, None, "internal", sdk.SpanStatus("ok")),
        ({}, "WEIRD", "unspecified", sdk.SpanStatus()),
    ],
)
def test_status_and_kind(
    tags: dict[str, str], kind: str | None, expected_kind: str, status: sdk.SpanStatus
) -> None:
    span = sdk.zipkin_span_to_span(
        sdk.ZipkinSpan(
            "a" * 16,
            "B" * 16,
            parent_id="C" * 16,
            kind=kind,
            tags=tags,
            shared=True,
            debug=True,
        )
    )
    assert span.kind == expected_kind
    assert span.status == status
    assert span.trace_id == "0" * 16 + "a" * 16
    assert (span.span_id, span.parent_span_id) == ("b" * 16, "c" * 16)
    assert span.service_name == ""
    assert (span.start_micros, span.end_micros, span.duration_micros) == (0, 0, 0)
    assert span.shared and span.debug and span.links == ()
