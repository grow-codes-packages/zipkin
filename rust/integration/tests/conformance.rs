// Created by sdk-kit 0.1.0; owned by this repository.
//! Integration suite against the real service (Docker): client round trips and the
//! conformance scenarios for `core` and `tracing`.
//!
//! Capabilities: core `version` (GET /info); tracing `query` + `services`. Zipkin's in-memory
//! storage makes spans queryable as soon as POST /api/v2/spans returns, so no warm-up is
//! needed; service and span names are lower-case because Zipkin lower-cases them on ingest.

use std::sync::Arc;
use std::time::Duration;

use grow_zipkin as sdk;
use grow_zipkin_integration as support;
use sdk::testing::tracing::{TracingHandler, TracingTarget};
use sdk::testing::{ConformanceRunner, CoreHandler};
use sdk::tracing::{TraceQueryClient, TracingConfig};
use sdk::ErrorCode;
use support::{conformance_dir, load_bootstrap_env, run_tag};

use sdk::blocking::ZipkinClient as BlockingClient;

use sdk::ZipkinClient as Client;
use sdk::ZipkinConfig as Config;

fn client() -> Client {
    load_bootstrap_env();
    Client::new(Config::from_env().expect("config from env")).expect("client")
}

#[tokio::test]
async fn client_round_trip() {
    let c = client();
    let health = c.health().await.expect("health");
    assert!(health.healthy, "service not healthy: {health:?}");
    c.ping().await.expect("ping");
    assert_eq!(c.server_version().await.expect("version"), "3.6.1");
    // unreachable services report unhealthy instead of failing
    let down = Client::new(
        Config::builder()
            .url("http://127.0.0.1:9")
            .max_retries(0)
            .build()
            .unwrap(),
    )
    .unwrap();
    assert!(!down.health().await.unwrap().healthy);
    assert_eq!(
        down.ping().await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
    c.close().await;
    c.close().await;
}

#[test]
fn blocking_client_basics() {
    load_bootstrap_env();
    let c = BlockingClient::from_env().expect("client");
    assert!(c.health().expect("health").healthy);
    c.ping().expect("ping");
    c.close();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn conformance() {
    load_bootstrap_env();
    // Unique service per run, so stale names never satisfy listServices.
    let service = format!("conformance-rust-{}", run_tag());
    let factory_service = service.clone();
    #[allow(unused_mut)]
    let mut runner = ConformanceRunner::new(conformance_dir())
        // Declare what the service supports; justify every skip in docs/compatibility.md.
        .capabilities(["version", "query", "services"])
        .var("serviceName", service.clone())
        .handler(CoreHandler::new(Client::from_env))
        .handler(TracingHandler::new(move || {
            let service = factory_service.clone();
            async move {
                let client = Client::from_env()?;
                let mut config = TracingConfig::new(service).with_set_global(false);
                config.batch.scheduled_delay = Duration::from_millis(100);
                let handle = sdk::init_tracing(config)?;
                let query: Arc<dyn TraceQueryClient> = Arc::new(client.query());
                Ok(TracingTarget {
                    handle,
                    query: Some(query),
                })
            }
        }));
    let report = runner.run().await.expect("scenarios load");
    println!("{report}");
    report.assert_success();
    assert_eq!(report.skipped(), 0, "{report}");
}
