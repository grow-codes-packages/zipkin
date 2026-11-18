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

- **Client** with health, authenticated ping and server version ([Client](client.md)).
- **Configuration** from code or `GROW_ZIPKIN_*` / vendor environment variables, immutable
  and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language.
- **Typed errors** with one set of codes across languages ([Error handling](errors.md)).
- **Redaction everywhere**: credentials never appear in errors, logs, hooks or `toString`
  ([Redaction](redaction.md)).
- **Tracing**: OpenTelemetry setup, span helpers, propagation, framework adapters and trace
  queries ([Tracing](tracing.md)).
- **Service functions** for the Zipkin API ([Functions](functions.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Zipkin is a trademark of
    The OpenZipkin authors. This project is not affiliated with or endorsed by The OpenZipkin authors. See
    [License & disclaimer](license.md).
