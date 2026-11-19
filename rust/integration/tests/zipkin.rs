// Integration suite (Zipkin): every public function against the real Zipkin 3.6.1 with a
// round-trip assertion (report or export → query back).

use std::collections::HashMap;
use std::time::{Duration, SystemTime};

use grow_zipkin as sdk;
use grow_zipkin_integration::{load_bootstrap_env, random_hex, run_tag};
use opentelemetry::propagation::TextMapPropagator;
use opentelemetry::trace::{Span as _, SpanKind, Status, TraceContextExt, Tracer as _};
use opentelemetry::KeyValue;
use opentelemetry_sdk::trace::SpanExporter as _;
use sdk::tracing::{
    DependencyOptions, SpanKind as NormKind, StatusCode, TraceQueryClient, TraceSearchQuery,
    TracingConfig, WaitForTraceOptions,
};
use sdk::{
    ErrorCode, ZipkinClient, ZipkinConfig, ZipkinDependencyOptions, ZipkinEndpoint, ZipkinKind,
    ZipkinQueryClient, ZipkinReporter, ZipkinReporterOptions, ZipkinSpan, ZipkinSpanExporter,
    ZipkinTraceQuery, ZipkinTracingOptions,
};

fn client() -> ZipkinClient {
    load_bootstrap_env();
    ZipkinClient::from_env().expect("client from env")
}

fn now_micros() -> u64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_micros() as u64
}

