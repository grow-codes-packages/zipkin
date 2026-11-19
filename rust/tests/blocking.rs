// Taken over from sdk-kit 0.1.0 (header removed): the skeleton test used the demo service's
// routes and a token; this one serves recorded Zipkin responses and calls every blocking
// method.
//! Blocking client (feature `blocking`).
#![cfg(feature = "blocking")]

mod support;

use std::time::{Duration, UNIX_EPOCH};

use grow_zipkin as sdk;
use sdk::core::ErrorCode;
use sdk::tracing::{TraceSearchQuery, WaitForTraceOptions};
use sdk::{ZipkinDependencyOptions, ZipkinSpan, ZipkinTraceQuery};
use support::{fast_retry, respond};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use sdk::blocking::ZipkinClient as Client;

use sdk::ZipkinConfig as Config;

const TRACE: &str = "5af7183fb1d4cf5fad1c0df61d0a9b8c";

#[test]
fn blocking_client() {
    // The mock server lives on its own runtime; the blocking client owns another one.
    let rt = tokio::runtime::Runtime::new().unwrap();
    let server = rt.block_on(async {
        let server = MockServer::start().await;
        let routes = [
            ("/health", respond("health.ok")),
            ("/info", respond("serverVersion.ok")),
            ("/api/v2/services", respond("listServices.ok")),
            ("/api/v2/spans", respond("listSpans.ok")),
            ("/api/v2/remoteServices", respond("listRemoteServices.ok")),
            ("/api/v2/autocompleteKeys", respond("autocompleteKeys.ok")),
            (
                "/api/v2/autocompleteValues",
                respond("autocompleteValues.ok"),
            ),
            ("/api/v2/dependencies", respond("getDependencies.ok")),
            ("/api/v2/traces", respond("getTraces.allFilters")),
            ("/api/v2/traceMany", respond("getTraceMany.ok")),
            ("/api/v2/trace/463ac35cad1c0df6", respond("getTrace.id64")),
        ];
        Mock::given(method("POST"))
            .and(path("/api/v2/spans"))
            .respond_with(ResponseTemplate::new(202))
            .mount(&server)
            .await;
        Mock::given(path(format!("/api/v2/trace/{TRACE}")))
            .respond_with(respond("getTrace.ok"))
            .mount(&server)
            .await;
        for (p, r) in routes {
            Mock::given(method("GET"))
                .and(path(p))
                .respond_with(r)
                .mount(&server)
                .await;
        }
        server
    });
    let cfg = Config::builder()
        .url(server.uri())
        .retry(fast_retry())
        .build()
        .unwrap();
    let c = Client::new(cfg).unwrap();
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert_eq!(c.server_version().unwrap(), "3.6.1");
    assert_eq!(c.server_info().unwrap().version.as_deref(), Some("3.6.1"));
    c.report(&[ZipkinSpan::new(TRACE, "0000000000000009").with_name("x")])
        .unwrap();
    assert!(c.get_trace_spans(TRACE).unwrap().is_some());
    assert_eq!(c.get_trace(TRACE).unwrap().unwrap().trace_id, TRACE);
    assert_eq!(c.get_traces(&ZipkinTraceQuery::default()).unwrap().len(), 1);
    assert_eq!(
        c.get_trace_many(&[TRACE, "463ac35cad1c0df6"])
            .unwrap()
            .len(),
        2
    );
    let mut q = TraceSearchQuery::service("grow-frontend");
    q.end = Some(UNIX_EPOCH + Duration::from_secs(1_795_035_940));
    assert_eq!(c.search_traces(&q).unwrap().len(), 1);
    assert!(!c.list_services().unwrap().is_empty());
    assert!(!c.list_spans("grow-frontend").unwrap().is_empty());
    assert!(!c.list_operations("grow-frontend").unwrap().is_empty());
    assert_eq!(
        c.list_remote_services("grow-frontend").unwrap(),
        ["grow-backend"]
    );
    assert_eq!(
        c.get_dependencies(ZipkinDependencyOptions::default())
            .unwrap()
            .len(),
        2
    );
    assert!(!c.autocomplete_keys().unwrap().is_empty());
    assert!(!c.autocomplete_values("grow.test").unwrap().is_empty());
    let trace = c
        .wait_for_trace(TRACE, WaitForTraceOptions::default().min_spans(2))
        .unwrap();
    assert!(trace.spans.len() >= 2);
    assert!(!c.async_client().is_closed());
    assert_eq!(c.block_on(async { 7 }), 7);
    c.close();
    c.close();
    assert_eq!(c.ping().unwrap_err().code(), ErrorCode::Cancelled);
    let _ = Client::from_env();
    let bad = Config::builder()
        .ca_cert_path("/nonexistent")
        .build()
        .unwrap();
    assert_eq!(Client::new(bad).unwrap_err().code(), ErrorCode::Config);
    drop(server);
}
