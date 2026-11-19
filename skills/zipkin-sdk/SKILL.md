---
name: zipkin-sdk
description: Add and use the Grow Codes SDK for Zipkin (`zipkin`) in TypeScript, Rust, Go, Python or Java projects. Use when code must send traces to Zipkin or read them back - OpenTelemetry tracing with the native Zipkin v2 exporter and W3C + B3 propagation (initTracing), reporting Zipkin v2 spans without OpenTelemetry (ZipkinReporter), querying traces, services, span names, dependencies or autocomplete values (ZipkinQueryClient), installing @grow-codes/zipkin, the grow-zipkin crate or PyPI package, the Go module github.com/grow-codes-packages/zipkin/go or Maven codes.grow.packages:zipkin, configuring it with GROW_ZIPKIN_* / ZIPKIN_URL / OTEL_EXPORTER_ZIPKIN_ENDPOINT, handling ZipkinError codes, testing with the bundled fakes, or running the Docker integration suite of this repository.
---

# Zipkin SDK (Grow Codes)

One API for Zipkin in five languages (categories: tracing). Pick the package for the
project's language, configure it from the environment, and handle errors by their canonical code.

## 1. Add the package

| Language | Command | Import |
| - | - | - |
| TypeScript | `npm install @grow-codes/zipkin` | `import { ZipkinClient } from "@grow-codes/zipkin"` |
| Rust | `cargo add grow-zipkin` (add `tokio` with `macros,rt-multi-thread`) | `use grow_zipkin::ZipkinClient;` |
| Go | `go get github.com/grow-codes-packages/zipkin/go` | `zipkin "github.com/grow-codes-packages/zipkin/go"` |
| Python | `pip install grow-zipkin` (or `uv add grow-zipkin`) | `from grow_zipkin import ZipkinClient` |
| Java | Maven `codes.grow.packages:zipkin` (Gradle `implementation("codes.grow.packages:zipkin:<version>")`) | `import codes.grow.packages.zipkin.ZipkinClient;` |

Check the latest version in the README badges before pinning one.

## 2. Configure

Prefer environment configuration:

```sh
export GROW_ZIPKIN_URL=http://zipkin:9411                       # query API + base of the span endpoint
export GROW_ZIPKIN_ENDPOINT=http://zipkin:9411/api/v2/spans     # optional, span ingest URL
```

Precedence: explicit config value > `GROW_ZIPKIN_*` > vendor variables (`ZIPKIN_URL`, then the
base of `OTEL_EXPORTER_ZIPKIN_ENDPOINT`; for the span endpoint `OTEL_EXPORTER_ZIPKIN_ENDPOINT`
verbatim) > default (`http://127.0.0.1:9411`, `<url>/api/v2/spans`). Zipkin has **no
authentication and no token**; if a gateway protects it, pass its credentials as `headers` in code
(read them from your secret store; they are redacted everywhere). Tracing also reads
`GROW_ZIPKIN_PROTOCOL` (`native` default, `grpc`/`http/protobuf` only to an OpenTelemetry
Collector), `GROW_ZIPKIN_COMPRESSION`, `OTEL_EXPORTER_ZIPKIN_TIMEOUT`, `OTEL_SERVICE_NAME`,
`OTEL_RESOURCE_ATTRIBUTES`, `OTEL_TRACES_SAMPLER(_ARG)` and `OTEL_PROPAGATORS`.
Other variables: `…_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`, `…_TOTAL_TIMEOUT` (`10s`, `1500`),
`…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`, `…_PROXY`. Environment proxies
(`HTTPS_PROXY`) are ignored unless `proxy` is set.

## 3. Use

Create one client per process and close it on shutdown (TypeScript `await client.close()`, Rust
`client.close().await`, Go `defer client.Close()`, Python `with …`/`close()`, Java
try-with-resources).

- `health()` never throws for an unhealthy service; check `healthy`.
- `ping()` is a round trip through the query API (`GET /api/v2/services`); behind a gateway it
  also verifies the credentials (`AUTHENTICATION` error when they are wrong).
- All I/O is retried with jitter on 429/502/503/504 and connection errors; only idempotent calls
  are retried on 500.
