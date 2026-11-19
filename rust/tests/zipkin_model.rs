// Service tests (Zipkin): the v2 span model, ingest normalization and the conversion into the
// normalized trace model.
//! Model: ZipkinSpan builders/serde, normalize_zipkin_span, tag_value, decoding helpers,
//! zipkin_span_to_span, annotation_to_event, summaries.

mod support;

use grow_zipkin as sdk;
use sdk::core::ErrorCode;
use sdk::tracing::{DependencyLink, SpanKind, StatusCode};
use sdk::{
    annotation_to_event, dependencies_from_value, normalize_zipkin_span, strings_from_value,
    summarize_zipkin_trace, tag_value, zipkin_span_to_span, zipkin_spans_from_value,
    zipkin_trace_id, zipkin_trace_to_trace, zipkin_traces_from_value, ZipkinAnnotation,
    ZipkinEndpoint, ZipkinKind, ZipkinSpan,
};
use serde_json::{json, Value};
use support::body;

const TRACE: &str = "5af7183fb1d4cf5fad1c0df61d0a9b8c";

#[test]
fn span_builders_serialize_to_the_v2_wire_model() {
    let span = ZipkinSpan::new(TRACE, "0000000000000002")
        .with_parent_id("0000000000000001")
        .with_kind(ZipkinKind::Client)
        .with_name("get /orders")
        .with_timing(1_795_034_515_005_000, 100_000)
        .with_local_service("grow-frontend")
        .with_remote_endpoint(ZipkinEndpoint {
            service_name: Some("grow-backend".into()),
            port: Some(9000),
            ..Default::default()
        })
        .with_annotation(1_795_034_515_006_000, "cache.miss")
        .with_tag("http.method", "GET")
        .with_tag("retries", 7)
        .with_tag("ratio", 1.5)
        .with_tag("ok", true)
        .with_tag("list", json!(["a", 1]))
        .with_debug(true)
        .with_shared(true);
    let wire = serde_json::to_value(&span).unwrap();
    assert_eq!(
        wire,
        json!({
            "traceId": TRACE, "parentId": "0000000000000001", "id": "0000000000000002",
            "kind": "CLIENT", "name": "get /orders", "timestamp": 1_795_034_515_005_000u64,
            "duration": 100_000,
            "localEndpoint": {"serviceName": "grow-frontend"},
            "remoteEndpoint": {"serviceName": "grow-backend", "port": 9000},
            "annotations": [{"timestamp": 1_795_034_515_006_000u64, "value": "cache.miss"}],
            "tags": {"http.method": "GET", "retries": "7", "ratio": "1.5", "ok": "true",
                     "list": "[\"a\",1]"},
            "debug": true, "shared": true
        })
    );
    // minimal spans omit every empty field
    let min = serde_json::to_value(ZipkinSpan::new(TRACE, "0000000000000001")).unwrap();
    assert_eq!(min, json!({"traceId": TRACE, "id": "0000000000000001"}));
    // the server's responses deserialize, with kind in any case
    let back: ZipkinSpan = serde_json::from_value(json!({
        "traceId": TRACE, "id": "0000000000000001", "kind": "server"
    }))
    .unwrap();
    assert_eq!(back.kind, Some(ZipkinKind::Server));
    assert!(serde_json::from_value::<ZipkinSpan>(json!({
        "traceId": TRACE, "id": "1", "kind": "sideways"
    }))
    .is_err());
    let ep = ZipkinSpan::new(TRACE, "0000000000000001")
        .with_local_endpoint(ZipkinEndpoint::service("x"))
        .with_local_service("y");
    assert_eq!(
        ep.local_endpoint.unwrap().service_name.as_deref(),
        Some("y")
    );
}

#[test]
fn kinds() {
    for (k, s, sk) in [
        (ZipkinKind::Client, "CLIENT", SpanKind::Client),
        (ZipkinKind::Server, "SERVER", SpanKind::Server),
        (ZipkinKind::Producer, "PRODUCER", SpanKind::Producer),
        (ZipkinKind::Consumer, "CONSUMER", SpanKind::Consumer),
    ] {
        assert_eq!(k.as_str(), s);
        assert_eq!(ZipkinKind::parse(&s.to_ascii_lowercase()), Some(k));
        assert_eq!(k.span_kind(), sk);
    }
    assert_eq!(ZipkinKind::parse("internal"), None);
}

