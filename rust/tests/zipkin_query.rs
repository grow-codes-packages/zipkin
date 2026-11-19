// Service tests (Zipkin): ZipkinQueryClient against recorded Zipkin 3.6.1 responses.
//! Query API: getTrace(Spans), getTraces, getTraceMany, name lists, dependencies,
//! autocomplete, searchTraces, the TraceQueryClient trait and error mapping.

mod support;

use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::{Duration, UNIX_EPOCH};

use grow_zipkin as sdk;
use sdk::core::ErrorCode;
use sdk::tracing::{
    DependencyOptions, SpanKind, StatusCode, TraceQueryClient, TraceSearchQuery,
    WaitForTraceOptions,
};
use sdk::{build_annotation_query, ZipkinDependencyOptions, ZipkinQueryClient, ZipkinTraceQuery};
use serde_json::json;
use support::{body, client, config_builder, respond, text};
use wiremock::matchers::{method, path, query_param, query_param_is_missing};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const TRACE: &str = "5af7183fb1d4cf5fad1c0df61d0a9b8c";
const NOW_MS: u64 = 1_795_035_940_000;

fn now() -> std::time::SystemTime {
    UNIX_EPOCH + Duration::from_millis(NOW_MS)
}

#[tokio::test]
async fn get_trace_and_spans() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/v2/trace/{TRACE}")))
        .respond_with(respond("getTrace.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/463ac35cad1c0df6"))
        .respond_with(respond("getTrace.id64"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/ffffffffffffffffffffffffffffffff"))
        .respond_with(text(404, "ffffffffffffffffffffffffffffffff not found"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([])))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/dddddddddddddddddddddddddddddddd"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    let q = client(&server).query();

    // upper-case ids are lower-cased before the request
    let spans = q
        .get_trace_spans(&TRACE.to_ascii_uppercase())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(spans.len(), body("getTrace.ok").as_array().unwrap().len());
    assert_eq!(spans[0].id, "0000000000000001");

    let trace = q.get_trace(TRACE).await.unwrap().unwrap();
    assert_eq!(trace.trace_id, TRACE);
    let server_span = trace
        .spans
        .iter()
        .find(|s| s.span_id == "0000000000000001")
        .unwrap();
    assert_eq!(server_span.kind, SpanKind::Server);
    assert_eq!(server_span.service_name, "grow-frontend");
    assert_eq!(server_span.events[0].name, "cache.miss");
    assert_eq!(server_span.duration_micros, 150_000);
    assert!(trace
        .spans
        .iter()
        .any(|s| s.status.code == StatusCode::Error));

    // 64-bit ids are padded in the normalized model
    let short = q.get_trace("463ac35cad1c0df6").await.unwrap().unwrap();
    assert_eq!(short.trace_id, "0000000000000000463ac35cad1c0df6");
    assert_eq!(short.spans[0].kind, SpanKind::Internal);

    // 404, an empty array and an empty body all mean "no such trace"
    assert!(q
        .get_trace("ffffffffffffffffffffffffffffffff")
        .await
        .unwrap()
        .is_none());
    assert!(q
        .get_trace_spans("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
        .await
        .unwrap()
        .is_none());
    assert!(q
        .get_trace_spans("dddddddddddddddddddddddddddddddd")
        .await
        .unwrap()
        .is_none());

    // invalid ids never reach the server
    for bad in ["zz", "", "123", "00000000000000000000000000000000"] {
        let err = q.get_trace(bad).await.unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation, "{bad}");
    }
    assert_eq!(server.received_requests().await.unwrap().len(), 6);
}

#[tokio::test]
async fn get_traces_all_filters_match_the_recorded_request() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/traces"))
        .and(query_param("serviceName", "grow-frontend"))
        .and(query_param("spanName", "get /api/orders"))
        .and(query_param(
            "annotationQuery",
            "http.method=GET and cache.miss",
        ))
        .and(query_param("minDuration", "100000"))
        .and(query_param("maxDuration", "200000"))
        .and(query_param("endTs", NOW_MS.to_string()))
        .and(query_param("lookback", "3600000"))
        .and(query_param("limit", "5"))
        .respond_with(respond("getTraces.allFilters"))
        .expect(1)
        .mount(&server)
        .await;
    let q = client(&server).query();
    let traces = q
        .get_traces(
            &ZipkinTraceQuery::service("grow-frontend")
                .span_name("get /api/orders")
                .tag("http.method", "GET")
                .annotation("cache.miss")
                .duration(
                    Some(Duration::from_millis(100)),
                    Some(Duration::from_millis(200)),
                )
                .window(now(), Duration::from_secs(3600))
                .limit(5),
        )
        .await
        .unwrap();
    assert_eq!(traces.len(), 1);
    assert_eq!(traces[0][0].trace_id, TRACE);
}

