// Service tests (Zipkin): ZipkinSpanExporter, the span mapping and the init_tracing preset.
//! Tracing: OTel span → Zipkin mapping, exporter success/failure/shutdown, environment
//! resolution, propagator order, init_tracing end to end, client-owned handles, OTLP option.

mod support;

use std::collections::HashMap;
use std::io::Read;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_zipkin as sdk;
use opentelemetry::propagation::TextMapPropagator;
use opentelemetry::trace::{
    Event, SpanContext, SpanId, SpanKind, Status, TraceContextExt, TraceFlags, TraceId, TraceState,
    Tracer,
};
use opentelemetry::{Array, Context, InstrumentationScope, KeyValue, Value as OtelValue};
use opentelemetry_sdk::trace::{SpanData, SpanEvents, SpanExporter, SpanLinks};
use opentelemetry_sdk::Resource;
use sdk::core::{EnvReader, ErrorCode};
use sdk::tracing::{Compression, PropagatorKind, Protocol, SamplerKind, TracingConfig};
use sdk::{
    attribute_to_tag, default_propagators, order_propagators, to_zipkin_span,
    tracing_config_from_env, tracing_config_from_env_with, zipkin_exporter_factory, ZipkinConfig,
    ZipkinExporter, ZipkinKind, ZipkinReporter, ZipkinReporterOptions, ZipkinSpanExporter,
    ZipkinSpanExporterOptions, ZipkinTracingOptions, UNKNOWN_SERVICE,
};
use serde_json::Value;
use support::{closed_port, config_builder, text};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const TRACE: &str = "5af7183fb1d4cf5f0a6665df1d0a9b8c";

fn at(micros: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_micros(micros)
}

fn span_data(kind: SpanKind, status: Status, attributes: Vec<KeyValue>) -> SpanData {
    SpanData {
        span_context: SpanContext::new(
            TraceId::from_hex(TRACE).unwrap(),
            SpanId::from_hex("0000000000000002").unwrap(),
            TraceFlags::SAMPLED,
            false,
            TraceState::default(),
        ),
        parent_span_id: SpanId::from_hex("0000000000000001").unwrap(),
        parent_span_is_remote: false,
        span_kind: kind,
        name: "GET /orders".into(),
        start_time: at(1_795_034_515_005_000) + Duration::from_nanos(700),
        end_time: at(1_795_034_515_105_000) + Duration::from_nanos(1_400),
        attributes,
        dropped_attributes_count: 0,
        events: SpanEvents::default(),
        links: SpanLinks::default(),
        status,
        instrumentation_scope: InstrumentationScope::builder("grow-lib")
            .with_version("1.2.3")
            .build(),
    }
}

