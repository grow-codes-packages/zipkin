// Created by sdk-kit 0.1.0; owned by this repository.
//! The Zipkin Rust SDK end to end: OpenTelemetry tracing to Zipkin (`init_tracing`), direct
//! span reporting (`ZipkinReporter`), reading traces back (`ZipkinQueryClient`), hooks and
//! error handling.
//!
//! ```sh
//! docker run --rm -p 9411:9411 openzipkin/zipkin:3.6.1
//! GROW_ZIPKIN_URL=http://127.0.0.1:9411 cargo run
//! ```

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_zipkin as sdk;
use sdk::core::{ErrorInfo, Hooks};
use sdk::tracing::{
    current_trace_id, traced, TraceSearchQuery, TracingConfig, WaitForTraceOptions,
};
use sdk::{ErrorCode, ZipkinClient, ZipkinConfig, ZipkinKind, ZipkinSpan, ZipkinTraceQuery};

/// Prints every retry.
struct RetryLogger;

impl Hooks for RetryLogger {
    fn on_error(&self, info: &ErrorInfo) {
        if info.will_retry {
            eprintln!(
                "retrying {} {} (attempt {})",
                info.method, info.url, info.attempt
            );
        }
    }
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("error [{}]: {err}", err.code());
        std::process::exit(1);
    }
}

fn now_micros() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_micros() as u64)
        .unwrap_or(0)
}

async fn run() -> sdk::Result<()> {
    // explicit > GROW_ZIPKIN_* > ZIPKIN_URL / OTEL_EXPORTER_ZIPKIN_ENDPOINT > defaults.
    // Gateway credentials go in headers and print as [REDACTED].
    let config = ZipkinConfig::builder()
        .hooks(Arc::new(RetryLogger))
        .build_from_env()?;
    println!("config: {config:?}");
    let client = ZipkinClient::new(config)?;

    let health = client.health().await?; // never fails for an unreachable service
    println!("health: {} ({})", health.healthy, health.status);
    match client.server_version().await {
        Ok(version) => println!("zipkin {version}"),
        Err(e) if e.code() == ErrorCode::Unsupported => println!("version: not exposed"),
        Err(e) => return Err(e),
    }

    // 1. OpenTelemetry: spans go to /api/v2/spans as Zipkin v2 JSON; W3C + B3 propagation.
    let tracing = client.init_tracing(TracingConfig::new("grow-example-checkout"))?;
    let trace_id = traced("checkout", async {
        traced("charge-card", async { Ok::<_, std::io::Error>(()) }).await?;
        Ok::<_, std::io::Error>(current_trace_id())
    })
    .await
    .ok()
    .flatten()
    .unwrap_or_default();
    tracing.force_flush(Some(Duration::from_secs(5))).await?;
    let query = client.query();
    let trace = query
        .wait_for_trace(&trace_id, WaitForTraceOptions::default().min_spans(2))
        .await?;
    println!("otel trace {trace_id}: {} spans", trace.spans.len());

    // 2. Report spans directly (no OpenTelemetry), then read them back.
    let id = format!("{:032x}", now_micros());
    let start = now_micros() - 20_000;
    client
        .reporter()
        .report(&[
            ZipkinSpan::new(&id, "0000000000000001")
                .with_kind(ZipkinKind::Server)
                .with_name("get /orders")
                .with_local_service("grow-example-frontend")
                .with_timing(start, 15_000)
                .with_tag("http.method", "GET"),
            ZipkinSpan::new(&id, "0000000000000002")
                .with_parent_id("0000000000000001")
                .with_kind(ZipkinKind::Client)
                .with_name("select orders")
                .with_local_service("grow-example-frontend")
                .with_remote_endpoint(sdk::ZipkinEndpoint::service("grow-example-db"))
                .with_timing(start + 1_000, 10_000),
        ])
        .await?;
    let spans = query.get_trace_spans(&id).await?.unwrap_or_default();
    println!("reported trace {id}: {} spans", spans.len());

    let traces = query
        .get_traces(&ZipkinTraceQuery::service("grow-example-frontend").tag("http.method", "GET"))
        .await?;
    println!("getTraces: {} traces", traces.len());
    let summaries = query
        .search_traces(&TraceSearchQuery::service("grow-example-frontend"))
        .await?;
    println!("searchTraces (last hour): {} traces", summaries.len());
    println!("services: {:?}", query.list_services().await?);
    println!(
        "spans of grow-example-frontend: {:?}",
        query.list_spans("grow-example-frontend").await?
    );
    let deps = query
        .get_dependencies(sdk::ZipkinDependencyOptions::window(
            SystemTime::now(),
            Duration::from_secs(3600),
        ))
        .await?;
    for d in deps {
        println!("{} -> {}: {} calls", d.parent, d.child, d.call_count);
    }

    client.close().await; // also shuts the tracing handle down
    Ok(())
}
