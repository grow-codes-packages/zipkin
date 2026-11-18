// Created by sdk-kit 0.1.0; owned by this repository.
//! Integration suite against the real service (Docker): client round trips and the
//! conformance scenarios for `core` and this service's categories.

use grow_zipkin as sdk;
use grow_zipkin_integration as support;
use sdk::testing::{ConformanceRunner, CoreHandler};
use sdk::ErrorCode;
use support::{conformance_dir, load_bootstrap_env};

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
    c.ping().await.expect("authenticated ping");
    match c.server_version().await {
        Ok(v) => assert!(!v.is_empty()),
        Err(e) => assert_eq!(e.code(), ErrorCode::Unsupported, "{e}"),
    }
    c.close().await;
    c.close().await;
}

#[test]
fn blocking_round_trip() {
    load_bootstrap_env();
    let c = BlockingClient::from_env().expect("client");
    assert!(c.health().expect("health").healthy);
    c.ping().expect("ping");
    c.close();
}

#[tokio::test]
async fn conformance() {
    load_bootstrap_env();
    #[allow(unused_mut)]
    let mut runner = ConformanceRunner::new(conformance_dir())
        // Declare what the service supports; justify every skip in docs/compatibility.md.
        .capabilities(["version", "write", "delete", "versions", "query"])
        .handler(CoreHandler::new(Client::from_env));
    let report = runner.run().await.expect("scenarios load");
    println!("{report}");
    report.assert_success();
}
