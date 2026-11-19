# grow-zipkin (Rust)

Rust SDK for [Zipkin](https://zipkin.io) by [Grow Codes](https://grow.codes): OpenTelemetry
tracing to Zipkin, direct span reporting and the Zipkin v2 query API. Async on tokio, with an
optional blocking client. Tested against `openzipkin/zipkin:3.6.1`.

```toml
[dependencies]
grow-zipkin = "0.1.0"
# blocking client:   features = ["blocking"]
# test fakes/runner: features = ["testing"] (dev-dependency)
# instrumentation:   features = ["tower", "reqwest-middleware"]
```

```rust,no_run
use grow_zipkin::tracing::{traced, TracingConfig};
use grow_zipkin::{ZipkinClient, ZipkinConfig, ZipkinKind, ZipkinSpan, ZipkinTraceQuery};

#[tokio::main]
async fn main() -> grow_zipkin::Result<()> {
    // explicit > GROW_ZIPKIN_* > ZIPKIN_URL / OTEL_EXPORTER_ZIPKIN_ENDPOINT > defaults
    let client = ZipkinClient::new(ZipkinConfig::from_env()?)?;
    println!("{:?}", client.health().await?);

    // OpenTelemetry → Zipkin v2 JSON (POST /api/v2/spans), W3C + B3 propagation
    let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
    traced("charge-card", async { Ok::<_, std::io::Error>(()) }).await.ok();
    tracing.force_flush(None).await?;

    // Report spans without OpenTelemetry, then read them back
    let span = ZipkinSpan::new("5af7183fb1d4cf5f0a6665df1d0a9b8c", "0000000000000001")
        .with_kind(ZipkinKind::Server)
        .with_name("get /orders")
        .with_local_service("checkout")
        .with_timing(1_795_034_515_005_000, 1_000);
    client.reporter().report(&[span]).await?;
    let traces = client
        .query()
        .get_traces(&ZipkinTraceQuery::service("checkout").limit(5))
        .await?;
    println!("{} traces", traces.len());

    client.close().await; // also shuts the tracing handle down
    Ok(())
}
```

- `ZipkinSpanExporter` is this crate's own OpenTelemetry `SpanExporter` (the
  `opentelemetry-zipkin` crate is discontinued); the span mapping is identical in all five
  languages of this SDK (see `to_zipkin_span`).
- Errors: one `Error` enum with `code()`, `http_status()`, `request_id()`, `is_retryable()`,
  `retry_after()`; messages never contain credentials (gateway headers are redacted).
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation (drop the future), request hooks, `tracing` debug events.
- MSRV 1.88. Licence Apache-2.0. Documentation: https://grow-codes-packages.github.io/zipkin/

Development: `make deps build lint test coverage license pack docs` (see `Makefile`).