fn wait() -> WaitForTraceOptions {
    WaitForTraceOptions::default()
        .timeout(Duration::from_secs(30))
        .interval(Duration::from_millis(200))
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn zipkin_api_round_trip() {
    let c = client();
    let q = c.query();
    let run = run_tag();
    let (front, back, lonely) = (
        format!("grow-rust-{run}-front"),
        format!("grow-rust-{run}-back"),
        format!("grow-rust-{run}-lonely"),
    );
    let trace_a = random_hex(32);
    let trace_b = random_hex(16);
    let ts = now_micros() - 1_000_000;

    // health, ping, version
    let h = c.health().await.unwrap();
    assert!(h.healthy, "{h:?}");
    assert_eq!(h.status, "UP");
    assert!(c.ping().await.unwrap() > Duration::ZERO);
    let want = std::env::var("ZIPKIN_VERSION").unwrap_or_else(|_| "3.6.1".into());
    assert_eq!(c.server_version().await.unwrap(), want);
    assert!(c.server_info().await.unwrap().commit.is_some());

    // report (shared reporter, upper-case ids and mixed-case names)
    let spans_a = vec![
        ZipkinSpan::new(trace_a.to_ascii_uppercase(), "0000000000000001")
            .with_kind(ZipkinKind::Server)
            .with_name("Get /API/Orders")
            .with_timing(ts, 150_000)
            .with_local_endpoint(ZipkinEndpoint {
                service_name: Some(front.clone()),
                ipv4: Some("10.0.0.1".into()),
                port: Some(8080),
                ..Default::default()
            })
            .with_annotation(ts + 1000, "cache.miss")
            .with_tag("grow.test", run.as_str())
            .with_tag("http.method", "GET")
            .with_tag("note", "cats and dogs")
            .with_tag("retries", 2),
        ZipkinSpan::new(trace_a.clone(), "0000000000000002")
            .with_parent_id("0000000000000001")
            .with_kind(ZipkinKind::Client)
            .with_name("get /orders")
            .with_timing(ts + 5000, 100_000)
            .with_local_service(front.clone())
            .with_remote_endpoint(ZipkinEndpoint {
                service_name: Some(back.clone()),
                port: Some(9000),
                ..Default::default()
            })
            .with_tag("grow.test", run.as_str()),
        ZipkinSpan::new(trace_a.clone(), "0000000000000003")
            .with_parent_id("0000000000000002")
            .with_kind(ZipkinKind::Server)
            .with_name("get /orders")
            .with_timing(ts + 10_000, 80_000)
            .with_local_service(back.clone())
            .with_tag("grow.test", run.as_str())
            .with_tag("error", "boom"),
    ];
    c.reporter().report(&spans_a).await.unwrap();
    // standalone reporter with gzip and batches of 1
    load_bootstrap_env();
    let r = ZipkinReporter::from_env(
        ZipkinReporterOptions::default()
            .compression(sdk::tracing::Compression::Gzip)
            .max_batch_size(1),
    )
    .unwrap();
    r.report(&[
        ZipkinSpan::new(trace_b.to_ascii_uppercase(), "00000000000000B1")
            .with_name("lonely-op")
            .with_timing(ts, 5000)
            .with_local_service(lonely.clone())
            .with_tag("grow.test", run.as_str()),
    ])
    .await
    .unwrap();
    assert_eq!(
        r.report(&[ZipkinSpan::new("zz", "1")])
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    r.close().await;

    // getTraceSpans / getTrace / waitForTrace
    let spans = q.get_trace_spans(&trace_a).await.unwrap().unwrap();
    let mut names: Vec<_> = spans.iter().map(|s| s.name.clone().unwrap()).collect();
    names.sort();
    assert_eq!(
        names,
        ["get /api/orders", "get /orders", "get /orders"],
        "lower-cased"
    );
    let root = spans.iter().find(|s| s.id == "0000000000000001").unwrap();
    assert_eq!(root.tags["retries"], "2");
    assert!(q.get_trace_spans(&random_hex(32)).await.unwrap().is_none());
    let trace = q.get_trace(&trace_a).await.unwrap().unwrap();
    assert_eq!(trace.spans.len(), 3);
    let tree = trace.tree();
    assert_eq!(tree.len(), 1);
    assert_eq!(tree[0].span.name, "get /api/orders");
    assert_eq!(tree[0].span.kind, NormKind::Server);
    assert_eq!(tree[0].span.events[0].name, "cache.miss");
    assert_eq!(tree[0].children.len(), 1);
    let err_span = trace
        .spans
        .iter()
        .find(|s| s.span_id == "0000000000000003")
        .unwrap();
    assert_eq!(err_span.status.code, StatusCode::Error);
    assert_eq!(err_span.status.message.as_deref(), Some("boom"));
    let tb = q.get_trace(&trace_b).await.unwrap().unwrap();
    assert_eq!(tb.trace_id, format!("0000000000000000{trace_b}"));
    assert_eq!(tb.spans[0].service_name, lonely);
    let waited = q
        .wait_for_trace(&trace_a, wait().min_spans(3))
        .await
        .unwrap();
    assert_eq!(waited.spans.len(), 3);

    // getTraces
    let end = SystemTime::now();
    let traces = q
        .get_traces(
            &ZipkinTraceQuery::service(front.clone())
                .span_name("get /api/orders")
                .tag("grow.test", run.as_str())
                .annotation("cache.miss")
                .duration(
                    Some(Duration::from_millis(100)),
                    Some(Duration::from_millis(200)),
                )
                .window(end, Duration::from_secs(3600))
                .limit(5),
        )
        .await
        .unwrap();
    assert_eq!(traces.len(), 1);
    assert_eq!(traces[0][0].trace_id, trace_a);
    let remote = q
        .get_traces(
            &ZipkinTraceQuery::default()
                .remote_service_name(back.clone())
                .limit(1),
        )
        .await
        .unwrap();
    assert_eq!(remote[0][0].trace_id, trace_a);
    let raw = q
        .get_traces(
            &ZipkinTraceQuery::service(front.clone())
                .annotation_query(format!("grow.test={run} and http.method=GET")),
        )
        .await
        .unwrap();
    assert_eq!(raw.len(), 1);
    let none = q
        .get_traces(
            &ZipkinTraceQuery::service(front.clone()).duration(Some(Duration::from_secs(10)), None),
        )
        .await
        .unwrap();
    assert!(none.is_empty());
    // a value with " and " is matched client-side
    let local = q
        .get_traces(&ZipkinTraceQuery::service(front.clone()).tag("note", "cats and dogs"))
        .await
        .unwrap();
    assert_eq!(local.len(), 1);

    // getTraceMany: request order, duplicates and missing ids
    let many = q
        .get_trace_many(&[
            trace_b.clone(),
            trace_a.clone(),
            random_hex(32),
            trace_a.to_ascii_uppercase(),
        ])
        .await
        .unwrap();
    assert_eq!(many.len(), 2);
    assert_eq!(many[0][0].trace_id, trace_b);
    assert_eq!(many[1][0].trace_id, trace_a);
    let empty: [&str; 0] = [];
    assert!(q.get_trace_many(&empty).await.unwrap().is_empty());

    // name lists and autocomplete
    let services = q.list_services().await.unwrap();
    for s in [&front, &back, &lonely] {
        assert!(services.contains(s), "{s} in {services:?}");
    }
    assert_eq!(
        q.list_spans(&front.to_ascii_uppercase()).await.unwrap(),
        ["get /api/orders", "get /orders"]
    );
    assert_eq!(q.list_operations(&back).await.unwrap(), ["get /orders"]);
    assert_eq!(
        q.list_remote_services(&front).await.unwrap(),
        std::slice::from_ref(&back)
    );
    let keys = q.autocomplete_keys().await.unwrap();
    assert!(keys.contains(&"grow.test".to_owned()), "{keys:?}");
    let values = q.autocomplete_values("grow.test").await.unwrap();
    assert!(values.contains(&run), "{values:?}");

    // dependencies (in-memory storage computes them on read)
    let links = q
        .get_dependencies(ZipkinDependencyOptions::window(
            SystemTime::now(),
            Duration::from_secs(3600),
        ))
        .await
        .unwrap();
    let link = links
        .iter()
        .find(|l| l.parent == front && l.child == back)
        .unwrap_or_else(|| panic!("edge {front} → {back} in {links:?}"));
    assert!(link.call_count >= 1);
    assert!(link.error_count >= 1);
    let generic = TraceQueryClient::get_dependencies(
        &q,
        DependencyOptions {
            start: Some(SystemTime::now() - Duration::from_secs(3600)),
            end: None,
        },
    )
    .await
    .unwrap();
    assert!(generic.iter().any(|l| l.parent == front && l.child == back));

    // searchTraces (SPEC §5)
    let mut search = TraceSearchQuery::service(front.clone()).tag("grow.test", run.as_str());
    search.start = Some(SystemTime::now() - Duration::from_secs(3600));
    let sums = q.search_traces(&search).await.unwrap();
    assert_eq!(sums.len(), 1, "{sums:?}");
    assert_eq!(sums[0].trace_id, trace_a);
    assert_eq!(sums[0].root_name.as_deref(), Some("get /api/orders"));
    assert_eq!(sums[0].span_count, 3);
    let default_window = TraceSearchQuery::service(front.clone()).tag("note", "cats and dogs");
    assert_eq!(q.search_traces(&default_window).await.unwrap().len(), 1);
    let mismatch = TraceSearchQuery::service(front.clone()).tag("note", "cats and mice");
    assert!(q.search_traces(&mismatch).await.unwrap().is_empty());

    // a server 400 maps to VALIDATION (the SDK validates locally, so call the API raw)
    let err = c.transport().get("/api/v2/spans").send().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(err.message().contains("serviceName"), "{err}");

    // standalone query client
    let own = ZipkinQueryClient::from_env().unwrap();
    assert!(own.list_services().await.unwrap().contains(&front));
    own.close().await;

    // close is idempotent
    let other = ZipkinClient::new(ZipkinConfig::from_env().unwrap()).unwrap();
    other.close().await;
    other.close().await;
    assert_eq!(other.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    c.close().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn tracing_round_trip() {
    let c = client();
    let q = c.query();
    let run = run_tag();
    let service = format!("grow-rust-{run}-otel");

    // init_tracing reads GROW_ZIPKIN_URL from the environment (native exporter)
    let mut config = TracingConfig::new(service.clone()).with_set_global(false);
    config.batch.scheduled_delay = Duration::from_millis(100);
    let handle = sdk::init_tracing(config).unwrap();
    let tracer = handle.tracer("grow-rust-integration", Some("0.1.0"));
    let trace_id = tracer.in_span("Checkout", |cx| {
        let span = cx.span();
        span.set_attribute(KeyValue::new("grow.test", run.clone()));
        span.set_attribute(KeyValue::new("cart.items", 3i64));
        span.set_attribute(KeyValue::new("vip", true));
        span.add_event(
            "validated",
            vec![KeyValue::new("rule", "stock"), KeyValue::new("n", 1i64)],
        );
        let mut child = tracer
            .span_builder("charge")
            .with_kind(SpanKind::Client)
            .with_attributes(vec![KeyValue::new("peer.service", "payments")])
            .start_with_context(&tracer, &cx);
        child.set_status(Status::error("card declined"));
        child.end();
        span.span_context().trace_id().to_string()
    });
    handle
        .force_flush(Some(Duration::from_secs(10)))
        .await
        .unwrap();
    let spans = loop {
        if let Some(s) = q.get_trace_spans(&trace_id).await.unwrap() {
            if s.len() == 2 {
                break s;
            }
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    };
    let root = spans
        .iter()
        .find(|s| s.name.as_deref() == Some("checkout"))
        .unwrap();
    assert_eq!(root.kind, None, "INTERNAL has no kind");
    assert_eq!(
        root.local_endpoint
            .as_ref()
            .unwrap()
            .service_name
            .as_deref(),
        Some(service.as_str())
    );
    assert_eq!(root.tags["grow.test"], run);
    assert_eq!(root.tags["cart.items"], "3");
    assert_eq!(root.tags["vip"], "true");
    assert_eq!(root.tags["otel.scope.name"], "grow-rust-integration");
    assert_eq!(root.tags["otel.scope.version"], "0.1.0");
    assert!(root.annotations[0].value.starts_with("{\"validated\":"));
    let child = spans
        .iter()
        .find(|s| s.name.as_deref() == Some("charge"))
        .unwrap();
    assert_eq!(child.kind, Some(ZipkinKind::Client));
    assert_eq!(child.parent_id.as_deref(), Some(root.id.as_str()));
    assert_eq!(
        child
            .remote_endpoint
            .as_ref()
            .unwrap()
            .service_name
            .as_deref(),
        Some("payments")
    );
    assert_eq!(child.tags["otel.status_code"], "ERROR");
    assert_eq!(child.tags["error"], "card declined");
    // the normalized model decodes the event attributes back
    let trace = q
        .wait_for_trace(&trace_id, wait().min_spans(2))
        .await
        .unwrap();
    let r = trace.spans.iter().find(|s| s.name == "checkout").unwrap();
    assert_eq!(r.events[0].name, "validated");
    assert_eq!(r.events[0].attributes["rule"], "stock");
    assert!(q.list_services().await.unwrap().contains(&service));
    assert!(q
        .list_remote_services(&service)
        .await
        .unwrap()
        .contains(&"payments".to_owned()));

    // propagation: W3C + B3 injected; W3C wins when both disagree
    let prop = handle.propagator();
    let mut carrier = HashMap::new();
    let parent = tracer.start("outgoing");
    let cx = opentelemetry::Context::new().with_span(parent);
    prop.inject_context(&cx, &mut carrier);
    assert!(carrier.contains_key("traceparent"));
    assert!(carrier.contains_key("b3"));
    let w3c_trace = cx.span().span_context().trace_id().to_string();
    carrier.insert(
        "b3".into(),
        format!("{}-{}-1", random_hex(32), random_hex(16)),
    );
    let extracted = prop.extract(&carrier);
    assert_eq!(
        extracted.span().span_context().trace_id().to_string(),
        w3c_trace
    );
    cx.span().end();
    handle.shutdown().await.unwrap();

    // client.init_tracing: the client's endpoint; close() shuts the handle down
    let tc = client();
    let h2 = tc
        .init_tracing_with_options(
            TracingConfig::new(format!("{service}-client")).with_set_global(false),
            ZipkinTracingOptions::new(),
        )
        .unwrap();
    let t2 = h2
        .tracer("grow-rust-integration", None)
        .in_span("client-op", |cx| {
            cx.span().span_context().trace_id().to_string()
        });
    tc.close().await;
    assert!(h2.is_shutdown());
    let t = q.wait_for_trace(&t2, wait()).await.unwrap();
    assert_eq!(t.spans[0].service_name, format!("{service}-client"));

    // the exporter on its own (SpanExporter::export)
    let exporter = ZipkinSpanExporter::from_env().unwrap();
    let data = {
        use opentelemetry::trace::{SpanContext, SpanId, TraceFlags, TraceId, TraceState};
        let id = random_hex(32);
        let sc = SpanContext::new(
            TraceId::from_hex(&id).unwrap(),
            SpanId::from_hex(&random_hex(16)).unwrap(),
            TraceFlags::SAMPLED,
            false,
            TraceState::default(),
        );
        let now = SystemTime::now();
        let d = opentelemetry_sdk::trace::SpanData {
            span_context: sc,
            parent_span_id: SpanId::INVALID,
            parent_span_is_remote: false,
            span_kind: SpanKind::Server,
            name: "direct-export".into(),
            start_time: now - Duration::from_millis(5),
            end_time: now,
            attributes: vec![KeyValue::new("grow.test", run.clone())],
            dropped_attributes_count: 0,
            events: Default::default(),
            links: Default::default(),
            status: Status::Ok,
            instrumentation_scope: Default::default(),
        };
        (id, d)
    };
    exporter.export(vec![data.1]).await.unwrap();
    let t = q.wait_for_trace(&data.0, wait()).await.unwrap();
    assert_eq!(t.spans[0].name, "direct-export");
    assert_eq!(t.spans[0].service_name, sdk::UNKNOWN_SERVICE);
    assert_eq!(t.spans[0].status.code, StatusCode::Ok);
    c.close().await;
}

#[test]
fn blocking_round_trip() {
    load_bootstrap_env();
    let c = sdk::blocking::ZipkinClient::from_env().unwrap();
    let run = run_tag();
    let service = format!("grow-rust-{run}-blocking");
    let trace = random_hex(32);
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert_eq!(
        c.server_version().unwrap(),
        c.server_info().unwrap().version.unwrap()
    );
    c.report(&[ZipkinSpan::new(trace.clone(), "0000000000000001")
        .with_kind(ZipkinKind::Client)
        .with_name("blocking-op")
        .with_timing(now_micros() - 10_000, 2000)
        .with_local_service(service.clone())
        .with_remote_endpoint(ZipkinEndpoint::service(format!("{service}-peer")))
        .with_tag("grow.test", run.as_str())])
        .unwrap();
    assert_eq!(c.get_trace_spans(&trace).unwrap().unwrap().len(), 1);
    assert_eq!(
        c.get_trace(&trace).unwrap().unwrap().spans[0].name,
        "blocking-op"
    );
    assert_eq!(c.wait_for_trace(&trace, wait()).unwrap().spans.len(), 1);
    assert_eq!(
        c.get_traces(&ZipkinTraceQuery::service(service.clone()))
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        c.get_trace_many(std::slice::from_ref(&trace))
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        c.search_traces(&TraceSearchQuery::service(service.clone()))
            .unwrap()
            .len(),
        1
    );
    assert!(c.list_services().unwrap().contains(&service));
    assert_eq!(c.list_spans(&service).unwrap(), ["blocking-op"]);
    assert_eq!(c.list_operations(&service).unwrap(), ["blocking-op"]);
    assert_eq!(
        c.list_remote_services(&service).unwrap(),
        [format!("{service}-peer")]
    );
    assert!(c
        .get_dependencies(ZipkinDependencyOptions::default())
        .unwrap()
        .iter()
        .any(|l| l.parent == service));
    assert!(c
        .autocomplete_keys()
        .unwrap()
        .contains(&"grow.test".to_owned()));
    assert!(c.autocomplete_values("grow.test").unwrap().contains(&run));
    c.close();
    assert_eq!(c.ping().unwrap_err().code(), ErrorCode::Cancelled);
}
