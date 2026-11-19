// Taken over from sdk-kit 0.1.0 (header removed): the skeleton tests targeted the demo
// service (token, /v1/ping, /version); these test the Zipkin client surface against recorded
// Zipkin responses.
//! `ZipkinClient` and `ZipkinConfig`: health, ping, server info/version, close, config and
//! environment precedence, redaction.

mod support;

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use grow_zipkin as sdk;
use sdk::core::{EnvReader, ErrorCode};
use sdk::{base_from_endpoint, spans_endpoint, ZipkinServerInfo};
use serde_json::json;
use support::{client, closed_port, config_builder, respond};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use sdk::ZipkinClient as Client;
use sdk::ZipkinConfig as Config;

#[tokio::test]
async fn client_surface_against_recorded_responses() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/health"))
        .respond_with(respond("health.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/services"))
        .and(header("authorization", "Bearer gw-secret-123"))
        .respond_with(respond("listServices.ok"))
        .mount(&server)
        .await;
    Mock::given(path("/info"))
        .respond_with(respond("serverVersion.ok"))
        .mount(&server)
        .await;
    let c = Client::new(
        config_builder(&server)
            .header("Authorization", "Bearer gw-secret-123")
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c.health().await.unwrap();
    assert!(h.healthy);
    assert_eq!(h.status, "UP");
    assert_eq!(h.details["zipkin"], "UP");
    assert!(h.details["storage"].contains("InMemoryStorage"));
    assert!(c.ping().await.unwrap() < Duration::from_secs(5));
    assert_eq!(c.server_version().await.unwrap(), "3.6.1");
    let info = c.server_info().await.unwrap();
    assert_eq!(info.version.as_deref(), Some("3.6.1"));
    assert!(info.commit.is_some());
    // gateway credentials never show up in Debug output
    let dbg = format!("{c:?} {:?}", c.config());
    assert!(!dbg.contains("gw-secret-123"), "{dbg}");
    assert_eq!(c.config().known_secrets().len(), 1);
}

