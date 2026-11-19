# Zipkin SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/zipkin/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **Zipkin** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against a real
`openzipkin/zipkin:3.6.1` in Docker.

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/zipkin`](https://www.npmjs.com/package/@grow-codes/zipkin) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-typescript.json) |
| Rust | [`grow-zipkin`](https://crates.io/crates/grow-zipkin) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/zipkin/go`](https://pkg.go.dev/github.com/grow-codes-packages/zipkin/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-go.json) |
| Python | [`grow-zipkin`](https://pypi.org/project/grow-zipkin/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-python.json) |
| Java | [`codes.grow.packages:zipkin`](https://central.sonatype.com/artifact/codes.grow.packages/zipkin) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/zipkin/badges/coverage-java.json) |

## What you get

- **Tracing**: OpenTelemetry setup in one call with the package's own Zipkin exporter (v2 JSON to
  `/api/v2/spans`, no deprecated OpenTelemetry Zipkin exporter), W3C + B3 propagation, span
  helpers and framework adapters ([Tracing setup](functions/tracing-setup.md), [Tracing](tracing.md)).
- **Span reporter**: send Zipkin v2 spans without OpenTelemetry ([Span reporter](functions/reporter.md)).
- **Trace queries**: the full Zipkin v2 query API plus the category `TraceQueryClient` with one
  normalized trace model ([Trace queries](functions/query.md)).
- **Client** with health, ping and server version ([Client and status](functions/client.md)).
- **Configuration** from code or `GROW_ZIPKIN_*` / `ZIPKIN_URL` / `OTEL_EXPORTER_ZIPKIN_ENDPOINT`,
  immutable and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language.
- **Typed errors** with one set of codes across languages ([Error handling](errors.md)).
- **Redaction everywhere**: gateway credentials never appear in errors, logs, hooks or `toString`
  ([Redaction](redaction.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Zipkin is a trademark of
    The OpenZipkin authors. This project is not affiliated with or endorsed by The OpenZipkin authors. See
    [License & disclaimer](license.md).
