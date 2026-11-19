# Tracing setup

`initTracing` starts the official OpenTelemetry SDK of each language and exports to Zipkin with the
package's **own `ZipkinSpanExporter`** — an implementation of the SDK's `SpanExporter` interface
that sends Zipkin v2 JSON through the package's `ZipkinReporter` (headers, TLS, retries, hooks,
redaction, gzip). It does not depend on the OpenTelemetry Zipkin exporters, which are deprecated
(Go), frozen (Rust 0.33) or no longer published (Java after 1.64); the mapping is the same in all
five languages.

The shared span helpers (`withSpan`, `traced`, `currentSpan`, `currentTraceId`, `currentSpanId`,
`addEvent`, `recordException`, `setError`, `inject`, `extract`, `traceFields`) and the framework
adapters are described in [Tracing](../tracing.md).

## initTracing

=== "TypeScript"

    ```ts
    import { initTracing, withSpan } from "@grow-codes/zipkin";

    const tracing = initTracing({ serviceName: "checkout", serviceVersion: "1.4.0" });
    await withSpan("place-order", async () => { /* … */ });
    await tracing.shutdown(); // flushes
    ```

=== "Rust"

    ```rust
    use grow_zipkin::tracing::TracingConfig;

    let tracing = grow_zipkin::init_tracing(TracingConfig::new("checkout"))?;
    // …
    tracing.shutdown().await?;
    ```

=== "Go"

    ```go
    h, err := zipkin.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "checkout", ServiceVersion: "1.4.0"})
    if err != nil {
        return err
    }
    defer h.Shutdown(context.Background())
    ```

=== "Python"

    ```python
    from grow_zipkin import init_tracing, with_span

    handle = init_tracing(service_name="checkout", service_version="1.4.0")
    with with_span("place-order"):
        ...
    handle.shutdown()
    ```

=== "Java"

    ```java
    try (ZipkinTracingHandle tracing = ZipkinTracing.initTracing(
            TracingConfig.builder("checkout").serviceVersion("1.4.0").build())) {
        Spans.withSpan("place-order", () -> { /* … */ });
    }
    ```