#[test]
fn span_mapping_matches_the_reference_table() {
    let mut data = span_data(
        SpanKind::Client,
        Status::error("boom"),
        vec![
            KeyValue::new("http.method", "GET"),
            KeyValue::new("retries", 3i64),
            KeyValue::new("ratio", 0.5),
            KeyValue::new("whole", 2.0),
            KeyValue::new("cached", true),
            KeyValue::new("server.address", "db.internal"),
            KeyValue::new("peer.service", "grow-backend"),
            KeyValue::new(
                "list",
                OtelValue::Array(Array::String(vec!["a".into(), "b".into()])),
            ),
        ],
    );
    data.dropped_attributes_count = 2;
    data.events = SpanEvents::default();
    data.events.events = vec![
        Event::new("cache.miss", at(1_795_034_515_006_000), vec![], 0),
        Event::new(
            "exception",
            at(1_795_034_515_007_000),
            vec![
                KeyValue::new("exception.message", "boom"),
                KeyValue::new("n", 1i64),
            ],
            0,
        ),
    ];
    data.events.dropped_count = 1;
    let z = to_zipkin_span(&data, "grow-frontend");
    assert_eq!(z.trace_id, TRACE);
    assert_eq!(z.id, "0000000000000002");
    assert_eq!(z.parent_id.as_deref(), Some("0000000000000001"));
    assert_eq!(z.kind, Some(ZipkinKind::Client));
    assert_eq!(z.name.as_deref(), Some("GET /orders"));
    assert_eq!(z.timestamp, Some(1_795_034_515_005_000), "truncated");
    assert_eq!(z.duration, Some(100_001), "rounded");
    assert_eq!(
        z.local_endpoint.as_ref().unwrap().service_name.as_deref(),
        Some("grow-frontend")
    );
    assert_eq!(
        z.remote_endpoint.as_ref().unwrap().service_name.as_deref(),
        Some("grow-backend"),
        "peer.service wins over server.address"
    );
    let t = &z.tags;
    assert_eq!(t["http.method"], "GET");
    assert_eq!(t["retries"], "3");
    assert_eq!(t["ratio"], "0.5");
    assert_eq!(t["whole"], "2");
    assert_eq!(t["cached"], "true");
    assert_eq!(t["list"], r#"["a","b"]"#);
    assert_eq!(t["otel.scope.name"], "grow-lib");
    assert_eq!(t["otel.scope.version"], "1.2.3");
    assert_eq!(t["otel.dropped_attributes_count"], "2");
    assert_eq!(t["otel.dropped_events_count"], "1");
    assert_eq!(t["otel.status_code"], "ERROR");
    assert_eq!(t["error"], "boom");
    assert_eq!(z.annotations[0].value, "cache.miss");
    assert_eq!(z.annotations[0].timestamp, 1_795_034_515_006_000);
    let rich: Value = serde_json::from_str(&z.annotations[1].value).unwrap();
    assert_eq!(rich["exception"]["exception.message"], "boom");
    assert_eq!(rich["exception"]["n"], 1);
    assert!(!z.debug && !z.shared);

    // INTERNAL → no kind; OK status; no remote endpoint for SERVER; unknown service
    let mut root = span_data(
        SpanKind::Internal,
        Status::Ok,
        vec![KeyValue::new("peer.service", "x")],
    );
    root.parent_span_id = SpanId::INVALID;
    root.end_time = root.start_time;
    root.instrumentation_scope = InstrumentationScope::builder("").build();
    let z = to_zipkin_span(&root, "");
    assert_eq!(z.kind, None);
    assert_eq!(z.parent_id, None);
    assert_eq!(z.duration, Some(1), "at least 1 µs");
    assert_eq!(z.tags["otel.status_code"], "OK");
    assert!(!z.tags.contains_key("error"));
    assert!(!z.tags.contains_key("otel.scope.name"));
    assert!(z.remote_endpoint.is_none());
    assert_eq!(
        z.local_endpoint.unwrap().service_name.as_deref(),
        Some(UNKNOWN_SERVICE)
    );
    for (kind, expected) in [
        (SpanKind::Server, ZipkinKind::Server),
        (SpanKind::Producer, ZipkinKind::Producer),
        (SpanKind::Consumer, ZipkinKind::Consumer),
    ] {
        let z = to_zipkin_span(
            &span_data(
                kind.clone(),
                Status::Unset,
                vec![KeyValue::new("net.peer.name", "broker")],
            ),
            "s",
        );
        assert_eq!(z.kind, Some(expected));
        assert_eq!(z.remote_endpoint.is_some(), kind == SpanKind::Producer);
        assert!(!z.tags.contains_key("otel.status_code"));
    }
    // fallbacks of the remote service name
    let z = to_zipkin_span(
        &span_data(
            SpanKind::Producer,
            Status::Unset,
            vec![
                KeyValue::new("peer.service", ""),
                KeyValue::new("network.peer.address", "10.0.0.9"),
            ],
        ),
        "s",
    );
    assert_eq!(
        z.remote_endpoint.unwrap().service_name.as_deref(),
        Some("10.0.0.9")
    );
}

#[test]
fn attribute_tags() {
    assert_eq!(attribute_to_tag(&OtelValue::from("x")), "x");
    assert_eq!(attribute_to_tag(&OtelValue::from(7i64)), "7");
    assert_eq!(attribute_to_tag(&OtelValue::from(1.25)), "1.25");
    assert_eq!(attribute_to_tag(&OtelValue::from(f64::NAN)), "null");
    assert_eq!(attribute_to_tag(&OtelValue::from(false)), "false");
    assert_eq!(
        attribute_to_tag(&OtelValue::Array(Array::Bool(vec![true, false]))),
        "[true,false]"
    );
    assert_eq!(
        attribute_to_tag(&OtelValue::Array(Array::I64(vec![1, 2]))),
        "[1,2]"
    );
    assert_eq!(
        attribute_to_tag(&OtelValue::Array(Array::F64(vec![1.0, 1.5]))),
        "[1,1.5]"
    );
}

#[tokio::test]
async fn exporter_sends_reports_failures_and_shuts_down() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/v2/spans"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&server)
        .await;
    Mock::given(path("/bad/api/v2/spans"))
        .respond_with(text(400, "Expected a JSON_V2 encoded list"))
        .mount(&server)
        .await;
    let mut exporter = ZipkinSpanExporter::new(
        config_builder(&server).build().unwrap(),
        ZipkinSpanExporterOptions::default(),
    )
    .unwrap();
    assert_eq!(exporter.service_name(), UNKNOWN_SERVICE);
    exporter.set_resource(&Resource::builder_empty().build());
    assert_eq!(exporter.service_name(), UNKNOWN_SERVICE);
    exporter.set_resource(
        &Resource::builder_empty()
            .with_service_name("grow-checkout")
            .build(),
    );
    assert_eq!(exporter.service_name(), "grow-checkout");
    assert!(format!("{exporter:?}").contains("grow-checkout"));
    exporter
        .export(vec![span_data(SpanKind::Server, Status::Unset, vec![])])
        .await
        .unwrap();
    let reqs = server.received_requests().await.unwrap();
    assert_eq!(reqs[0].headers.get("content-encoding").unwrap(), "gzip");
    let mut json = String::new();
    flate2::read::GzDecoder::new(&reqs[0].body[..])
        .read_to_string(&mut json)
        .unwrap();
    let sent: Value = serde_json::from_str(&json).unwrap();
    assert_eq!(sent[0]["localEndpoint"]["serviceName"], "grow-checkout");
    assert_eq!(exporter.reporter().compression(), Compression::Gzip);

    // a failed export calls on_error (a panicking handler is contained) and reports failure
    let calls = Arc::new(AtomicU32::new(0));
    let seen = calls.clone();
    let bad = ZipkinReporter::new(
        config_builder(&server)
            .url(format!("{}/bad", server.uri()))
            .build()
            .unwrap(),
        ZipkinReporterOptions::default(),
    )
    .unwrap();
    let failing = ZipkinSpanExporter::with_reporter(bad).on_error(Arc::new(move |e| {
        assert_eq!(e.code(), ErrorCode::Validation);
        seen.fetch_add(1, Ordering::SeqCst);
        panic!("handler failure (expected in tests)");
    }));
    let err = failing
        .export(vec![span_data(SpanKind::Server, Status::Unset, vec![])])
        .await
        .unwrap_err();
    assert!(err.to_string().contains("zipkin export failed"));
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let mut silent = ZipkinSpanExporter::with_reporter(failing.reporter().clone());
    assert!(
        silent.export(vec![]).await.is_ok(),
        "empty batch sends nothing"
    );
    assert!(silent
        .export(vec![span_data(SpanKind::Server, Status::Unset, vec![])])
        .await
        .is_err());
    silent.shutdown().unwrap();

    // shutdown closes the owned reporter; later exports fail; shutdown is idempotent
    exporter.force_flush().unwrap();
    exporter.shutdown().unwrap();
    exporter.shutdown().unwrap();
    assert!(exporter.is_shutdown());
    assert!(exporter.export(vec![]).await.is_err());
    let _ = ZipkinSpanExporter::from_env();
    assert!(format!("{:?}", ZipkinSpanExporterOptions::default()).contains("Gzip"));
}