#[test]
fn tag_values() {
    assert_eq!(tag_value(&json!("x")), "x");
    assert_eq!(tag_value(&json!(7)), "7");
    assert_eq!(tag_value(&json!(1.5)), "1.5");
    assert_eq!(tag_value(&json!(false)), "false");
    assert_eq!(tag_value(&Value::Null), "null");
    assert_eq!(tag_value(&json!({"a": 1})), "{\"a\":1}");
}

#[test]
fn normalization_lower_cases_and_drops_empty_fields() {
    let input = ZipkinSpan {
        trace_id: TRACE.to_ascii_uppercase(),
        parent_id: Some("".into()),
        id: " 00000000000000AB ".into(),
        name: Some("".into()),
        timestamp: Some(0),
        duration: Some(0),
        local_endpoint: Some(ZipkinEndpoint {
            service_name: Some("".into()),
            ipv4: Some("".into()),
            ipv6: Some("".into()),
            port: Some(0),
        }),
        remote_endpoint: Some(ZipkinEndpoint {
            ipv6: Some("::1".into()),
            ..Default::default()
        }),
        ..Default::default()
    };
    let out = normalize_zipkin_span(&input, "op").unwrap();
    assert_eq!(out.trace_id, TRACE);
    assert_eq!(out.id, "00000000000000ab");
    assert_eq!(out.parent_id, None);
    assert_eq!(out.name, None);
    assert_eq!(out.timestamp, None);
    assert_eq!(out.duration, None);
    assert_eq!(out.local_endpoint, None);
    assert_eq!(out.remote_endpoint.unwrap().ipv6.as_deref(), Some("::1"));
    let ok = normalize_zipkin_span(
        &ZipkinSpan::new("463AC35CAD1C0DF6", "0000000000000001").with_parent_id("00000000000000FF"),
        "op",
    )
    .unwrap();
    assert_eq!(ok.parent_id.as_deref(), Some("00000000000000ff"));
    assert_eq!(ok.trace_id, "463ac35cad1c0df6");

    let bad = [
        ZipkinSpan::new("zz", "0000000000000001"),
        ZipkinSpan::new(TRACE, "1"),
        ZipkinSpan::new(TRACE, "0000000000000000"),
        ZipkinSpan::new(TRACE, "0000000000000001").with_parent_id("xyz"),
        ZipkinSpan::new(TRACE, "0000000000000001").with_annotation(1, ""),
        ZipkinSpan::new(TRACE, "0000000000000001").with_annotation(0, "x"),
    ];
    for b in &bad {
        let err = normalize_zipkin_span(b, "zipkin.report").unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation, "{b:?}");
        assert!(err.message().starts_with("zipkin.report:"));
    }
    assert_eq!(
        zipkin_trace_id(" 463AC35CAD1C0DF6 ", "x").unwrap(),
        "463ac35cad1c0df6"
    );
}

#[test]
fn decoding_is_tolerant() {
    assert!(zipkin_spans_from_value(&json!({"x": 1})).is_empty());
    let spans = zipkin_spans_from_value(&json!([
        {"traceId": TRACE, "id": "0000000000000001"},
        {"traceId": 5},
        "junk"
    ]));
    assert_eq!(spans.len(), 1);
    assert!(zipkin_traces_from_value(&json!("x")).is_empty());
    assert_eq!(
        zipkin_traces_from_value(&json!([[], [{"traceId": TRACE, "id": "0000000000000001"}]]))
            .len(),
        1
    );
    assert_eq!(strings_from_value(&json!(["a", 1, "b"])), ["a", "b"]);
    assert!(strings_from_value(&json!(null)).is_empty());
    let deps = dependencies_from_value(&json!([
        {"parent": "b", "child": "c", "callCount": 2},
        {"parent": "a", "child": "z", "callCount": 1, "errorCount": 1},
        {"parent": "a", "child": "b"},
        {"parent": 1, "child": "x"}
    ]));
    assert_eq!(deps.len(), 3);
    assert_eq!(
        (deps[0].parent.as_str(), deps[0].child.as_str()),
        ("a", "b")
    );
    assert_eq!(deps[0].call_count, 0);
    assert_eq!(deps[1].error_count, 1);
    let generic: DependencyLink = deps[2].clone().into();
    assert_eq!(generic.call_count, 2);
    assert!(dependencies_from_value(&json!({})).is_empty());
}