`client.initTracing(config)` does the same with the client's endpoint and connection settings and
is shut down by `client.close()` (see [Client](client.md#inittracing)).

### Defaults and environment

Per field: explicit value > environment > Zipkin default.

| Setting | Environment | Default |
| - | - | - |
| `protocol` | `GROW_ZIPKIN_PROTOCOL` (`native`, `grpc`, `http/protobuf`) | `native` (Zipkin v2 JSON) |
| `endpoint` (native) | `GROW_ZIPKIN_ENDPOINT`, `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (verbatim), `GROW_ZIPKIN_URL` / `ZIPKIN_URL` + `/api/v2/spans` | `http://127.0.0.1:9411/api/v2/spans` |
| `endpoint` (OTLP) | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (verbatim), `OTEL_EXPORTER_OTLP_ENDPOINT` (`http/protobuf` appends `/v1/traces`) | none — required |
| `compression` | `GROW_ZIPKIN_COMPRESSION` (`gzip`, `none`) | `gzip` |
| `batch.exportTimeout` | `OTEL_EXPORTER_ZIPKIN_TIMEOUT` (ms or `10s`) | 30 s |
| `serviceName` | `OTEL_SERVICE_NAME`, `service.name` in `OTEL_RESOURCE_ATTRIBUTES` | required |
| `resourceAttributes` | `OTEL_RESOURCE_ATTRIBUTES` (merged; explicit keys win) | none |
| `sampler`, `samplerRatio` | `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG` | `parentbased_always_on`, 1.0 |
| `propagators` | `OTEL_PROPAGATORS` | `tracecontext, baggage, b3` |
| `headers` | — (code only) | none |

### Propagation

The default propagators are W3C `tracecontext` + `baggage` and **B3 single header** (`b3`). Add
`b3multi` to also inject the `X-B3-*` headers; every B3 propagator extracts both formats. The
composite applies B3 first and W3C last, so when an incoming request carries both and they
disagree, **W3C wins**. The order is the same in every language.

=== "TypeScript"

    ```ts
    initTracing({ serviceName: "checkout", propagators: ["tracecontext", "baggage", "b3", "b3multi"] });
    ```

=== "Rust"

    ```rust
    use grow_zipkin::tracing::PropagatorKind;

    let mut config = TracingConfig::new("checkout");
    config.propagators = vec![PropagatorKind::TraceContext, PropagatorKind::Baggage, PropagatorKind::B3, PropagatorKind::B3Multi];
    let tracing = grow_zipkin::init_tracing(config)?;
    ```

=== "Go"

    ```go
    h, err := zipkin.InitTracing(ctx, zipkin.TracingConfig{
        ServiceName: "checkout",
        Propagators: []string{zipkin.PropagatorTraceContext, zipkin.PropagatorBaggage, zipkin.PropagatorB3, zipkin.PropagatorB3Multi},
    })
    ```

=== "Python"

    ```python
    init_tracing(service_name="checkout", propagators=["tracecontext", "baggage", "b3", "b3multi"])
    ```

=== "Java"

    ```java
    ZipkinTracing.initTracing(TracingConfig.builder("checkout")
        .propagators(List.of("tracecontext", "baggage", "b3", "b3multi")).build());
    ```

### OTLP through an OpenTelemetry Collector

Stock Zipkin has no OTLP receiver (`POST /v1/traces` → 404). `protocol: grpc` or
`http/protobuf` uses the official OTLP exporter and needs an
[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) with a `zipkin` exporter in
front of Zipkin; pointing it at `/api/v2/spans` is a `CONFIG` error. In Rust and Java pick OTLP
with the options' `protocol` (see [Compatibility](../compatibility.md#language-differences)).

=== "TypeScript"

    ```ts
    initTracing({ serviceName: "checkout", protocol: "grpc", endpoint: "http://otel-collector:4317" });
    ```

=== "Rust"

    ```rust
    use grow_zipkin::{tracing::Protocol, ZipkinTracingOptions};

    let mut config = TracingConfig::new("checkout");
    config.endpoint = Some("http://otel-collector:4317".into());
    let tracing = grow_zipkin::init_tracing_with_options(config, ZipkinTracingOptions::new().protocol(Protocol::Grpc))?;
    ```

=== "Go"

    ```go
    h, err := zipkin.InitTracing(ctx, zipkin.TracingConfig{
        ServiceName: "checkout", Protocol: zipkin.ProtocolGRPC, Endpoint: "http://otel-collector:4317",
    })
    ```

=== "Python"

    ```python
    init_tracing(service_name="checkout", protocol="grpc", endpoint="http://otel-collector:4317")
    ```

=== "Java"

    ```java
    ZipkinTracing.initTracing(
        TracingConfig.builder("checkout").endpoint("http://otel-collector:4317").build(),
        ZipkinTracing.Options.builder().protocol(TracingConfig.Protocol.GRPC));
    ```

## ZipkinSpanExporter

Use the exporter directly when you build the tracer provider yourself. With a reporter it sends
through it (and does not close it); otherwise it owns a reporter built from a config (default
`fromEnv`, gzip, 512 spans per POST). A failed batch is reported to the span processor and to
`onError`, then dropped.

=== "TypeScript"

    ```ts
    import { BatchSpanProcessor, TracerProvider } from "@opentelemetry/sdk-trace";
    import { ZipkinSpanExporter } from "@grow-codes/zipkin";

    const exporter = new ZipkinSpanExporter({ reporter: client.reporter });
    const provider = new TracerProvider({ spanProcessors: [new BatchSpanProcessor({ exporter })] });
    ```

=== "Rust"

    ```rust
    use grow_zipkin::ZipkinSpanExporter;

    let exporter = ZipkinSpanExporter::with_reporter(client.reporter());
    let provider = opentelemetry_sdk::trace::SdkTracerProvider::builder()
        .with_batch_exporter(exporter)
        .build();
    ```

=== "Go"

    ```go
    exporter := zipkin.NewSpanExporter(client.Reporter())
    provider := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exporter))
    ```

=== "Python"

    ```python
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
    from grow_zipkin import ZipkinSpanExporter

    provider = TracerProvider()
    provider.add_span_processor(BatchSpanProcessor(ZipkinSpanExporter(reporter=client.reporter)))
    ```

=== "Java"

    ```java
    SdkTracerProvider provider = SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(ZipkinSpanExporter.create(client.reporter())).build())
        .build();
    ```

### Span mapping (`toZipkinSpan`)

Identical in every language (based on the
[OpenTelemetry Zipkin exporter specification](https://opentelemetry.io/docs/specs/otel/trace/sdk_exporters/zipkin/)):

| Zipkin | OpenTelemetry |
| - | - |
| `traceId`, `id` | span context ids (lower-case hex, 32 / 16) |
| `parentId` | parent span id when valid, else omitted |
| `kind` | SERVER / CLIENT / PRODUCER / CONSUMER 1:1; INTERNAL → omitted |
| `name` | span name (the server lower-cases it) |
| `timestamp` | start time, epoch µs (truncated) |
| `duration` | end − start in µs, rounded, at least 1 |
| `localEndpoint.serviceName` | resource `service.name`, else `unknown_service` |
| `remoteEndpoint` | CLIENT / PRODUCER only: `{serviceName}` = first non-empty string of `peer.service`, `server.address`, `net.peer.name`, `network.peer.address`; omitted when none is set |
| `tags` | span attributes (strings as is, numbers and booleans as text, arrays as JSON); then `otel.scope.name` / `otel.scope.version`; `otel.dropped_attributes_count` / `otel.dropped_events_count` when > 0; status ERROR → `otel.status_code=ERROR` + `error=<description or "">`, OK → `otel.status_code=OK` |
| `annotations` | events: `timestamp` = event time µs, `value` = the event name, or the JSON text `{"<name>":{…attributes}}` (values keep their JSON type) when the event has attributes |
| `debug`, `shared` | never set |

Links, trace state and resource attributes other than `service.name` are not mapped. Reading a
trace back (`getTrace`) reverses the event encoding into span events.