#[test]
fn propagator_order_and_defaults() {
    assert_eq!(
        default_propagators(),
        [
            PropagatorKind::TraceContext,
            PropagatorKind::Baggage,
            PropagatorKind::B3
        ]
    );
    assert_eq!(
        order_propagators(&[
            PropagatorKind::TraceContext,
            PropagatorKind::B3Multi,
            PropagatorKind::Baggage,
            PropagatorKind::B3,
            PropagatorKind::TraceContext,
        ]),
        [
            PropagatorKind::B3Multi,
            PropagatorKind::B3,
            PropagatorKind::TraceContext,
            PropagatorKind::Baggage
        ]
    );
}

fn env(vars: &[(&str, &str)]) -> EnvReader {
    EnvReader::from_map(vars.iter().copied())
}

#[test]
fn environment_resolution() {
    // nothing set: native, Zipkin propagators (B3 first)
    let c = tracing_config_from_env_with(TracingConfig::new("svc"), &env(&[])).unwrap();
    assert_eq!(c.protocol, Protocol::Native);
    assert_eq!(c.endpoint, None);
    assert_eq!(
        c.propagators,
        [
            PropagatorKind::B3,
            PropagatorKind::TraceContext,
            PropagatorKind::Baggage
        ]
    );

    let all = env(&[
        ("GROW_ZIPKIN_URL", "http://zipkin:9411"),
        ("OTEL_EXPORTER_ZIPKIN_TIMEOUT", "10s"),
        ("GROW_ZIPKIN_COMPRESSION", "none"),
        (
            "OTEL_RESOURCE_ATTRIBUTES",
            "service.name=from-res,team=core,k%20x=v%2Cw",
        ),
        ("OTEL_TRACES_SAMPLER", "parentbased_traceidratio"),
        ("OTEL_TRACES_SAMPLER_ARG", "0.25"),
        ("OTEL_PROPAGATORS", "tracecontext, b3multi"),
    ]);
    let c = tracing_config_from_env_with(TracingConfig::new(""), &all).unwrap();
    assert_eq!(
        c.endpoint.as_deref(),
        Some("http://zipkin:9411/api/v2/spans")
    );
    assert_eq!(c.batch.export_timeout, Duration::from_secs(10));
    assert_eq!(c.compression, Compression::None);
    assert_eq!(c.service_name, "from-res");
    assert_eq!(c.resource_attributes["team"], "core");
    assert_eq!(c.resource_attributes["k x"], "v,w");
    assert!(!c.resource_attributes.contains_key("service.name"));
    assert_eq!(c.sampler, SamplerKind::ParentBasedTraceIdRatio);
    assert_eq!(c.sampler_ratio, 0.25);
    assert_eq!(
        c.propagators,
        [PropagatorKind::B3Multi, PropagatorKind::TraceContext]
    );

    // explicit values win; OTEL_SERVICE_NAME beats the resource attribute
    let explicit = TracingConfig::new("")
        .with_endpoint("http://explicit:9411/api/v2/spans")
        .with_resource_attribute("team", "explicit")
        .with_sampler(SamplerKind::AlwaysOn, 1.0)
        .with_propagators(vec![PropagatorKind::Baggage]);
    let c = tracing_config_from_env_with(
        explicit,
        &env(&[
            ("OTEL_SERVICE_NAME", "from-name"),
            ("OTEL_RESOURCE_ATTRIBUTES", "service.name=from-res,team=env"),
            ("OTEL_TRACES_SAMPLER", "always_off"),
            ("GROW_ZIPKIN_ENDPOINT", "http://env:9411/x"),
        ]),
    )
    .unwrap();
    assert_eq!(c.service_name, "from-name");
    assert_eq!(c.resource_attributes["team"], "explicit");
    assert_eq!(c.sampler, SamplerKind::AlwaysOn);
    assert_eq!(
        c.endpoint.as_deref(),
        Some("http://explicit:9411/api/v2/spans")
    );
    assert_eq!(c.propagators, [PropagatorKind::Baggage]);

    // endpoint precedence for native
    let c = tracing_config_from_env_with(
        TracingConfig::new("s"),
        &env(&[
            ("GROW_ZIPKIN_ENDPOINT", "http://grow:9411/in"),
            (
                "OTEL_EXPORTER_ZIPKIN_ENDPOINT",
                "http://otel:9411/api/v2/spans",
            ),
        ]),
    )
    .unwrap();
    assert_eq!(c.endpoint.as_deref(), Some("http://grow:9411/in"));
    let c = tracing_config_from_env_with(
        TracingConfig::new("s"),
        &env(&[(
            "OTEL_EXPORTER_ZIPKIN_ENDPOINT",
            "http://otel:9411/api/v2/spans",
        )]),
    )
    .unwrap();
    assert_eq!(c.endpoint.as_deref(), Some("http://otel:9411/api/v2/spans"));

    // OTLP endpoints (a Collector)
    let c = tracing_config_from_env_with(
        TracingConfig::new("s"),
        &env(&[
            ("GROW_ZIPKIN_PROTOCOL", "http/protobuf"),
            ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318"),
        ]),
    )
    .unwrap();
    assert_eq!(c.protocol, Protocol::HttpProtobuf);
    assert_eq!(
        c.endpoint.as_deref(),
        Some("http://collector:4318/v1/traces")
    );
    let c = tracing_config_from_env_with(
        TracingConfig::new("s"),
        &env(&[
            ("GROW_ZIPKIN_PROTOCOL", "GRPC"),
            ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317"),
        ]),
    )
    .unwrap();
    assert_eq!(c.protocol, Protocol::Grpc);
    assert_eq!(c.endpoint.as_deref(), Some("http://collector:4317"));
    let c = tracing_config_from_env_with(
        TracingConfig::new("s").with_protocol(Protocol::Grpc),
        &env(&[
            ("GROW_ZIPKIN_PROTOCOL", "native"),
            (
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                "http://collector:4317/x",
            ),
        ]),
    )
    .unwrap();
    assert_eq!(c.protocol, Protocol::Grpc, "explicit grpc wins");
    assert_eq!(c.endpoint.as_deref(), Some("http://collector:4317/x"));
    let c = tracing_config_from_env_with(
        TracingConfig::new("s"),
        &env(&[("GROW_ZIPKIN_PROTOCOL", "native")]),
    )
    .unwrap();
    assert_eq!(c.protocol, Protocol::Native);
    let c = tracing_config_from_env_with(
        TracingConfig::new("s"),
        &env(&[
            ("GROW_ZIPKIN_COMPRESSION", "gzip"),
            ("OTEL_TRACES_SAMPLER", "traceidratio"),
        ]),
    )
    .unwrap();
    assert_eq!(c.compression, Compression::Gzip);
    assert_eq!(c.sampler_ratio, 1.0);

    // invalid values name the variable, never the value
    for (var, value) in [
        ("GROW_ZIPKIN_PROTOCOL", "carrier-pigeon"),
        ("OTEL_EXPORTER_ZIPKIN_TIMEOUT", "soon"),
        ("OTEL_EXPORTER_ZIPKIN_TIMEOUT", "0"),
        ("GROW_ZIPKIN_COMPRESSION", "zstd"),
        ("OTEL_RESOURCE_ATTRIBUTES", "novalue"),
        ("OTEL_RESOURCE_ATTRIBUTES", "=x"),
        ("OTEL_TRACES_SAMPLER", "sometimes"),
    ] {
        let err = tracing_config_from_env_with(TracingConfig::new("s"), &env(&[(var, value)]))
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{var}");
        assert!(err.message().contains(var), "{err}");
        assert!(!err.message().contains(value), "{err}");
    }
    let err = tracing_config_from_env_with(
        TracingConfig::new("s"),
        &env(&[
            ("OTEL_TRACES_SAMPLER", "traceidratio"),
            ("OTEL_TRACES_SAMPLER_ARG", "2"),
        ]),
    )
    .unwrap_err();
    assert!(err.message().contains("OTEL_TRACES_SAMPLER_ARG"));
    let _ = tracing_config_from_env(TracingConfig::new("s"));
}