#[tokio::test]
async fn get_traces_defaults_raw_query_and_local_terms() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/traces"))
        .and(query_param_is_missing("serviceName"))
        .and(query_param_is_missing("annotationQuery"))
        .and(query_param_is_missing("limit"))
        .and(query_param_is_missing("endTs"))
        .respond_with(respond("getTraces.defaults"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/traces"))
        .and(query_param("annotationQuery", "error and http.method=GET"))
        .and(query_param("remoteServiceName", "grow-backend"))
        .respond_with(respond("getTraces.allFilters"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/traces"))
        .and(query_param("serviceName", "local"))
        .and(query_param("annotationQuery", "http.method=GET"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            [{"traceId": "000000000000000b", "id": "0000000000000001", "tags": {"k=v": "x"}}]
        ])))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/traces"))
        .and(query_param("serviceName", "local"))
        .and(query_param_is_missing("annotationQuery"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            [{"traceId": "000000000000000a", "id": "0000000000000001",
              "tags": {"note": "cats and dogs"},
              "annotations": [{"timestamp": 1, "value": "start and stop"}]}],
            [{"traceId": "000000000000000b", "id": "0000000000000001", "tags": {"k=v": "x"}}],
            [],
            [{"no": "trace id"}]
        ])))
        .mount(&server)
        .await;
    let q = client(&server).query();
    let defaults = q.get_traces(&ZipkinTraceQuery::default()).await.unwrap();
    assert_eq!(
        defaults.len(),
        body("getTraces.defaults")
            .as_array()
            .unwrap()
            .iter()
            .filter(|t| !t.as_array().unwrap().is_empty())
            .count()
    );
    // blank strings count as unset
    let blank = ZipkinTraceQuery {
        service_name: Some(" ".into()),
        annotation_query: Some("".into()),
        ..Default::default()
    };
    assert_eq!(q.get_traces(&blank).await.unwrap().len(), defaults.len());

    let raw = q
        .get_traces(
            &ZipkinTraceQuery::default()
                .annotation_query(" error ")
                .tag("http.method", "GET")
                .remote_service_name("grow-backend"),
        )
        .await
        .unwrap();
    assert_eq!(raw.len(), 1);

    // terms Zipkin cannot express are matched client-side (and not sent)
    let ids = |traces: Vec<Vec<sdk::ZipkinSpan>>| -> Vec<String> {
        traces.into_iter().map(|t| t[0].trace_id.clone()).collect()
    };
    let local = ZipkinTraceQuery::service("local");
    assert_eq!(
        ids(q
            .get_traces(&local.clone().tag("note", "cats and dogs"))
            .await
            .unwrap()),
        ["000000000000000a"]
    );
    assert!(q
        .get_traces(&local.clone().tag("note", "nope and nope"))
        .await
        .unwrap()
        .is_empty());
    assert_eq!(
        ids(q
            .get_traces(&local.clone().annotation("start and stop"))
            .await
            .unwrap()),
        ["000000000000000a"]
    );
    assert_eq!(
        ids(q
            .get_traces(&local.clone().annotation("k=v"))
            .await
            .unwrap()),
        ["000000000000000b"]
    );
    assert_eq!(
        ids(q.get_traces(&local.clone().tag("k=v", "x")).await.unwrap()),
        ["000000000000000b"]
    );
    // a server-side term next to a local one: only the server-side one is sent
    assert_eq!(
        ids(q
            .get_traces(&local.clone().tag("http.method", "GET").tag("k=v", "x"))
            .await
            .unwrap()),
        ["000000000000000b"]
    );
}

