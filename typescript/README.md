# @grow-codes/zipkin

TypeScript SDK for [Zipkin](https://grow-codes-packages.github.io/zipkin/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/zipkin
```

```ts
import { ZipkinClient } from "@grow-codes/zipkin";

// Reads GROW_ZIPKIN_URL, GROW_ZIPKIN_TOKEN, timeouts, TLS and proxy settings.
const client = ZipkinClient.fromEnv();
try {
  console.log(await client.health()); // { healthy: true, status: "ok", details: {} }
  console.log(`ping: ${(await client.ping()).toFixed(1)} ms`);
} finally {
  await client.close();
}
```

Every error is a `ZipkinError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/zipkin/testing`.

Documentation: https://grow-codes-packages.github.io/zipkin/ · Licence: Apache-2.0.
Zipkin is a trademark of The OpenZipkin authors; this package is not affiliated with or endorsed by it.
