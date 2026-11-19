// Service test helpers (Zipkin): recorded fixtures and clients pointing at a mock server.
#![allow(dead_code)]

use std::time::Duration;

use grow_zipkin as sdk;
use sdk::core::{RandomSource, RetryPolicy};
use serde_json::Value;
use wiremock::{MockServer, ResponseTemplate};

/// A recorded fixture (`{request, response}`) from `tests/fixtures/zipkin`.
pub fn fixture(name: &str) -> Value {
    let path = format!(
        "{}/tests/fixtures/zipkin/{name}.json",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(&path).expect("fixture")).expect("json")
}

/// The recorded response body of a fixture.
pub fn body(name: &str) -> Value {
    fixture(name)["response"]["body"].clone()
}

/// The recorded response of a fixture as a mock response (JSON or plain text).
pub fn respond(name: &str) -> ResponseTemplate {
    let f = fixture(name);
    let status = f["response"]["status"].as_u64().unwrap() as u16;
    match &f["response"]["body"] {
        Value::Null => ResponseTemplate::new(status),
        Value::String(text) => ResponseTemplate::new(status)
            .insert_header("content-type", "text/plain; charset=utf-8")
            .set_body_string(text.clone()),
        other => ResponseTemplate::new(status).set_body_json(other.clone()),
    }
}

/// Plain-text error like Zipkin sends.
pub fn text(status: u16, body: &str) -> ResponseTemplate {
    ResponseTemplate::new(status)
        .insert_header("content-type", "text/*")
        .set_body_string(body.to_owned())
}

/// Fast deterministic retries.
pub fn fast_retry() -> RetryPolicy {
    RetryPolicy {
        initial_backoff: Duration::from_millis(5),
        max_backoff: Duration::from_millis(20),
        ..RetryPolicy::default()
    }
    .with_random(RandomSource::fixed(0.0))
}

/// Config builder pointing at the mock server.
pub fn config_builder(server: &MockServer) -> sdk::ZipkinConfigBuilder {
    sdk::ZipkinConfig::builder()
        .url(server.uri())
        .retry(fast_retry())
}

/// Client pointing at the mock server.
pub fn client(server: &MockServer) -> sdk::ZipkinClient {
    sdk::ZipkinClient::new(config_builder(server).build().unwrap()).unwrap()
}

/// A local port with nothing listening.
pub fn closed_port() -> u16 {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
    let port = listener.local_addr().expect("addr").port();
    drop(listener);
    port
}