#[tokio::test]
async fn get_traces_local_validation() {
    let server = MockServer::start().await;
    let q = client(&server).query();
    let cases = [
        ZipkinTraceQuery::default().limit(0),
        ZipkinTraceQuery::default().duration(None, Some(Duration::from_millis(1))),
        ZipkinTraceQuery::default().duration(
            Some(Duration::from_millis(2)),
            Some(Duration::from_millis(1)),
        ),
        ZipkinTraceQuery {
            lookback: Some(Duration::ZERO),
            ..Default::default()
        },
        ZipkinTraceQuery {
            end_ts: Some(UNIX_EPOCH),
            ..Default::default()
        },
        ZipkinTraceQuery::default().tag("", "x"),
        ZipkinTraceQuery::default().annotation(""),
    ];
    for case in &cases {
        let err = q.get_traces(case).await.unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation, "{case:?}");
    }
    assert!(server.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn server_400_maps_to_validation() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/traces"))
        .respond_with(respond("getTraces.limitZero400"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans"))
        .respond_with(respond("listSpans.missing400"))
        .mount(&server)
        .await;
    let q = client(&server).query();
    let err = q
        .get_traces(&ZipkinTraceQuery::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert_eq!(err.http_status(), Some(400));
    assert!(!err.is_retryable());
    let err = q.list_spans("x").await.unwrap_err();
    assert!(err.message().contains("serviceName"), "{err}");
}

#[tokio::test]
async fn get_trace_many_order_dedupe_and_chunks() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/traceMany"))
        .and(query_param(
            "traceIds",
            "5af7183fb1d4cf5fad1c0df61d0a9b8c,463ac35cad1c0df6,ffffffffffffffffffffffffffffffff",
        ))
        .respond_with(respond("getTraceMany.ok"))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/463ac35cad1c0df6"))
        .respond_with(respond("getTrace.id64"))
        .mount(&server)
        .await;
    Mock::given(path(format!("/api/v2/trace/{TRACE}")))
        .respond_with(respond("getTrace.ok"))
        .mount(&server)
        .await;
    let q = client(&server).query();
    let empty: [&str; 0] = [];
    assert!(q.get_trace_many(&empty).await.unwrap().is_empty());
    let many = q
        .get_trace_many(&[
            TRACE,
            "463AC35CAD1C0DF6",
            "0000000000000000463ac35cad1c0df6", // same as the 64-bit id
            "ffffffffffffffffffffffffffffffff", // missing → omitted
            TRACE,
        ])
        .await
        .unwrap();
    assert_eq!(many.len(), 2);
    assert_eq!(many[0][0].trace_id, TRACE, "request order");
    assert_eq!(many[1][0].trace_id, "463ac35cad1c0df6");
    // one id uses /api/v2/trace/{id}
    let one = q.get_trace_many(&["463ac35cad1c0df6"]).await.unwrap();
    assert_eq!(one.len(), 1);
    assert_eq!(
        q.get_trace_many(&["bad"]).await.unwrap_err().code(),
        ErrorCode::Validation
    );
}

#[tokio::test]
async fn get_trace_many_splits_into_chunks_of_100() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/traceMany"))
        .respond_with(|req: &Request| {
            let ids: Vec<String> = req
                .url
                .query_pairs()
                .find(|(k, _)| k == "traceIds")
                .map(|(_, v)| v.split(',').map(str::to_owned).collect())
                .unwrap_or_default();
            assert!(ids.len() <= sdk::TRACE_MANY_CHUNK);
            let traces: Vec<_> = ids
                .iter()
                .rev()
                .map(|id| json!([{"traceId": id, "id": "0000000000000001", "name": "op"}]))
                .collect();
            ResponseTemplate::new(200).set_body_json(traces)
        })
        .mount(&server)
        .await;
    let last = format!("{:032x}", 201);
    Mock::given(path(format!("/api/v2/trace/{last}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"traceId": last, "id": "0000000000000001"}
        ])))
        .mount(&server)
        .await;
    let ids: Vec<String> = (1..=201).map(|i| format!("{i:032x}")).collect();
    let many = client(&server).query().get_trace_many(&ids).await.unwrap();
    assert_eq!(many.len(), 201);
    for (i, t) in many.iter().enumerate() {
        assert_eq!(t[0].trace_id, ids[i]);
    }
    // 2 traceMany chunks (100 + 100) + the last single id through /api/v2/trace
    assert_eq!(server.received_requests().await.unwrap().len(), 3);
}