fn decode(body: &[u8]) -> Value {
    let mut json = String::new();
    flate2::read::GzDecoder::new(body)
        .read_to_string(&mut json)
        .unwrap();
    serde_json::from_str(&json).unwrap()
}

async fn spans_server() -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/v2/spans"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&server)
        .await;
    server
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn init_tracing_end_to_end() {
    let server = spans_server().await;
    let errors = Arc::new(AtomicU32::new(0));
    let seen = errors.clone();
    let handle = sdk::init_tracing_with_options(
        TracingConfig::new("grow-e2e")
            .with_set_global(false)
            .with_header("x-gateway", "k"),
        ZipkinTracingOptions::new()
            .without_env()
            .client_config(config_builder(&server).build().unwrap())
            .on_export_error(Arc::new(move |_| {
                seen.fetch_add(1, Ordering::SeqCst);
            })),
    )
    .unwrap();
    let tracer = handle.tracer("grow-e2e-lib", Some("0.1.0"));
    let trace_id = tracer.in_span("parent", |cx| {
        tracer.in_span("child", |_| {});
        cx.span().span_context().trace_id().to_string()
    });
    handle
        .force_flush(Some(Duration::from_secs(10)))
        .await
        .unwrap();
    let reqs = server.received_requests().await.unwrap();
    assert_eq!(reqs.len(), 1);
    assert_eq!(reqs[0].headers.get("x-gateway").unwrap(), "k");
    let sent = decode(&reqs[0].body);
    let spans = sent.as_array().unwrap();
    assert_eq!(spans.len(), 2);
    for s in spans {
        assert_eq!(s["traceId"], trace_id.as_str());
        assert_eq!(s["localEndpoint"]["serviceName"], "grow-e2e");
        assert_eq!(s["tags"]["otel.scope.name"], "grow-e2e-lib");
    }

    // W3C wins over B3 when both are present; both are injected
    let prop = handle.propagator();
    let mut carrier = HashMap::new();
    let cx = Context::new().with_remote_span_context(SpanContext::new(
        TraceId::from_hex(TRACE).unwrap(),
        SpanId::from_hex("0000000000000003").unwrap(),
        TraceFlags::SAMPLED,
        true,
        TraceState::default(),
    ));
    prop.inject_context(&cx, &mut carrier);
    assert!(carrier.contains_key("traceparent"));
    assert_eq!(carrier["b3"], format!("{TRACE}-0000000000000003-1"));
    carrier.insert(
        "b3".into(),
        "463ac35cad1c0df6463ac35cad1c0df6-0000000000000009-1".into(),
    );
    let extracted = prop.extract(&carrier);
    assert_eq!(
        extracted.span().span_context().trace_id().to_string(),
        TRACE
    );
    handle.shutdown().await.unwrap();
    assert_eq!(errors.load(Ordering::SeqCst), 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn client_init_tracing_uses_the_client_and_close_shuts_down() {
    let server = spans_server().await;
    let client = sdk::ZipkinClient::new(config_builder(&server).build().unwrap()).unwrap();
    let handle = client
        .init_tracing_with_options(
            TracingConfig::new("grow-client").with_set_global(false),
            ZipkinTracingOptions::new().without_env(),
        )
        .unwrap();
    handle.tracer("t", None).in_span("op", |_| {});
    client.close().await;
    assert!(
        handle.is_shutdown(),
        "close shuts the handle down (and flushes)"
    );
    let reqs = server.received_requests().await.unwrap();
    assert_eq!(reqs.len(), 1);
    assert_eq!(decode(&reqs[0].body)[0]["name"], "op");
    assert_eq!(
        client
            .init_tracing(TracingConfig::new("late"))
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    // the process-environment variant with explicit settings
    let other = sdk::ZipkinClient::new(config_builder(&server).build().unwrap()).unwrap();
    let h = other
        .init_tracing(
            TracingConfig::new("grow-client-2")
                .with_set_global(false)
                .with_protocol(Protocol::Native),
        )
        .unwrap();
    other.close().await;
    assert!(h.is_shutdown());
    let h = sdk::init_tracing(
        TracingConfig::new("grow-direct")
            .with_set_global(false)
            .with_protocol(Protocol::Native)
            .with_endpoint(format!("{}/api/v2/spans", server.uri())),
    )
    .unwrap();
    h.shutdown().await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn exporter_factory_native_and_otlp() {
    let server = spans_server().await;
    let base = ZipkinConfig::builder()
        .url(server.uri())
        .header("authorization", "Bearer gw")
        .proxy("http://127.0.0.1:1")
        .user_agent_suffix("app/1")
        .hooks(Arc::new(NoHooks))
        .build()
        .unwrap();
    let mut cfg = TracingConfig::new("s").with_protocol(Protocol::Native);
    cfg.tls = Some(sdk::core::TlsConfig {
        insecure_skip_verify: true,
        ..Default::default()
    });
    let mut native = zipkin_exporter_factory(base.clone(), None)(&cfg).unwrap();
    assert!(matches!(native, ZipkinExporter::Native(_)));
    native.set_resource(&Resource::builder_empty().with_service_name("n").build());
    native.force_flush().unwrap();
    native.shutdown().unwrap();
    assert!(format!("{native:?}").contains("Native"));
    assert!(native.export(vec![]).await.is_err(), "shut down");

    // OTLP needs a Collector endpoint, never Zipkin's /api/v2/spans
    for endpoint in [None, Some(format!("{}/api/v2/spans/", server.uri()))] {
        let mut c = TracingConfig::new("s").with_protocol(Protocol::Grpc);
        c.endpoint = endpoint;
        let err = zipkin_exporter_factory(base.clone(), None)(&c).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config);
    }
    let otlp_cfg = TracingConfig::new("s")
        .with_protocol(Protocol::HttpProtobuf)
        .with_endpoint(format!("http://127.0.0.1:{}", closed_port()));
    let mut otlp = zipkin_exporter_factory(base.clone(), None)(&otlp_cfg).unwrap();
    assert!(matches!(otlp, ZipkinExporter::Otlp(_)));
    otlp.set_resource(&Resource::builder_empty().build());
    assert!(otlp
        .export(vec![span_data(SpanKind::Server, Status::Unset, vec![])])
        .await
        .is_err());
    let _ = otlp.force_flush();
    let _ = otlp.shutdown();

    // the explicit protocol override selects OTLP even at the template default
    let h = sdk::init_tracing_with_options(
        TracingConfig::new("s")
            .with_set_global(false)
            .with_endpoint(format!("http://127.0.0.1:{}", closed_port())),
        ZipkinTracingOptions::new()
            .without_env()
            .protocol(Protocol::HttpProtobuf),
    )
    .unwrap();
    h.shutdown().await.unwrap();
    // grpc to a Collector from the environment
    let h = sdk::init_tracing_with_options(
        TracingConfig::new("s").with_set_global(false),
        ZipkinTracingOptions::new().env(env(&[
            ("GROW_ZIPKIN_PROTOCOL", "grpc"),
            ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4317"),
        ])),
    )
    .unwrap();
    h.shutdown().await.unwrap();
    assert!(format!("{:?}", ZipkinTracingOptions::new()).contains("ZipkinTracingOptions"));
}

struct NoHooks;
impl sdk::core::Hooks for NoHooks {}
