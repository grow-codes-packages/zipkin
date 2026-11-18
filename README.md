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

- **Typed client** with timeouts, retries with jitter, `Retry-After`, cancellation and one error
  model (`ZipkinError` with canonical codes) in every language.
- **Secrets stay secret**: credentials are redacted in `toString`, logs, hooks and error messages.
- **Tracing**: OpenTelemetry setup in one call, span helpers, context propagation, framework
  middleware and a trace query client.

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

Every package reads its configuration from the environment (`GROW_ZIPKIN_URL`,
`GROW_ZIPKIN_TOKEN`, …, see [Configuration](#configuration)) or from an explicit config
object. The examples check the service health and make one authenticated call.

### TypeScript

```ts
import { ZipkinClient } from "@grow-codes/zipkin";

const client = ZipkinClient.fromEnv();
try {
  const health = await client.health();
  console.log(health.healthy, health.status);
  console.log("latency ms:", await client.ping());
} finally {
  await client.close();
}
```

### Rust

```rust
use grow_zipkin::ZipkinClient;

#[tokio::main]
async fn main() -> Result<(), grow_zipkin::Error> {
    let client = ZipkinClient::from_env()?;
    let health = client.health().await?;
    println!("{} {}", health.healthy, health.status);
    println!("latency: {:?}", client.ping().await?);
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
	defer client.Close()

	health, err := client.Health(ctx)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(health.Healthy, health.Status)
}
```

### Python

```python
from grow_zipkin import ZipkinClient

with ZipkinClient.from_env() as client:
    health = client.health()
    print(health.healthy, health.status)
    print("latency ms:", client.ping())
```

Async code uses `AsyncZipkinClient` with the same methods (`async with`, `await client.health()`).

### Java

```java
import codes.grow.packages.zipkin.ZipkinClient;

try (ZipkinClient client = ZipkinClient.fromEnv()) {
    var health = client.health();
    System.out.println(health.healthy() + " " + health.status());
    System.out.println("latency ms: " + client.ping());
}
```

More complete programs live in [examples/](examples/) — one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_ZIPKIN_*` variable > vendor variable > default.** A present but invalid value raises a
`ConfigError` that names the variable (never its value).

| Setting | Environment variable | Default |
| - | - | - |
| `url` | `GROW_ZIPKIN_URL` | service default |
| `token` | `GROW_ZIPKIN_TOKEN` | none |
| `connectTimeout` | `GROW_ZIPKIN_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_ZIPKIN_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_ZIPKIN_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_ZIPKIN_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_ZIPKIN_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_ZIPKIN_CLIENT_CERT` / `GROW_ZIPKIN_CLIENT_KEY` | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_ZIPKIN_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_ZIPKIN_PROXY` | none (environment proxies are ignored) |
| `headers`, `userAgentSuffix`, `hooks`, `logger` | — | none |

Vendor variables that are also read: `ZIPKIN_URL`, `OTEL_EXPORTER_ZIPKIN_ENDPOINT`, `GROW_ZIPKIN_*`.

Durations accept milliseconds (`1500`) or units (`500ms`, `10s`, `1m30s`); booleans accept
`true/false/1/0/yes/no/on/off`. Field names follow each language's convention
(`request_timeout` in Python and Rust, `RequestTimeout` in Go). The full reference is in the
[documentation](https://grow-codes-packages.github.io/zipkin/).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`getString` in
TypeScript and Java, `get_string` in Python and Rust, `GetString` in Go).

| Area | Functions | Description |
| - | - | - |
| Client | `health`, `ping`, `serverVersion`, `close` | liveness, authenticated round trip, server version, shutdown |
| Tracing | `initTracing`, `withSpan`, `traced`, `inject`, `extract`, `getTrace`, `searchTraces`, `listServices`, `listOperations`, `getDependencies`, `waitForTrace` | OpenTelemetry setup and trace queries |

<!-- /service:function-overview -->

## Testing with Docker

The integration suite runs every public function against a real Zipkin (`openzipkin/zipkin:3.6.1`) in Docker.
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

Functions that Zipkin (or its emulator) cannot serve are listed with the reason in
[docs/compatibility.md](docs/compatibility.md).

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
sponsored by The OpenZipkin authors**. Zipkin is a trademark of The OpenZipkin authors; it is used here only to name the
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