#[tokio::test]
async fn name_lists_and_autocomplete() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/services"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!(["b", "a", 7])))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans"))
        .and(query_param("serviceName", "grow-frontend"))
        .respond_with(respond("listSpans.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/remoteServices"))
        .and(query_param("serviceName", "grow-frontend"))
        .respond_with(respond("listRemoteServices.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/autocompleteKeys"))
        .respond_with(respond("autocompleteKeys.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/autocompleteValues"))
        .and(query_param("key", "grow.test"))
        .respond_with(respond("autocompleteValues.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/autocompleteValues"))
        .and(query_param("key", "weird"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"not": "a list"})))
        .mount(&server)
        .await;
    let q = client(&server).query();
    assert_eq!(q.list_services().await.unwrap(), ["a", "b"]);
    let ops = q.list_spans("grow-frontend").await.unwrap();
    assert_eq!(ops.len(), body("listSpans.ok").as_array().unwrap().len());
    assert_eq!(q.list_operations("grow-frontend").await.unwrap(), ops);
    assert_eq!(
        q.list_remote_services("grow-frontend").await.unwrap(),
        ["grow-backend"]
    );
    let keys: Vec<String> = serde_json::from_value(body("autocompleteKeys.ok")).unwrap();
    assert_eq!(q.autocomplete_keys().await.unwrap(), keys);
    let values = q.autocomplete_values("grow.test").await.unwrap();
    assert!(values.contains(&"run-a".to_owned()));
    assert!(values.windows(2).all(|w| w[0] <= w[1]));
    assert!(q.autocomplete_values("weird").await.unwrap().is_empty());
    for err in [
        q.list_spans(" ").await.unwrap_err(),
        q.list_remote_services("").await.unwrap_err(),
        q.autocomplete_values("").await.unwrap_err(),
    ] {
        assert_eq!(err.code(), ErrorCode::Validation);
    }
}

#[tokio::test]
async fn dependencies_windows() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/dependencies"))
        .and(query_param("endTs", NOW_MS.to_string()))
        .and(query_param("lookback", "3600000"))
        .respond_with(respond("getDependencies.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/dependencies"))
        .and(query_param_is_missing("lookback"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([])))
        .mount(&server)
        .await;
    let q = client(&server).query();
    let links = q
        .get_dependencies(ZipkinDependencyOptions::window(
            now(),
            Duration::from_secs(3600),
        ))
        .await
        .unwrap();
    assert_eq!(links.len(), 2);
    assert_eq!(links[0].parent, "grow-frontend", "sorted by parent");
    assert_eq!(links[0].error_count, 1);
    assert_eq!(links[1].error_count, 0, "omitted errorCount defaults to 0");

    // category window: end - start
    let opts = DependencyOptions {
        start: Some(now() - Duration::from_secs(3600)),
        end: Some(now()),
    };
    assert_eq!(q.get_dependencies(opts).await.unwrap().len(), 2);
    let generic = TraceQueryClient::get_dependencies(&q, opts).await.unwrap();
    assert_eq!(generic[1].child, "kafka-broker");
    // no start: server default lookback, end = now
    assert!(q
        .get_dependencies(ZipkinDependencyOptions::default())
        .await
        .unwrap()
        .is_empty());
    // invalid windows
    let bad = [
        ZipkinDependencyOptions {
            start: Some(now()),
            end: Some(now() - Duration::from_secs(1)),
            ..Default::default()
        },
        ZipkinDependencyOptions {
            lookback: Some(Duration::ZERO),
            ..Default::default()
        },
        ZipkinDependencyOptions {
            end_ts: Some(UNIX_EPOCH),
            ..Default::default()
        },
    ];
    for b in bad {
        assert_eq!(
            q.get_dependencies(b).await.unwrap_err().code(),
            ErrorCode::Validation
        );
    }
}

