// Service tests (Zipkin): ZipkinReporter against a mock of POST /api/v2/spans.
//! Reporter: JSON body, normalization, batching, gzip, empty batches, errors and retries.

mod support;

use std::io::Read;

use grow_zipkin as sdk;
use sdk::core::ErrorCode;
use sdk::tracing::Compression;
use sdk::{ZipkinKind, ZipkinReporter, ZipkinReporterOptions, ZipkinSpan};
use serde_json::Value;
use support::{client, config_builder, fixture, text};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const TRACE: &str = "5af7183fb1d4cf5fad1c0df61d0a9b8c";

fn span(i: u64) -> ZipkinSpan {
    ZipkinSpan::new(TRACE.to_ascii_uppercase(), format!("{i:016X}"))
        .with_kind(ZipkinKind::Server)
        .with_name("op")
        .with_local_service("grow-frontend")
        .with_timing(1_795_035_874_000_000, 10)
        .with_tag("n", i)
}

async fn accept(server: &MockServer) {
    Mock::given(method("POST"))
        .and(path("/api/v2/spans"))
        .respond_with(ResponseTemplate::new(202))
        .mount(server)
        .await;
}

#[tokio::test]
async fn reports_the_recorded_payload() {
    let server = MockServer::start().await;
    accept(&server).await;
    let recorded: Vec<ZipkinSpan> =
        serde_json::from_value(fixture("report.json")["request"]["body"].clone()).unwrap();
    client(&server).reporter().report(&recorded).await.unwrap();
    let reqs = server.received_requests().await.unwrap();
    assert_eq!(reqs.len(), 1);
    assert_eq!(
        reqs[0].headers.get("content-type").unwrap(),
        "application/json"
    );
    assert!(reqs[0].headers.get("content-encoding").is_none());
    let sent: Value = serde_json::from_slice(&reqs[0].body).unwrap();
    assert_eq!(sent, fixture("report.json")["request"]["body"]);
}

#[tokio::test]
async fn normalizes_batches_and_skips_empty_reports() {
    let server = MockServer::start().await;
    accept(&server).await;
    let c = client(&server);
    let reporter = c
        .create_reporter(ZipkinReporterOptions::default().max_batch_size(2))
        .unwrap();
    assert_eq!(reporter.max_batch_size(), 2);
    assert_eq!(reporter.compression(), Compression::None);
    assert_eq!(
        reporter.endpoint(),
        format!("{}/api/v2/spans", server.uri())
    );
    reporter.report(&[]).await.unwrap();
    assert!(server.received_requests().await.unwrap().is_empty());
    let spans: Vec<ZipkinSpan> = (1..=5).map(span).collect();
    reporter.report(&spans).await.unwrap();
    let reqs = server.received_requests().await.unwrap();
    assert_eq!(reqs.len(), 3, "5 spans in batches of 2");
    let first: Value = serde_json::from_slice(&reqs[0].body).unwrap();
    assert_eq!(first[0]["traceId"], TRACE, "ids lower-cased");
    assert_eq!(first[0]["id"], "0000000000000001");
    assert_eq!(first[0]["tags"]["n"], "1", "tags are strings");
    let last: Value = serde_json::from_slice(&reqs[2].body).unwrap();
    assert_eq!(last.as_array().unwrap().len(), 1);

    // one invalid span fails the whole call before anything is sent
    let before = server.received_requests().await.unwrap().len();
    let err = reporter
        .report(&[span(1), ZipkinSpan::new("nope", "0000000000000001")])
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert_eq!(server.received_requests().await.unwrap().len(), before);
}

#[tokio::test]
async fn gzip_and_endpoint_override() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/gw/zipkin/spans"))
        .and(header("content-encoding", "gzip"))
        .respond_with(ResponseTemplate::new(202))
        .expect(1)
        .mount(&server)
        .await;
    let reporter = ZipkinReporter::new(
        config_builder(&server).build().unwrap(),
        ZipkinReporterOptions::default()
            .compression(Compression::Gzip)
            .endpoint(format!("{}/gw/zipkin/spans", server.uri())),
    )
    .unwrap();
    reporter.report(&[span(1)]).await.unwrap();
    let reqs = server.received_requests().await.unwrap();
    let mut json = String::new();
    flate2::read::GzDecoder::new(&reqs[0].body[..])
        .read_to_string(&mut json)
        .unwrap();
    let sent: Value = serde_json::from_str(&json).unwrap();
    assert_eq!(sent[0]["name"], "op");
    reporter.close().await;
    reporter.close().await;
    assert_eq!(
        reporter.report(&[span(1)]).await.unwrap_err().code(),
        ErrorCode::Cancelled
    );
}

#[tokio::test]
async fn errors_and_retries() {
    let server = MockServer::start().await;
    Mock::given(path("/bad/api/v2/spans"))
        .respond_with(text(
            400,
            "Expected a JSON_V2 encoded list, but received: JSON_V1\n",
        ))
        .mount(&server)
        .await;
    Mock::given(path("/big/api/v2/spans"))
        .respond_with(ResponseTemplate::new(413))
        .mount(&server)
        .await;
    Mock::given(path("/flaky/api/v2/spans"))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(path("/flaky/api/v2/spans"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&server)
        .await;
    let at = |p: &str| {
        sdk::ZipkinClient::new(
            config_builder(&server)
                .url(format!("{}/{p}", server.uri()))
                .build()
                .unwrap(),
        )
        .unwrap()
        .reporter()
    };
    let err = at("bad").report(&[span(1)]).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(err.message().contains("JSON_V1"), "{err}");
    assert!(err.message().starts_with("zipkin.report"));
    let err = at("big").report(&[span(1)]).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert_eq!(err.http_status(), Some(413));
    // 5xx is retried: ingest is idempotent
    at("flaky").report(&[span(1)]).await.unwrap();
    assert_eq!(
        server
            .received_requests()
            .await
            .unwrap()
            .iter()
            .filter(|r| r.url.path().starts_with("/flaky"))
            .count(),
        2
    );
}

#[tokio::test]
async fn option_validation() {
    let server = MockServer::start().await;
    let c = client(&server);
    assert_eq!(
        c.create_reporter(ZipkinReporterOptions::default().max_batch_size(0))
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    assert_eq!(
        c.create_reporter(ZipkinReporterOptions::default().endpoint("ftp://x"))
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    // the shared reporter does not close the client
    c.reporter().close().await;
    assert!(!c.is_closed());
    let _ = ZipkinReporter::from_env(ZipkinReporterOptions::default());
    assert_eq!(
        ZipkinReporterOptions::default(),
        ZipkinReporterOptions {
            compression: Compression::None,
            max_batch_size: sdk::DEFAULT_MAX_BATCH_SIZE,
            endpoint: None
        }
    );
}
