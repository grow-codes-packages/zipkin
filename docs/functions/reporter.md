# Span reporter

`ZipkinReporter` sends spans to Zipkin **without OpenTelemetry**: `POST /api/v2/spans` with a
JSON array of [v2 spans](https://github.com/openzipkin/zipkin-api/blob/master/zipkin2-api.yaml)
(`202 Accepted`). Use it for apps that build spans themselves, for importers, or in tests.
OpenTelemetry users get the same wire format from [`initTracing`](tracing-setup.md).

## The span model

`ZipkinSpan { traceId, parentId?, id, kind?, name?, timestamp?, duration?, localEndpoint?,
remoteEndpoint?, annotations?, tags?, debug?, shared? }` with `Endpoint { serviceName?, ipv4?,
ipv6?, port? }` and `Annotation { timestamp, value }`. Field names on the wire are exact.

| Rule | Behaviour |
| - | - |
| Time units | `timestamp` and annotation `timestamp`: epoch **microseconds**; `duration`: microseconds |
| IDs | `traceId` 16 or 32 hex characters, `id` / `parentId` 16; lower-cased locally (Zipkin rejects upper case with 400) |
| `kind` | `CLIENT`, `SERVER`, `PRODUCER`, `CONSUMER` (any case in, upper case out) or absent (internal) |
| Tags | every value becomes a string (`true` → `"true"`, `7` → `"7"`, arrays/objects → JSON); Zipkin rejects the whole batch for a boolean tag |
| Names | Zipkin lower-cases service and span names on ingest (`Send Order` → `send order`) |
| Validation | an invalid span throws `VALIDATION` before anything is sent |

## report

Sends spans. An empty list sends nothing; more than `maxBatchSize` spans (default 512) go in
several POSTs, in order. Ingest is idempotent (Zipkin merges spans by id), so 5xx, 429 and network
errors are retried; 400 and 413 are permanent `VALIDATION` errors. With in-memory storage the spans
are queryable as soon as the call returns; persistent backends may lag (use
[`waitForTrace`](query.md#waitfortrace)).

=== "TypeScript"

    ```ts
    const now = Date.now() * 1000;
    await client.reporter.report([
      {
        traceId: "5af7183fb1d4cf5f0a6665df1d0a9b8c",
        id: "0000000000000001",
        kind: "SERVER",
        name: "GET /orders",
        timestamp: now,
        duration: 120_000,
        localEndpoint: { serviceName: "frontend" },
        annotations: [{ timestamp: now + 1_000, value: "cache.miss" }],
        tags: { "http.method": "GET", cached: true }, // → "true"
      },
    ]);
    ```

=== "Rust"

    ```rust
    use grow_zipkin::{ZipkinEndpoint, ZipkinKind, ZipkinSpan};

    client
        .reporter()
        .report(&[ZipkinSpan::new("5af7183fb1d4cf5f0a6665df1d0a9b8c", "0000000000000001")
            .with_kind(ZipkinKind::Server)
            .with_name("GET /orders")
            .with_local_service("frontend")
            .with_remote_endpoint(ZipkinEndpoint::service("db"))
            .with_timing(now_micros, 120_000)
            .with_tag("http.method", "GET")])
        .await?;
    ```

=== "Go"

    ```go
    now := time.Now().UnixMicro()
    err := client.Reporter().Report(ctx, []zipkin.ZipkinSpan{{
        TraceID: "5af7183fb1d4cf5f0a6665df1d0a9b8c", ID: "0000000000000001",
        Kind: zipkin.KindServer, Name: "GET /orders", Timestamp: now, Duration: 120_000,
        LocalEndpoint: &zipkin.Endpoint{ServiceName: "frontend"},
        Annotations:   []zipkin.Annotation{{Timestamp: now + 1000, Value: "cache.miss"}},
        Tags:          map[string]string{"http.method": "GET"},
    }})
    ```

=== "Python"

    ```python
    import grow_zipkin as sdk

    client.reporter.report([
        sdk.ZipkinSpan(
            trace_id="5af7183fb1d4cf5f0a6665df1d0a9b8c",
            id="0000000000000001",
            kind="SERVER",
            name="GET /orders",
            timestamp=now_us,
            duration=120_000,
            local_endpoint=sdk.ZipkinEndpoint("frontend"),
            tags={"http.method": "GET", "http.status_code": 200},  # → "200"
        ),
        # the wire form (camelCase keys) is accepted too
        {"traceId": "5af7183fb1d4cf5f0a6665df1d0a9b8c", "id": "0000000000000002",
         "parentId": "0000000000000001", "name": "select", "kind": "CLIENT"},
    ])
    ```

=== "Java"

    ```java
    Instant now = Instant.now();
    client.reporter().report(List.of(
        ZipkinSpan.builder("5af7183fb1d4cf5f0a6665df1d0a9b8c", "0000000000000001")
            .kind(ZipkinKind.SERVER)
            .name("GET /orders")
            .localServiceName("frontend")
            .timestamp(now)
            .duration(Duration.ofMillis(120))
            .annotation(now.toEpochMilli() * 1000 + 1000, "cache.miss")
            .tag("http.method", "GET")
            .tag("http.status_code", 200)
            .build()));
    ```

## Standalone reporter and options

A reporter built from a config owns its connections (close it). Options: `compression`
(`gzip` sends `Content-Encoding: gzip`; default `none`), `maxBatchSize` (default 512) and
`endpoint` (default the config's `endpoint`, i.e. `GROW_ZIPKIN_ENDPOINT` >
`OTEL_EXPORTER_ZIPKIN_ENDPOINT` > `<url>/api/v2/spans`).

=== "TypeScript"

    ```ts
    import { ZipkinReporter } from "@grow-codes/zipkin";

    const reporter = ZipkinReporter.fromEnv({}, { compression: "gzip", maxBatchSize: 100 });
    try {
      await reporter.report(spans);
    } finally {
      await reporter.close();
    }
    ```

=== "Rust"

    ```rust
    use grow_zipkin::{tracing::Compression, ZipkinReporter, ZipkinReporterOptions};

    let reporter = ZipkinReporter::from_env(
        ZipkinReporterOptions::default().compression(Compression::Gzip).max_batch_size(100),
    )?;
    reporter.report(&spans).await?;
    reporter.close().await;
    ```

=== "Go"

    ```go
    reporter, err := zipkin.NewReporterFromEnv(zipkin.ReporterOptions{Compression: zipkin.CompressionGzip, MaxBatchSize: 100})
    if err != nil {
        return err
    }
    defer reporter.Close()
    err = reporter.Report(ctx, spans)
    ```

=== "Python"

    ```python
    from grow_zipkin import ZipkinConfig, ZipkinReporter

    with ZipkinReporter(ZipkinConfig.from_env(), compression="gzip", max_batch_size=100) as reporter:
        reporter.report(spans)
    # async code: AsyncZipkinReporter (await reporter.report(spans))
    ```

=== "Java"

    ```java
    try (ZipkinReporter reporter = ZipkinReporter.create(ZipkinConfig.fromEnv(),
        ZipkinReporter.Options.defaults().compression(TracingConfig.Compression.GZIP).maxBatchSize(100))) {
        reporter.report(spans); // reportAsync(spans) returns a CompletableFuture
    }
    ```
