// Zipkin TypeScript SDK tour: configuration from the environment, health and version, OpenTelemetry
// tracing through the native Zipkin exporter, the direct span reporter and the query API.
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   GROW_ZIPKIN_URL=http://127.0.0.1:9411 npm start

import { randomBytes } from "node:crypto";
import {
  ZipkinClient,
  ZipkinError,
  currentTraceId,
  withSpan,
} from "@grow-codes/zipkin";

const client = ZipkinClient.fromEnv({
  // Warnings only; the default logger is silent. Gateway credentials would go in `headers`.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
});
console.log(`config: ${client.config}`); // header values print as [REDACTED]

try {
  const health = await client.health(); // never throws for an unreachable service
  console.log("health:", health);
  if (!health.healthy) process.exit(1);
  console.log(
    `ping: ${(await client.ping()).toFixed(1)} ms, version: ${await client.serverVersion()}`,
  );

  // 1. OpenTelemetry: spans go to /api/v2/spans as Zipkin v2 JSON; W3C + B3 headers propagate.
  const tracing = client.initTracing({
    serviceName: "example-checkout",
    serviceVersion: "1.0.0",
  });
  let traceId = "";
  await withSpan(
    "checkout",
    async () => {
      traceId = currentTraceId() ?? "";
      await withSpan(
        "charge card",
        async () => new Promise((r) => setTimeout(r, 20)),
        {
          kind: "client",
          attributes: { "peer.service": "example-payments" },
        },
      );
    },
    { kind: "server", attributes: { "order.id": "o-42" } },
  );
  await tracing.forceFlush();
  const trace = await client.query.waitForTrace(traceId, { minSpans: 2 });
  console.log(
    "trace:",
    trace.spans.map((s) => `${s.serviceName}/${s.name} (${s.kind})`),
  );

  // 2. Direct reporter for apps without OpenTelemetry (Zipkin v2 model).
  const id = randomBytes(16).toString("hex");
  const now = Date.now() * 1000;
  await client.reporter.report([
    {
      traceId: id,
      id: randomBytes(8).toString("hex"),
      kind: "SERVER",
      name: "GET /stock",
      timestamp: now,
      duration: 12_000,
      localEndpoint: { serviceName: "example-inventory" },
      tags: { "http.method": "GET", cached: true },
    },
  ]);
  console.log("reported:", await client.query.getTraceSpans(id));

  // 3. Query API.
  console.log("services:", await client.query.listServices());
  console.log(
    "spans of example-checkout:",
    await client.query.listSpans("example-checkout"),
  );
  console.log(
    "remote services:",
    await client.query.listRemoteServices("example-checkout"),
  );
  const recent = await client.query.getTraces({
    serviceName: "example-inventory",
    tags: { "http.method": "GET" },
    lookbackMs: 15 * 60_000,
    limit: 5,
  });
  console.log(`recent inventory traces: ${recent.length}`);
  console.log(
    "search:",
    await client.query.searchTraces({ service: "example-checkout", limit: 3 }),
  );
  console.log(
    "dependencies:",
    await client.query.getDependencies({ lookbackMs: 3_600_000 }),
  );
  console.log("autocomplete keys:", await client.query.autocompleteKeys());
} catch (err) {
  if (err instanceof ZipkinError) {
    // Canonical code + HTTP status; the message never contains credentials.
    console.error(`${err.code}: ${err.message}`, {
      httpStatus: err.httpStatus,
    });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await client.close(); // flushes and shuts down the tracing handle too
}