#[test]
fn annotations_become_events() {
    let plain = annotation_to_event(&ZipkinAnnotation {
        timestamp: 5,
        value: "cache.miss".into(),
    });
    assert_eq!((plain.name.as_str(), plain.time_micros), ("cache.miss", 5));
    assert!(plain.attributes.is_empty());
    let rich = annotation_to_event(&ZipkinAnnotation {
        timestamp: 6,
        value: r#"{"exception":{"exception.message":"boom","n":1}}"#.into(),
    });
    assert_eq!(rich.name, "exception");
    assert_eq!(rich.attributes["exception.message"], json!("boom"));
    assert_eq!(rich.attributes["n"], json!(1));
    for odd in [r#"{"a":1}"#, r#"{"a":{},"b":{}}"#, "{not json}"] {
        let e = annotation_to_event(&ZipkinAnnotation {
            timestamp: 1,
            value: odd.into(),
        });
        assert_eq!(e.name, odd);
    }
}

#[test]
fn spans_convert_to_the_normalized_model() {
    let raw: Vec<ZipkinSpan> = serde_json::from_value(body("getTrace.ok")).unwrap();
    let trace = zipkin_trace_to_trace(&raw).unwrap();
    assert_eq!(trace.trace_id, TRACE);
    assert_eq!(trace.spans.len(), raw.len());
    assert!(trace
        .spans
        .windows(2)
        .all(|w| w[0].start_micros <= w[1].start_micros));
    let summary = summarize_zipkin_trace(&raw).unwrap();
    assert_eq!(summary.root_name.as_deref(), Some("get /api/orders"));
    assert!(zipkin_trace_to_trace(&[]).is_none());
    assert!(summarize_zipkin_trace(&[]).is_none());

    let err = zipkin_span_to_span(
        &ZipkinSpan::new("463AC35CAD1C0DF6", "00000000000000AA")
            .with_parent_id("00000000000000BB")
            .with_tag("error", ""),
    );
    assert_eq!(err.trace_id, "0000000000000000463ac35cad1c0df6");
    assert_eq!(err.span_id, "00000000000000aa");
    assert_eq!(err.parent_span_id.as_deref(), Some("00000000000000bb"));
    assert_eq!(err.status.code, StatusCode::Error);
    assert_eq!(err.status.message, None);
    assert_eq!(err.kind, SpanKind::Internal);
    assert_eq!(err.service_name, "");
    assert_eq!(err.name, "");
    let msg =
        zipkin_span_to_span(&ZipkinSpan::new(TRACE, "0000000000000001").with_tag("error", "boom"));
    assert_eq!(msg.status.message.as_deref(), Some("boom"));
    let otel_err = zipkin_span_to_span(
        &ZipkinSpan::new(TRACE, "0000000000000001").with_tag("otel.status_code", "error"),
    );
    assert_eq!(otel_err.status.code, StatusCode::Error);
    let ok = zipkin_span_to_span(
        &ZipkinSpan::new(TRACE, "0000000000000001")
            .with_tag("otel.status_code", "OK")
            .with_timing(10, 5),
    );
    assert_eq!(ok.status.code, StatusCode::Ok);
    assert_eq!(
        (ok.start_micros, ok.end_micros, ok.duration_micros),
        (10, 15, 5)
    );
    assert!(ok.links.is_empty());
    let unset = zipkin_span_to_span(&ZipkinSpan::new(TRACE, "0000000000000001").with_parent_id(""));
    assert_eq!(unset.status.code, StatusCode::Unset);
    assert_eq!(unset.parent_span_id, None);
    // an id the template cannot normalize is kept lower-cased
    let odd = zipkin_span_to_span(&ZipkinSpan::new("XYZ", "0000000000000001"));
    assert_eq!(odd.trace_id, "xyz");
    assert_eq!(
        zipkin_trace_to_trace(&[ZipkinSpan::new("XYZ", "1")])
            .unwrap()
            .trace_id,
        "xyz"
    );

    // a shared SERVER span (same id as its CLIENT span) does not break the tree
    let shared = vec![
        ZipkinSpan::new(TRACE, "0000000000000002")
            .with_parent_id("0000000000000001")
            .with_kind(ZipkinKind::Client)
            .with_timing(10, 5),
        ZipkinSpan::new(TRACE, "0000000000000002")
            .with_parent_id("0000000000000001")
            .with_kind(ZipkinKind::Server)
            .with_shared(true)
            .with_timing(11, 3),
        ZipkinSpan::new(TRACE, "0000000000000001").with_timing(9, 10),
    ];
    let t = zipkin_trace_to_trace(&shared).unwrap();
    assert_eq!(t.spans.len(), 3);
    assert_eq!(t.tree().len(), 1);
}
