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

The integration suite runs against `openzipkin/zipkin:3.6.1`. Other versions that expose the same API are
expected to work; report problems as an issue.

## Functions the service or a language cannot serve

Every public function is called against the real service in the Docker suite. Functions that the
service, its emulator or a deployment type cannot serve are listed here with the reason; they are
covered by unit tests and raise `UNSUPPORTED` where the capability is missing.

| Function | Languages | Reason |
| - | - | - |
| — | — | none so far |

## Skipped conformance scenarios

| Scenario | Reason |
| - | - |
| — | none so far |

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Java cannot change the process environment, so `loadEnv` sets **system properties**
instead and returns the names it set.
