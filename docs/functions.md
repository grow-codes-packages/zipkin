# Functions

The Zipkin API of this SDK. Every function exists in all five languages with the same
behaviour, named per language convention (`getTraces` in TypeScript and Java, `get_traces` in
Python and Rust, `GetTraces` in Go). Each page shows every function with an example per
language.

| Page | What |
| - | - |
| [Client and status](functions/client.md) | `ZipkinClient`: `health`, `ping`, `serverInfo`, `serverVersion`, `query`, `reporter`, `createReporter`, `initTracing`, `close` |
| [Span reporter](functions/reporter.md) | `ZipkinReporter.report(spans)` — Zipkin v2 JSON without OpenTelemetry; the `ZipkinSpan` model |
| [Trace queries](functions/query.md) | `ZipkinQueryClient`: the v2 query API and the category `TraceQueryClient` |
| [Tracing setup](functions/tracing-setup.md) | `initTracing` with the package's own `ZipkinSpanExporter`, B3 + W3C propagation, OTLP via a Collector |

## Overview

| Function | Zipkin endpoint | Integration test |
| - | - | - |
| `health()` | `GET /health` | `core.health` + service suite |
| `ping()` | `GET /api/v2/services` | `core.health` + service suite |
| `serverInfo()` / `serverVersion()` | `GET /info` | service suite |
| `reporter.report(spans)` | `POST /api/v2/spans` | service suite (read back with `getTraceSpans`) |
| `createReporter(options)` | — (reporter with gzip / batch size / endpoint) | service suite |
| `query.getTraceSpans(traceId)` | `GET /api/v2/trace/{id}` | service suite |
| `query.getTrace(traceId)` | `GET /api/v2/trace/{id}` (normalized `Trace`) | service suite, `tracing.tree` |
| `query.getTraces(query)` | `GET /api/v2/traces` | service suite |
| `query.getTraceMany(ids)` | `GET /api/v2/traceMany` (chunks of 100) | service suite |
| `query.searchTraces(query)` | `GET /api/v2/traces` (summaries, last 1 h by default) | service suite |
| `query.listServices()` | `GET /api/v2/services` | service suite, `tracing.services` |
| `query.listSpans(service)` / `listOperations` | `GET /api/v2/spans?serviceName=` | service suite |
| `query.listRemoteServices(service)` | `GET /api/v2/remoteServices?serviceName=` | service suite |
| `query.getDependencies(window)` | `GET /api/v2/dependencies` | service suite |
| `query.autocompleteKeys()` | `GET /api/v2/autocompleteKeys` | service suite |
| `query.autocompleteValues(key)` | `GET /api/v2/autocompleteValues?key=` | service suite |
| `query.waitForTrace(traceId, opts)` | polls `getTrace` | service suite, `tracing.tree` |
| `buildAnnotationQuery(tags, annotations)` | — (local) | unit + service suite |
| `initTracing(config)` / `client.initTracing` | `POST /api/v2/spans` via `ZipkinSpanExporter` | service suite (`TestTracing`), `tracing.tree` |
| `ZipkinSpanExporter`, `toZipkinSpan` | — (OpenTelemetry `SpanExporter`) | service suite |
| `withSpan`, `traced`, `currentSpan`, `currentTraceId`, `currentSpanId`, `addEvent`, `recordException`, `setError`, `inject`, `extract`, `traceFields` | shared [tracing helpers](tracing.md) | service suite |

Functions that the stock Zipkin container cannot serve (gateway auth errors, rate limits, TLS,
a `DOWN` storage, OTLP export) are covered by unit tests and listed in
[Compatibility](compatibility.md).

## Service configuration

| Setting | Default | Environment variables (precedence order) |
| - | - | - |
| `url` (query API and base of the span endpoint) | `http://127.0.0.1:9411` | `GROW_ZIPKIN_URL`, `ZIPKIN_URL`, base of `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (a trailing `/api/v2/spans` is removed once, a gateway prefix is kept) |
| `endpoint` (span ingest URL of the reporter and the native exporter) | `<url>/api/v2/spans` | `GROW_ZIPKIN_ENDPOINT`, `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (used verbatim) |
| `headers` (gateway credentials, redacted everywhere) | none | — (code only; stock Zipkin has no authentication) |

Tracing adds `GROW_ZIPKIN_PROTOCOL` (`native`, `grpc`, `http/protobuf`),
`GROW_ZIPKIN_COMPRESSION` (`gzip`, `none`) and `OTEL_EXPORTER_ZIPKIN_TIMEOUT` (see
[Tracing setup](functions/tracing-setup.md)). The common settings (timeouts, retries, TLS, proxy)
are in [Configuration](configuration.md).