- Errors carry `code` (`CONFIG`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CONFLICT`,
  `RATE_LIMITED`, `VALIDATION`, `UNAVAILABLE`, `TIMEOUT`, `UNSUPPORTED`, `CANCELLED`), `retryable`,
  `httpStatus` and `requestId`. Branch on the code, not on the message text. Go:
  `errors.Is(err, zipkin.ErrNotFound)`.

### Tracing

Call `initTracing({ serviceName })` (or `client.initTracing(...)`, which uses the client's
endpoint and headers and is shut down by `client.close()`) once at startup, wrap work in
`withSpan(name, fn)` or the `traced` helper, propagate context with `inject`/`extract`, and call
`shutdown()` on exit. Spans go to `/api/v2/spans` as Zipkin v2 JSON through the package's own
`ZipkinSpanExporter`. Propagators default to `tracecontext, baggage, b3` (B3 single header; add
`b3multi` for `X-B3-*`); W3C wins when both disagree.

<!-- service:skill-usage — the service adds its own functions and examples here. -->

### Zipkin functions

| Need | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Start tracing | `client.initTracing({ serviceName: "svc" })` | `client.init_tracing(TracingConfig::new("svc"))?` | `client.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "svc"})` | `client.init_tracing(service_name="svc")` | `client.initTracing(TracingConfig.builder("svc").build())` |
| Send spans without OTel | `await client.reporter.report([span])` | `client.reporter().report(&[span]).await?` | `client.Reporter().Report(ctx, spans)` | `client.reporter.report([span])` | `client.reporter().report(List.of(span))` |
| One trace (normalized / raw) | `query.getTrace(id)` / `getTraceSpans(id)` | `get_trace` / `get_trace_spans` | `GetTrace` / `GetTraceSpans` | `get_trace` / `get_trace_spans` | `getTrace` / `getTraceSpans` |
| Find traces | `query.getTraces({ serviceName, tags, lookbackMs, limit })` | `get_traces(&ZipkinTraceQuery::service(..))` | `GetTraces(ctx, zipkin.TracesQuery{..})` | `get_traces(service_name=.., tags=..)` | `getTraces(ZipkinTraceQuery.builder()..build())` |
| Summaries (last 1 h default) | `query.searchTraces({ service })` | `search_traces(&TraceSearchQuery::service(..))` | `SearchTraces(ctx, zipkin.TraceSearchQuery{..})` | `search_traces(TraceSearchQuery(service=..))` | `searchTraces(TraceSearchQuery.forService(..))` |
| Names | `listServices`, `listSpans`, `listRemoteServices` | `list_services`, … | `ListServices`, … | `list_services`, … | `listServices`, … |
| Dependencies | `query.getDependencies({ lookbackMs })` | `get_dependencies(ZipkinDependencyOptions::window(..))` | `GetDependenciesAt(ctx, end, lookback)` | `get_dependencies(lookback_s=..)` | `getDependencies(endTs, lookback)` |
| Wait in tests | `query.waitForTrace(id, { minSpans })` | `wait_for_trace(&id, opts)` | `WaitForTrace(ctx, id, opts)` | `wait_for_trace(id, min_spans=..)` | `waitForTrace(id)` |

Zipkin facts that matter in code: span `timestamp`/`duration` are **microseconds**, query `endTs`
/ `lookback` milliseconds (the SDK converts); ids are lower-case hex (the SDK lower-cases);
Zipkin lower-cases service and span names, so compare names in lower case; tag values are always
strings; `getDependencies` is empty on persistent storage unless the zipkin-dependencies job runs;
calls on a closed client fail with `CANCELLED`. Full reference: `docs/functions/*.md`, quirks and
language differences: `docs/compatibility.md`.

## 4. Test code that uses the SDK

Use the in-memory fakes from the testing module instead of a real Zipkin in unit tests:
TypeScript `@grow-codes/zipkin/testing`, Rust feature `testing`, Go package `testkit`, Python
`grow_zipkin.testing`, Java `codes.grow.packages.zipkin.testing`.

## 5. Work on this repository

Read `AGENTS.md` first. Short version: the five languages keep the same API; files with the
`Generated by sdk-kit` header are changed in sdk-kit, not here; `make lint coverage` must pass
(≥ 90 % lines and branches); `make test-docker LANGS="<lang>"` runs the integration suite against
`openzipkin/zipkin:3.6.1`.
