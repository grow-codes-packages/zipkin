# Grow Codes SDK for Zipkin

[![CI](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/grow-codes-packages/zipkin?sort=semver)](https://github.com/grow-codes-packages/zipkin/releases)
[![Docs](https://img.shields.io/badge/docs-grow--codes--packages.github.io-blue)](https://grow-codes-packages.github.io/zipkin/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [![npm](https://img.shields.io/npm/v/@grow-codes/zipkin)](https://www.npmjs.com/package/@grow-codes/zipkin) | [![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-typescript.json)](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml) |
| Rust | [![crates.io](https://img.shields.io/crates/v/grow-zipkin)](https://crates.io/crates/grow-zipkin) [![docs.rs](https://img.shields.io/docsrs/grow-zipkin)](https://docs.rs/grow-zipkin) | [![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-rust.json)](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml) |
| Go | [![Go Reference](https://pkg.go.dev/badge/github.com/grow-codes-packages/zipkin/go.svg)](https://pkg.go.dev/github.com/grow-codes-packages/zipkin/go) | [![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-go.json)](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml) |
| Python | [![PyPI](https://img.shields.io/pypi/v/grow-zipkin)](https://pypi.org/project/grow-zipkin/) | [![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-python.json)](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml) |
| Java | [![Maven Central](https://img.shields.io/maven-central/v/codes.grow.packages/zipkin)](https://central.sonatype.com/artifact/codes.grow.packages/zipkin) | [![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-java.json)](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml) |

Open-source client libraries for [Zipkin](#trademarks-and-disclaimer) in **TypeScript, Rust, Go,
Python and Java** — one API, five idiomatic packages, tested against a real Zipkin in Docker.

- **Tracing**: OpenTelemetry setup in one call with the package's **own Zipkin exporter** (v2 JSON
  to `/api/v2/spans`; no dependency on the deprecated OpenTelemetry Zipkin exporters), W3C + B3
  propagation (W3C wins on conflict), span helpers and framework middleware.
- **Span reporter**: send Zipkin v2 spans (`ZipkinSpan`, `Endpoint`, `Annotation`) without
  OpenTelemetry.
- **Query**: the whole Zipkin v2 query API — traces, search, `traceMany`, services, span names,
  remote services, dependencies, autocomplete — plus the category `TraceQueryClient` with one
  normalized trace model.
- **Typed client** with timeouts, retries with jitter, `Retry-After`, cancellation and one error
  model (`ZipkinError` with canonical codes) in every language.
- **Secrets stay secret**: gateway credentials in `headers` are redacted in `toString`, logs,
  hooks and error messages.

## Contents

- [Supported service version](#supported-service-version)
- [Install](#install)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Function overview](#function-overview)
- [Testing with Docker](#testing-with-docker)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)
- [Trademarks and disclaimer](#trademarks-and-disclaimer)
- [About Grow Codes](#about-grow-codes)

## Supported service version

Tested in CI against **`openzipkin/zipkin:3.6.1`** (the version pinned in the Grow Codes stack catalog). Other
versions with the same API usually work; see [Compatibility](#compatibility) and
[docs/compatibility.md](docs/compatibility.md).

## Install

| Language | Package | Install |
| - | - | - |
| TypeScript (Node.js ≥ 22.12) | `@grow-codes/zipkin` | `npm install @grow-codes/zipkin` |
| Rust (MSRV 1.88) | `grow-zipkin` | `cargo add grow-zipkin` |
| Go (1.26+) | `github.com/grow-codes-packages/zipkin/go` | `go get github.com/grow-codes-packages/zipkin/go` |
| Python (≥ 3.10) | `grow-zipkin` | `pip install grow-zipkin` |
| Java (17+) | `codes.grow.packages:zipkin` | see below |

Maven:

```xml
<dependency>
  <groupId>codes.grow.packages</groupId>
  <artifactId>zipkin</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("codes.grow.packages:zipkin:0.1.0")
```

## Quickstart

Every package reads its configuration from the environment (`GROW_ZIPKIN_URL`, `ZIPKIN_URL`,
`OTEL_EXPORTER_ZIPKIN_ENDPOINT`, …, see [Configuration](#configuration)) or from an explicit
config object. Stock Zipkin has no authentication; gateway credentials go in `headers`. Each
example starts tracing through the client, sends one span, reads the trace back and checks the
server.

```sh
docker run --rm -d -p 9411:9411 openzipkin/zipkin:3.6.1
export GROW_ZIPKIN_URL=http://127.0.0.1:9411
```

### TypeScript

```ts
import { ZipkinClient, currentTraceId, withSpan } from "@grow-codes/zipkin";

const client = ZipkinClient.fromEnv();
try {
  console.log("healthy:", (await client.health()).healthy, await client.serverVersion());
  const tracing = client.initTracing({ serviceName: "checkout" });
  const traceId = await withSpan("place-order", async () => currentTraceId());
  await tracing.forceFlush();
  const trace = await client.query.waitForTrace(traceId!);
  console.log(trace.spans.map((s) => `${s.serviceName}/${s.name}`));
} finally {
  await client.close(); // also shuts the tracing handle down
}
```

### Rust

```rust
use std::time::Duration;

use grow_zipkin::tracing::{current_trace_id, traced, TracingConfig, WaitForTraceOptions};
use grow_zipkin::ZipkinClient;

#[tokio::main]
async fn main() -> grow_zipkin::Result<()> {
    let client = ZipkinClient::from_env()?;
    println!("healthy: {} {}", client.health().await?.healthy, client.server_version().await?);
    let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
    let trace_id = traced("place-order", async { Ok::<_, std::io::Error>(current_trace_id()) })
        .await
        .ok()
        .flatten()
        .unwrap_or_default();
    tracing.force_flush(Some(Duration::from_secs(5))).await?;
    let trace = client.query().wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
    println!("{} span(s)", trace.spans.len());
    client.close().await;
    Ok(())
}
```

### Go

```go
package main

import (
	"context"
	"fmt"
	"log"

	zipkin "github.com/grow-codes-packages/zipkin/go"
)

func main() {
	ctx := context.Background()
	client, err := zipkin.NewClientFromEnv()
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close() // also shuts the tracing handle down

	h, err := client.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "checkout"})
	if err != nil {
		log.Fatal(err)
	}
	traceID, _ := zipkin.WithSpan(ctx, "place-order", func(ctx context.Context) (string, error) {
		id, _ := zipkin.CurrentTraceID(ctx)
		return id, nil
	})
	_ = h.ForceFlush(ctx)
	trace, err := client.Query().WaitForTrace(ctx, traceID, zipkin.WaitForTraceOptions{})
	if err != nil {
		log.Fatal(err)
	}
	health, _ := client.Health(ctx)
	fmt.Println(len(trace.Spans), "span(s), healthy:", health.Healthy)
}
```

### Python

```python
from grow_zipkin import ZipkinClient, current_trace_id, with_span

with ZipkinClient.from_env() as client:  # close() also shuts the tracing handle down
    print("healthy:", client.health().healthy, client.server_version())
    handle = client.init_tracing(service_name="checkout")
    with with_span("place-order"):
        trace_id = current_trace_id()
    handle.force_flush()
    trace = client.query.wait_for_trace(str(trace_id))
    print([f"{s.service_name}/{s.name}" for s in trace.spans])
```

Async code uses `AsyncZipkinClient` with the same methods (`async with`, `await client.health()`).

### Java

```java
import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.ZipkinTracingHandle;
import codes.grow.packages.zipkin.tracing.Spans;
import codes.grow.packages.zipkin.tracing.TracingConfig;

try (ZipkinClient client = ZipkinClient.fromEnv()) {
    System.out.println("healthy: " + client.health().healthy() + " " + client.serverVersion());
    ZipkinTracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
    String traceId = Spans.withSpan("place-order", () -> Spans.currentTraceId().orElseThrow());
    tracing.forceFlush();
    System.out.println(client.query().waitForTrace(traceId).spans());
} // close() also shuts the tracing handle down
```

More complete programs (reporter, every query function, hooks, async) live in
[examples/](examples/) — one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_ZIPKIN_*` variable > vendor variable > default.** A present but invalid value raises a
`ConfigError` that names the variable (never its value).

**Client** (`ZipkinConfig`, used by the client, the query client and the reporter):

| Setting | Environment variables (precedence order) | Default |
| - | - | - |
| `url` (query API and base of the span endpoint, port 9411) | `GROW_ZIPKIN_URL`, `ZIPKIN_URL`, base of `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (one trailing `/api/v2/spans` removed, a gateway prefix kept) | `http://127.0.0.1:9411` |
| `endpoint` (span ingest URL of the reporter and the native exporter) | `GROW_ZIPKIN_ENDPOINT`, `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (verbatim) | `<url>/api/v2/spans` |
| `headers` (gateway credentials, redacted everywhere) | — (code only; Zipkin has no authentication) | none |
| `connectTimeout` | `GROW_ZIPKIN_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_ZIPKIN_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_ZIPKIN_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_ZIPKIN_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_ZIPKIN_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_ZIPKIN_CLIENT_CERT` / `GROW_ZIPKIN_CLIENT_KEY` | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_ZIPKIN_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_ZIPKIN_PROXY` | none (environment proxies are ignored) |
| `userAgentSuffix`, `hooks`, `logger` | — | none |

**Tracing** (`initTracing`):

| Setting | Environment variables (precedence order) | Default |
| - | - | - |
| `protocol` | `GROW_ZIPKIN_PROTOCOL` (`native`, `grpc`, `http/protobuf`) | `native` (Zipkin v2 JSON) |
| `endpoint` (native) | `GROW_ZIPKIN_ENDPOINT`, `OTEL_EXPORTER_ZIPKIN_ENDPOINT`, `GROW_ZIPKIN_URL` / `ZIPKIN_URL` + `/api/v2/spans` | `http://127.0.0.1:9411/api/v2/spans` |
| `endpoint` (OTLP to an OpenTelemetry Collector) | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` | required |
| `compression` | `GROW_ZIPKIN_COMPRESSION` (`gzip`, `none`) | `gzip` |
| `batch.exportTimeout` | `OTEL_EXPORTER_ZIPKIN_TIMEOUT` | 30 s |
| `serviceName` | `OTEL_SERVICE_NAME`, `service.name` in `OTEL_RESOURCE_ATTRIBUTES` | required |
| `resourceAttributes` | `OTEL_RESOURCE_ATTRIBUTES` (merged; explicit keys win) | none |
| `sampler`, `samplerRatio` | `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG` | `parentbased_always_on`, 1.0 |
| `propagators` | `OTEL_PROPAGATORS` | `tracecontext,baggage,b3` (B3 single header; add `b3multi` for `X-B3-*`) |

Durations accept milliseconds (`1500`) or units (`500ms`, `10s`, `1m30s`); booleans accept
`true/false/1/0/yes/no/on/off`. Field names follow each language's convention
(`request_timeout` in Python and Rust, `RequestTimeout` in Go). The full reference is in the
[documentation](https://grow-codes-packages.github.io/zipkin/configuration/).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`getTraces` in
TypeScript and Java, `get_traces` in Python and Rust, `GetTraces` in Go). Every function has an
example in all five languages in the [documentation](https://grow-codes-packages.github.io/zipkin/functions/).

### Client — `ZipkinClient`

| Function | Description |
| - | - |
| `health()` | `GET /health`; `DOWN` (503) → `healthy: false`; never throws for an unhealthy or unreachable server |
| `ping()` | round trip through the query API and storage (`GET /api/v2/services`), returns the latency |
| `serverInfo()` / `serverVersion()` | `GET /info` → version and commit / `3.6.1` (`UNSUPPORTED` without one) |
| `query` / `reporter` / `createReporter(options)` | the query client and reporters on the client's connections |
| `initTracing(config)` | tracing to the client's endpoint with its settings; shut down by `close()` |
| `close()` | idempotent; later calls fail with `CANCELLED` |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.serverInfo()` | `client.server_info().await?` | `client.ServerInfo(ctx)` | `client.server_info()` | `client.serverInfo()` |

### Span reporter — `ZipkinReporter`

| Function | Description |
| - | - |
| `report(spans)` | `POST /api/v2/spans` (v2 JSON, optional gzip, 512 spans per POST); ids lower-cased, tags stringified, invalid spans rejected locally |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.reporter.report([span])` | `client.reporter().report(&[span]).await?` | `client.Reporter().Report(ctx, spans)` | `client.reporter.report([span])` | `client.reporter().report(List.of(span))` |

### Query — `ZipkinQueryClient` (`TraceQueryClient`)

| Function | Description |
| - | - |
| `getTraceSpans(traceId)` / `getTrace(traceId)` | one trace as stored / normalized; `null` when missing |
| `getTraces(query)` | traces by service, remote service, span name, annotation query, tags, duration, window, limit |
| `getTraceMany(traceIds)` | several traces (deduplicated, request order, chunks of 100) |
| `searchTraces(query)` | trace summaries (category search, last 1 h without `start`) |
| `listServices()`, `listSpans(service)` / `listOperations`, `listRemoteServices(service)` | names, sorted |
| `getDependencies(window)` | dependency links with `errorCount` |
| `autocompleteKeys()`, `autocompleteValues(key)` | the server's autocomplete allowlist and values |
| `waitForTrace(traceId, opts)` | poll until a trace is queryable (tests) |
| `buildAnnotationQuery(tags, annotations)` | `http.method=GET and error` |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.query.getTraces({ serviceName: "checkout", limit: 5 })` | `client.query().get_traces(&ZipkinTraceQuery::service("checkout").limit(5)).await?` | `client.Query().GetTraces(ctx, zipkin.TracesQuery{ServiceName: "checkout", Limit: 5})` | `client.query.get_traces(service_name="checkout", limit=5)` | `client.query().getTraces(ZipkinTraceQuery.builder().serviceName("checkout").limit(5).build())` |

### Tracing setup

| Function | Description |
| - | - |
| `initTracing(config)` | OpenTelemetry setup with the native Zipkin exporter (or OTLP to a Collector); handle with `tracer`, `forceFlush`, `shutdown` |
| `tracingConfigFromEnv(config)` | the effective config (explicit > environment > Zipkin defaults) |
| `ZipkinSpanExporter`, `toZipkinSpan(span)` | the OpenTelemetry `SpanExporter` and its span mapping |
| `withSpan`, `traced`, `currentSpan`, `currentTraceId`, `currentSpanId`, `addEvent`, `recordException`, `setError`, `inject`, `extract`, `traceFields` | shared span helpers and propagation |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `initTracing({ serviceName: "checkout" })` | `grow_zipkin::init_tracing(TracingConfig::new("checkout"))?` | `zipkin.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "checkout"})` | `init_tracing(service_name="checkout")` | `ZipkinTracing.initTracing(TracingConfig.builder("checkout").build())` |

<!-- /service:function-overview -->

## Testing with Docker

The integration suite runs every public function against a real Zipkin (`openzipkin/zipkin:3.6.1`,
in-memory storage) in Docker, plus the shared conformance scenarios (`core.health`,
`tracing.services`, `tracing.tree`).
Each language package is packed and installed into a separate consumer project first, so the tests
use the package exactly as you would.

```sh
make test-docker                     # all five languages
make test-docker LANGS="go python"   # a subset
```

No host ports are published and every run uses its own Compose project, so runs never collide; the
stack is always removed afterwards (`docker compose down -v`). Unit tests need no Docker:
`make test` or `make coverage`.

## Compatibility

| | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Runtime | Node.js ≥ 22.12, ESM + CJS | stable, MSRV 1.88 | 1.26, 1.27 | 3.10 – 3.14 | 17, 21, 25 |
| Async | `Promise` + `AbortSignal` | `async` (tokio), `blocking` feature | `context.Context` | sync + `asyncio` | sync + `CompletableFuture` |
| Zipkin | `openzipkin/zipkin:3.6.1` | `openzipkin/zipkin:3.6.1` | `openzipkin/zipkin:3.6.1` | `openzipkin/zipkin:3.6.1` | `openzipkin/zipkin:3.6.1` |

Behaviour the stock Zipkin container cannot produce (gateway 401/403, 429, `DOWN` health, 413,
TLS, OTLP export) is covered by unit tests; it is listed with the service quirks the SDK handles
and the few language differences in [docs/compatibility.md](docs/compatibility.md).

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[AGENTS.md](AGENTS.md) first. All five languages keep the same API; a change lands in all of them
together. Parts of this repository are generated by
[sdk-kit](https://github.com/grow-codes-packages/sdk-kit) and changed there.

## Security

Please report vulnerabilities privately to [opensource@grow.codes](mailto:opensource@grow.codes).
See [SECURITY.md](SECURITY.md).

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.

## Trademarks and disclaimer

This is an independent, community-maintained project. It is **not affiliated with, endorsed or
sponsored by its owner (The OpenZipkin authors)**. Zipkin is a trademark of The OpenZipkin authors; it is used here only to name the
service this SDK connects to. The Zipkin server is licensed separately by its owner
(Apache-2.0).

The software is provided "AS IS", without warranties or conditions of any kind. Grow Codes is not
responsible for any damage, data loss, security incident, outage or cost that results from using it.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## About Grow Codes

This SDK is built and maintained by [Grow Codes](https://grow.codes). Grow Codes publishes the
client libraries it builds as open source in the
[grow-codes-packages](https://github.com/grow-codes-packages) organisation, generated from one
shared kit so that every service and every language behaves the same way. Learn more at
[grow.codes](https://grow.codes).
