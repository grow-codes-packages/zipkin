# @grow-codes/zipkin

TypeScript SDK for [Zipkin](https://grow-codes-packages.github.io/zipkin/) by Grow Codes
(tested against `openzipkin/zipkin:3.6.1`). Node.js 22.12 or newer, ESM and CommonJS, full type
definitions.

```sh
npm install @grow-codes/zipkin
```

```ts
import { ZipkinClient, withSpan } from "@grow-codes/zipkin";

// Reads GROW_ZIPKIN_URL / ZIPKIN_URL / OTEL_EXPORTER_ZIPKIN_ENDPOINT, timeouts, TLS and proxy.
const client = ZipkinClient.fromEnv();
try {
  console.log(await client.health()); // { healthy: true, status: "UP", details: {...} }
  console.log(await client.serverVersion()); // "3.6.1"

  // OpenTelemetry → Zipkin v2 JSON (POST /api/v2/spans); W3C + B3 propagation.
  const tracing = client.initTracing({ serviceName: "checkout" });
  await withSpan("place order", async () => {
    /* ... */
  });
  await tracing.forceFlush();

  // Query API.
  console.log(await client.query.listServices());
  console.log(await client.query.getTraces({ serviceName: "checkout", limit: 5 }));
} finally {
  await client.close(); // also flushes and shuts down the tracing handle
}
```

| Module | What |
| - | - |
| `ZipkinClient` | `health()`, `ping()`, `serverVersion()`, `serverInfo()`, `query`, `reporter`, `initTracing()`, `close()` |
| `ZipkinReporter` | `report(spans)` — Zipkin v2 spans without OpenTelemetry (`ZipkinSpan`, `ZipkinEndpoint`, `ZipkinAnnotation`) |
| `ZipkinQueryClient` | `getTraceSpans`, `getTraces`, `getTraceMany`, `listServices`, `listSpans`, `listRemoteServices`, `getDependencies`, `autocompleteKeys`, `autocompleteValues` + SPEC `getTrace`, `searchTraces`, `listOperations`, `waitForTrace` |
| `initTracing` | OpenTelemetry setup with `ZipkinSpanExporter` (default `native`), or OTLP to a Collector |

Stock Zipkin has no authentication: pass gateway credentials in `headers`; they are redacted in
messages, logs, hook data and `toString()`. Every error is a `ZipkinError` subclass with a
canonical `code` (`VALIDATION`, `NOT_FOUND`, `UNAVAILABLE`, …), `httpStatus` and `retryable`.

Test helpers (in-memory fakes and the conformance runner) live in `@grow-codes/zipkin/testing`.

Documentation: https://grow-codes-packages.github.io/zipkin/ · Licence: Apache-2.0.
Zipkin is a trademark of The OpenZipkin authors; this package is not affiliated with or endorsed by it.