#[tokio::test]
async fn health_variants() {
    let server = MockServer::start().await;
    Mock::given(path("/a/health"))
        .respond_with(ResponseTemplate::new(503).set_body_json(json!({"status": "DOWN"})))
        .mount(&server)
        .await;
    Mock::given(path("/b/health"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;
    Mock::given(path("/c/health"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"status": "STARTING"})))
        .mount(&server)
        .await;
    Mock::given(path("/d/health"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;
    let at = |p: &str| {
        Client::new(
            config_builder(&server)
                .url(format!("{}/{p}", server.uri()))
                .max_retries(0)
                .build()
                .unwrap(),
        )
        .unwrap()
    };
    let a = at("a").health().await.unwrap();
    assert_eq!((a.healthy, a.status.as_str()), (false, "DOWN"));
    assert_eq!(a.details["httpStatus"], "503");
    let b = at("b").health().await.unwrap();
    assert_eq!((b.healthy, b.status.as_str()), (false, "unhealthy"));
    let c = at("c").health().await.unwrap();
    assert_eq!((c.healthy, c.status.as_str()), (false, "STARTING"));
    let d = at("d").health().await.unwrap();
    assert_eq!((d.healthy, d.status.as_str()), (true, "UP"));
}

#[tokio::test]
async fn core_health_unreachable() {
    let c = Client::new(
        Config::builder()
            .url(format!("http://127.0.0.1:{}", closed_port()))
            .max_retries(0)
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unreachable");
    assert!(h.details.contains_key("error"));
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Unavailable);
    // closed clients report CANCELLED, which health() returns as an error
    c.close().await;
    assert_eq!(c.health().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn server_version_unsupported() {
    let server = MockServer::start().await;
    Mock::given(path("/a/info"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    Mock::given(path("/b/info"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"zipkin": {"version": ""}})))
        .mount(&server)
        .await;
    Mock::given(path("/c/info"))
        .respond_with(ResponseTemplate::new(403))
        .mount(&server)
        .await;
    Mock::given(path("/d/info"))
        .respond_with(ResponseTemplate::new(200).set_body_string("not json"))
        .mount(&server)
        .await;
    let at = |p: &str| {
        Client::new(
            config_builder(&server)
                .url(format!("{}/{p}", server.uri()))
                .build()
                .unwrap(),
        )
        .unwrap()
    };
    assert_eq!(
        at("a").server_version().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
    assert_eq!(
        at("a").server_info().await.unwrap(),
        ZipkinServerInfo::default()
    );
    assert_eq!(
        at("b").server_version().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
    assert_eq!(
        at("c").server_version().await.unwrap_err().code(),
        ErrorCode::PermissionDenied
    );
    assert_eq!(
        at("d").server_version().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
}

#[tokio::test]
async fn core_close_idempotent() {
    let server = MockServer::start().await;
    let c = client(&server);
    let closed = Arc::new(AtomicU32::new(0));
    let counter = closed.clone();
    c.on_close(move || {
        Box::pin(async move {
            counter.fetch_add(1, Ordering::SeqCst);
        })
    });
    assert!(!c.is_closed());
    c.close().await;
    c.close().await;
    assert!(c.is_closed());
    assert_eq!(closed.load(Ordering::SeqCst), 1);
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    assert!(c.transport().is_closed());
}

#[test]
fn endpoint_helpers() {
    assert_eq!(
        spans_endpoint("http://z:9411/"),
        "http://z:9411/api/v2/spans"
    );
    assert_eq!(
        spans_endpoint("https://gw/zipkin/api/v2/spans/"),
        "https://gw/zipkin/api/v2/spans"
    );
    assert_eq!(
        base_from_endpoint("https://gw/zipkin/api/v2/spans/"),
        "https://gw/zipkin"
    );
    assert_eq!(base_from_endpoint("http://z:9411"), "http://z:9411");
}

#[test]
fn config_defaults_and_errors() {
    let c = Config::builder().build().unwrap();
    assert_eq!(c.url().as_str(), "http://127.0.0.1:9411/");
    assert_eq!(c.endpoint(), "http://127.0.0.1:9411/api/v2/spans");
    let gw = Config::builder()
        .url("https://gw.example/zipkin")
        .build()
        .unwrap();
    assert_eq!(gw.endpoint(), "https://gw.example/zipkin/api/v2/spans");
    let ep = Config::builder()
        .endpoint(" http://c:1/x ")
        .build()
        .unwrap();
    assert_eq!(ep.endpoint(), "http://c:1/x");
    assert_eq!(
        Config::builder()
            .url("mailto:x")
            .build()
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    assert_eq!(
        Config::builder()
            .endpoint("ftp://x/y")
            .build()
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    let cfg = Config::builder()
        .ca_cert_path("/nonexistent/ca.pem")
        .build()
        .unwrap();
    assert_eq!(Client::new(cfg).unwrap_err().code(), ErrorCode::Config);
    let _ = Client::from_env();
    // every builder setter is accepted
    let full = Config::builder()
        .connect_timeout(Duration::from_secs(1))
        .request_timeout(Duration::from_secs(2))
        .total_timeout(Duration::from_secs(3))
        .tls(Default::default())
        .insecure_skip_verify(true)
        .proxy("http://user:pw@proxy:3128")
        .user_agent_suffix("app/1")
        .hooks(Arc::new(NoHooks))
        .common(|b| b.max_retries(1))
        .build()
        .unwrap();
    assert_eq!(full.common().retry().max_retries, 1);
    assert!(!format!("{full:?}").contains("pw@"));
}

struct NoHooks;
impl sdk::core::Hooks for NoHooks {}

#[test]
fn from_env_precedence() {
    let env = |vars: &[(&str, &str)]| EnvReader::from_map(vars.iter().copied());
    let build = |vars: &[(&str, &str)]| Config::builder().build_with_env(&env(vars)).unwrap();

    let c = build(&[]);
    assert_eq!(c.endpoint(), "http://127.0.0.1:9411/api/v2/spans");
    // GROW_ZIPKIN_URL > ZIPKIN_URL > base of OTEL_EXPORTER_ZIPKIN_ENDPOINT
    let c = build(&[
        ("GROW_ZIPKIN_URL", "http://grow:9411"),
        ("ZIPKIN_URL", "http://vendor:9411"),
        (
            "OTEL_EXPORTER_ZIPKIN_ENDPOINT",
            "http://otel:9411/api/v2/spans",
        ),
    ]);
    assert_eq!(c.url().as_str(), "http://grow:9411/");
    assert_eq!(
        c.endpoint(),
        "http://otel:9411/api/v2/spans",
        "endpoint verbatim"
    );
    let c = build(&[("ZIPKIN_URL", "http://vendor:9411")]);
    assert_eq!(c.endpoint(), "http://vendor:9411/api/v2/spans");
    let c = build(&[(
        "OTEL_EXPORTER_ZIPKIN_ENDPOINT",
        "https://gw.example/zipkin/api/v2/spans",
    )]);
    assert_eq!(c.url().as_str(), "https://gw.example/zipkin");
    // GROW_ZIPKIN_ENDPOINT > OTEL_EXPORTER_ZIPKIN_ENDPOINT
    let c = build(&[
        ("GROW_ZIPKIN_ENDPOINT", "http://ingest:9411/custom"),
        (
            "OTEL_EXPORTER_ZIPKIN_ENDPOINT",
            "http://otel:9411/api/v2/spans",
        ),
        ("GROW_ZIPKIN_MAX_RETRIES", "5"),
    ]);
    assert_eq!(c.endpoint(), "http://ingest:9411/custom");
    assert_eq!(c.common().retry().max_retries, 5);
    // explicit wins over the environment
    let c = Config::builder()
        .url("http://explicit:9411")
        .endpoint("http://explicit:9411/api/v2/spans")
        .build_with_env(&env(&[
            ("GROW_ZIPKIN_URL", "http://grow:9411"),
            ("GROW_ZIPKIN_ENDPOINT", "http://ingest:9411/custom"),
        ]))
        .unwrap();
    assert_eq!(c.url().as_str(), "http://explicit:9411/");
    assert_eq!(c.endpoint(), "http://explicit:9411/api/v2/spans");
    // invalid values name the variable, not the value
    let err = Config::builder()
        .build_with_env(&env(&[("GROW_ZIPKIN_MAX_RETRIES", "lots")]))
        .unwrap_err();
    assert!(err.message().contains("GROW_ZIPKIN_MAX_RETRIES"));
    assert!(!err.message().contains("lots"));
    let _ = Config::from_env();
}