#[tokio::test]
async fn search_traces_maps_the_category_query() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/traces"))
        .and(query_param("serviceName", "grow-frontend"))
        .and(query_param("spanName", "get /api/orders"))
        .and(query_param("annotationQuery", "http.method=GET"))
        .and(query_param("endTs", NOW_MS.to_string()))
        .and(query_param("lookback", "600000"))
        .and(query_param("limit", "20"))
        .respond_with(respond("getTraces.allFilters"))
        .mount(&server)
        .await;
    // no start → 1 h window
    Mock::given(path("/api/v2/traces"))
        .and(query_param("serviceName", "one-hour"))
        .and(query_param("lookback", "3600000"))
        .respond_with(respond("getTraces.allFilters"))
        .mount(&server)
        .await;
    let q = client(&server).query();
    let mut query = TraceSearchQuery::service("grow-frontend")
        .operation("get /api/orders")
        .tag("http.method", "GET");
    query.start = Some(now() - Duration::from_secs(600));
    query.end = Some(now());
    let summaries = q.search_traces(&query).await.unwrap();
    assert_eq!(summaries.len(), 1);
    let s = &summaries[0];
    assert_eq!(s.trace_id, TRACE);
    assert_eq!(s.root_name.as_deref(), Some("get /api/orders"));
    assert_eq!(s.root_service_name.as_deref(), Some("grow-frontend"));
    assert_eq!(s.start_micros, 1_795_035_874_000_000);
    assert!(s.span_count >= 2);
    let generic = TraceQueryClient::search_traces(&q, &query).await.unwrap();
    assert_eq!(generic, summaries);

    let mut hour = TraceSearchQuery::service("one-hour");
    hour.end = Some(now());
    assert_eq!(q.search_traces(&hour).await.unwrap().len(), 1);

    let mut bad = TraceSearchQuery::service("x");
    bad.start = Some(now());
    bad.end = Some(now() - Duration::from_secs(1));
    assert_eq!(
        q.search_traces(&bad).await.unwrap_err().code(),
        ErrorCode::Validation
    );
    bad.start = Some(UNIX_EPOCH);
    bad.end = Some(now());
    assert_eq!(
        q.search_traces(&bad).await.unwrap_err().code(),
        ErrorCode::Validation
    );
}

#[tokio::test]
async fn trait_object_and_wait_for_trace() {
    let server = MockServer::start().await;
    Mock::given(path(format!("/api/v2/trace/{TRACE}")))
        .respond_with(respond("getTrace.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/services"))
        .respond_with(respond("listServices.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans"))
        .respond_with(respond("listSpans.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/ffffffffffffffffffffffffffffffff"))
        .respond_with(text(404, "not found"))
        .mount(&server)
        .await;
    let q = client(&server).query();
    let dynq: Arc<dyn TraceQueryClient> = Arc::new(q.clone());
    assert!(dynq.get_trace(TRACE).await.unwrap().is_some());
    assert!(!dynq.list_services().await.unwrap().is_empty());
    assert!(!dynq
        .list_operations("grow-frontend")
        .await
        .unwrap()
        .is_empty());
    let fast = WaitForTraceOptions::default()
        .timeout(Duration::from_millis(200))
        .interval(Duration::from_millis(20));
    let trace = q.wait_for_trace(TRACE, fast.min_spans(2)).await.unwrap();
    assert!(trace.spans.len() >= 2);
    assert_eq!(
        q.wait_for_trace("ffffffffffffffffffffffffffffffff", fast)
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Timeout
    );
    assert_eq!(
        q.wait_for_trace("nope", fast).await.unwrap_err().code(),
        ErrorCode::Validation
    );
}

#[tokio::test]
async fn standalone_client_owns_its_connections() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/services"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!(["a"])))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/autocompleteKeys"))
        .respond_with(ResponseTemplate::new(503).set_body_string("down"))
        .mount(&server)
        .await;
    let own = ZipkinQueryClient::new(config_builder(&server).build().unwrap()).unwrap();
    assert_eq!(own.list_services().await.unwrap(), ["a"]);
    let err = own.autocomplete_keys().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
    assert!(err.is_retryable());
    own.close().await;
    own.close().await;
    assert_eq!(
        own.list_services().await.unwrap_err().code(),
        ErrorCode::Cancelled
    );
    // the shared query client of a ZipkinClient does not close the client
    let c = client(&server);
    c.query().close().await;
    assert_eq!(c.query().list_services().await.unwrap(), ["a"]);
    let _ = ZipkinQueryClient::from_env();
}

#[test]
fn annotation_query_builder() {
    let mut tags = BTreeMap::new();
    assert_eq!(build_annotation_query(&tags, &[]).unwrap(), None);
    tags.insert("http.method".to_owned(), "GET".to_owned());
    assert_eq!(
        build_annotation_query(&tags, &["error".to_owned()])
            .unwrap()
            .as_deref(),
        Some("http.method=GET and error")
    );
    for (k, v, a) in [
        ("x and y", "1", None),
        ("k=v", "1", None),
        ("k", "a and b", None),
        ("", "1", None),
        ("k", "1", Some("")),
    ] {
        let mut t = BTreeMap::new();
        t.insert(k.to_owned(), v.to_owned());
        let ann: Vec<String> = a.map(|s: &str| vec![s.to_owned()]).unwrap_or_default();
        assert_eq!(
            build_annotation_query(&t, &ann).unwrap_err().code(),
            ErrorCode::Validation
        );
    }
}
