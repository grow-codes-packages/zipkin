# Compatibility

## Runtimes

| Language | Supported | Tested in CI |
| - | - | - |
| TypeScript | Node.js ≥ 22.12, ESM and CommonJS | Node 22, 24 |
| Rust | MSRV 1.88, edition 2021, tokio | stable, 1.88 |
| Go | Go ≥ 1.26 | 1.26, 1.27 |
| Python | ≥ 3.10 | 3.10, 3.13, 3.14 |
| Java | ≥ 17 (Java 17 bytecode) | Temurin 17, 21, 25 |

## Zipkin versions

The integration suite runs against `openzipkin/zipkin:3.6.1` (the Grow stack catalog tag) with
in-memory storage, `AUTOCOMPLETE_KEYS=grow.test,http.method` and `COLLECTOR_GRPC_ENABLED=true`
(test-only additions to the stack template). Zipkin 3.x servers with the same v2 API are expected to
work; the v1 API (removed from the Zipkin 3 query side) is not supported.

## Functions the service or a language cannot serve

Every public function is called against the real service in the Docker suite. These behaviours
cannot be produced by the stock container; they are covered by unit tests with mocked HTTP:

| Behaviour | Languages | Reason |
| - | - | - |
| `AUTHENTICATION` / `PERMISSION_DENIED` (401/403) | all | Zipkin has no authentication; only a gateway in front of it answers 401/403 |
| `RATE_LIMITED`, `Retry-After`, retries with backoff | all | Zipkin has no rate limiting |
| `health()` with `DOWN` (HTTP 503) | all | in-memory storage cannot fail |
| `VALIDATION` for an oversized batch (HTTP 413) | all | needs a request above Armeria's 10 MiB limit |
| TLS, client certificates, proxy | all | the stock image serves plain HTTP |
| OTLP export (`protocol: grpc` / `http/protobuf`) | all | needs an OpenTelemetry Collector with a `zipkin` exporter; not part of the fixture |
| `getDependencies` on persistent storage | all | needs the zipkin-dependencies batch job; in-memory storage computes edges on read (tested) |
| `getTraceMany` chunking above 100 ids and re-ordering | all | storage dependent; tested with recorded fixtures |

No function returns `UNSUPPORTED` against Zipkin 3.6.1. `serverVersion()` returns `UNSUPPORTED`
only when a server or gateway exposes no `/info` version.

## Skipped conformance scenarios

| Scenario | Reason |
| - | - |
| — | none (`core.health`, `tracing.services`, `tracing.tree` run in all five languages) |

## Service behaviour the SDK handles

| Zipkin behaviour | What the SDK does |
| - | - |
| Upper-case trace/span ids → 400 on ingest and query | lower-cases ids locally; anything but 16/32 (trace) or 16 (span) hex characters is `VALIDATION` without a request |
| Service and span names are lower-cased on ingest | nothing to do; query parameters match case-insensitively (tests compare lower-case names) |
| Error bodies are plain text (`text/*`), not JSON | the text is kept in the error message (redacted) |
| A boolean tag rejects the whole batch (400) | every tag value is turned into a string before sending |
| `errorCount` is omitted from dependency links when 0 | defaults to 0 (`ZipkinDependencyLink.errorCount`; the category `DependencyLink` has no such field) |
| `/api/v2/traceMany` returns 414 above ~4 KB of URL | `getTraceMany` sends chunks of 100 ids |
| `/api/v2/traceMany` rejects one id or duplicates | 0 ids → `[]` locally, 1 id → `/api/v2/trace/{id}`, duplicates removed, request order kept |
| `annotationQuery` has AND only and cannot express ` and ` inside a term | such terms are matched client-side after the query (`getTraces`, `searchTraces`); `buildAnnotationQuery` rejects them |
| `GET /api/v2/traces` without `lookback` searches 24 h | `searchTraces` without `start` sends the last hour, the same default as every Grow tracing package; `getTraces` keeps the server default |
| In-memory storage groups traces that share their low 64 id bits | random 128-bit ids make this negligible; the tests use distinct low halves |
| No OTLP receiver (`POST /v1/traces` → 404) | the native exporter sends v2 JSON; OTLP needs a Collector (an OTLP endpoint ending in `/api/v2/spans` is a `CONFIG` error) |
| No delete or update API | not offered |
| `autocompleteKeys` is `[]` unless the server sets `AUTOCOMPLETE_KEYS` | documented; the fixture sets two keys |
| Persistent backends can list a new service or trace after a delay | `waitForTrace` polls; the conformance scenario waits for the trace (`getTraceUntil`) before `listServices` (in-memory storage lists it at once) |

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Deliberate differences:

| Topic | Difference |
| - | - |
| Closed client | every call on a closed client fails with `CANCELLED` ("client is closed"); `initTracing` on a closed client fails with `CONFIG` — the same in all five languages |
| `TracingConfig` defaults (Rust, Java) | the shared `TracingConfig` cannot tell "unset" from "default". In Rust a field still at its `TracingConfig::new` value counts as unset (environment, then the Zipkin default); in Java the generic default protocol (`http/protobuf`), the default propagators and `gzip` compression count as unset. So `protocol` falls back to `GROW_ZIPKIN_PROTOCOL` or `native`. To force OTLP over HTTP in code use `ZipkinTracingOptions::protocol(Protocol::HttpProtobuf)` (Rust) or `ZipkinTracing.Options.builder().protocol(Protocol.HTTP_PROTOBUF)` (Java). TypeScript, Go and Python have real "unset" values |
| Normalized spans | TypeScript and Python return `ZipkinTraceSpan` (the SPEC span plus `remoteEndpoint`, `shared`, `debug`). The SPEC `Span` in Go, Rust and Java has no room for them (Java records), so these fields stay on the native `ZipkinSpan` (`getTraceSpans`) |
| `getDependencies` | TypeScript, Python and Rust return `ZipkinDependencyLink` (with `errorCount`) from one method that takes both window styles. Go has `GetDependencies` (category `DependencyLink`) and `GetDependenciesAt(endTs, lookback)` (`ZipkinDependencyLink`); Java has `getDependencies(start, end)` (category) and `getDependencies(endTs, lookback)` (Zipkin) |
| `health()` details | the `storage` detail is an object in TypeScript and compact JSON text in the other languages (their detail maps hold strings) |
| Event annotations | an event with attributes becomes the JSON text `{"<name>":{…}}`; the key order inside may differ (Rust sorts keys) |
| `buildAnnotationQuery` | tag terms follow the map order (insertion order in TypeScript, Python and Java; sorted in Go and Rust) |
| Environment in Java | Java cannot change the process environment, so `loadEnv` sets **system properties** instead and returns the names it set |
