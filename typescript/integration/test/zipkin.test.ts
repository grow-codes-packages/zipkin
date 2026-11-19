// Integration tests for ZipkinReporter and ZipkinQueryClient against the real Zipkin 3.6.1.
// Every write goes through the reporter and is read back through the query API.

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { after, before, describe, test } from "node:test";
import {
  ValidationError,
  ZipkinClient,
  ZipkinQueryClient,
  ZipkinReporter,
  buildAnnotationQuery,
  type ZipkinQueryClient as Query,
  type ZipkinSpan,
} from "@grow-codes/zipkin";
import { loadBootstrapEnv, runId } from "./env.ts";

const tag = `${runId}-${randomBytes(3).toString("hex")}`.toLowerCase().replace(/[^a-z0-9-]/g, "");
const frontend = `sdk-ts-fe-${tag}`;
const backend = `sdk-ts-be-${tag}`;
const lonely = `sdk-ts-gz-${tag}`;
const salty = `sdk-ts-salt-${tag}`;
const hex = (bytes: number) => randomBytes(bytes).toString("hex");
const traceId = hex(16);
const trace64 = hex(8);
const saltTrace = hex(16);
const [rootId, clientId, serverId, lonelyId, saltId] = [hex(8), hex(8), hex(8), hex(8), hex(8)];
const nowMicros = Date.now() * 1000;

let client: ZipkinClient;
let query: Query;

before(async () => {
  loadBootstrapEnv();
  client = ZipkinClient.fromEnv();
  query = client.query;
  // frontend SERVER root → frontend CLIENT → backend SERVER (error), as JSON (no compression).
  await client.reporter.report([
    {
      traceId,
      id: rootId,
      kind: "SERVER",
      name: "GET /API/Orders",
      timestamp: nowMicros,
      duration: 150_000,
      localEndpoint: { serviceName: frontend, ipv4: "10.0.0.1", port: 8080 },
      annotations: [{ timestamp: nowMicros + 1000, value: "cache.miss" }],
      tags: { "grow.test": tag, "http.method": "GET" },
    },
    {
      traceId,
      parentId: rootId,
      id: clientId,
      kind: "client",
      name: "get /orders",
      timestamp: nowMicros + 5000,
      duration: 100_000,
      localEndpoint: { serviceName: frontend },
      remoteEndpoint: { serviceName: backend, port: 9000 },
      tags: { "grow.test": tag, "http.method": "GET" },
    },
    {
      traceId,
      parentId: clientId,
      id: serverId,
      kind: "SERVER",
      name: "get /orders",
      timestamp: nowMicros + 10_000,
      duration: 80_000,
      localEndpoint: { serviceName: backend, port: 9000 },
      tags: { error: "boom", "grow.test": tag },
    },
  ]);
  // A 64-bit trace with upper-case ids and non-string tags, gzip-compressed.
  const gzip = client.createReporter({ compression: "gzip" });
  await gzip.report([
    {
      traceId: trace64.toUpperCase(),
      id: lonelyId.toUpperCase(),
      name: "lonely-op",
      timestamp: nowMicros,
      duration: 5000,
      localEndpoint: { serviceName: lonely },
      tags: { "grow.test": tag, flag: true, n: 7 },
    },
  ]);
  // A tag value Zipkin's annotationQuery cannot express.
  await new ZipkinReporter(client).report([
    {
      traceId: saltTrace,
      id: saltId,
      name: "season",
      timestamp: nowMicros,
      duration: 1000,
      localEndpoint: { serviceName: salty },
      tags: { note: "salt and pepper" },
    },
  ]);
});

after(async () => {
  await client.close();
});

function names(spans: ZipkinSpan[]): string[] {
  return spans.map((s) => s.name ?? "").sort();
}

describe("ZipkinReporter → query round trip", () => {
  test("getTraceSpans returns the reported spans (names lower-cased by the server)", async () => {
    const spans = await query.getTraceSpans(traceId);
    assert.ok(spans);
    assert.deepEqual(names(spans), ["get /api/orders", "get /orders", "get /orders"]);
    const root = spans.find((s) => s.id === rootId);
    assert.equal(root?.kind, "SERVER");
    assert.deepEqual(root?.annotations, [{ timestamp: nowMicros + 1000, value: "cache.miss" }]);
    assert.equal(spans.find((s) => s.id === clientId)?.remoteEndpoint?.serviceName, backend);
  });

  test("gzip reporter, upper-case ids and non-string tags are normalized", async () => {
    const spans = await query.getTraceSpans(trace64);
    assert.equal(spans?.length, 1);
    assert.equal(spans?.[0]?.traceId, trace64);
    assert.equal(spans?.[0]?.id, lonelyId);
    assert.deepEqual(spans?.[0]?.tags, { flag: "true", "grow.test": tag, n: "7" });
  });

  test("getTrace returns the normalized model; missing → null; invalid → ValidationError", async () => {
    const trace = await query.getTrace(traceId);
    assert.ok(trace);
    assert.equal(trace.traceId, traceId);
    assert.equal(trace.spans[0]?.spanId, rootId);
    assert.equal(trace.spans[0]?.kind, "server");
    assert.equal(trace.spans[0]?.serviceName, frontend);
    assert.deepEqual(trace.spans[0]?.events, [
      { name: "cache.miss", timeMicros: nowMicros + 1000, attributes: {} },
    ]);
    const failed = trace.spans.find((s) => s.spanId === serverId);
    assert.deepEqual(failed?.status, { code: "error", message: "boom" });
    assert.equal(failed?.parentSpanId, clientId);
    const padded = await query.getTrace(trace64);
    assert.equal(padded?.traceId, `0000000000000000${trace64}`);
    assert.equal(await query.getTrace(hex(16)), null);
    await assert.rejects(query.getTrace("not-a-trace"), ValidationError);
    await assert.rejects(client.reporter.report([{ traceId: "zz", id: rootId }]), ValidationError);
  });

  test("waitForTrace resolves once the trace has the spans", async () => {
    const trace = await query.waitForTrace(traceId, { minSpans: 3, intervalMs: 200 });
    assert.equal(trace.spans.length, 3);
  });
});

describe("ZipkinQueryClient search", () => {
  test("getTraces with every filter", async () => {
    const traces = await query.getTraces({
      serviceName: frontend.toUpperCase(),
      spanName: "get /api/orders",
      tags: { "grow.test": tag },
      annotations: ["cache.miss"],
      minDurationMicros: 100_000,
      maxDurationMicros: 200_000,
      endTs: Date.now() + 60_000,
      lookbackMs: 3_600_000,
      limit: 5,
    });
    assert.deepEqual(
      traces.map((t) => t[0]?.traceId),
      [traceId],
    );
    const raw = await query.getTraces({
      serviceName: frontend,
      annotationQuery: buildAnnotationQuery({ "http.method": "GET" }, ["cache.miss"]),
    });
    assert.equal(raw.length, 1);
    const remote = await query.getTraces({ serviceName: frontend, remoteServiceName: backend });
    assert.equal(remote[0]?.[0]?.traceId, traceId);
    const none = await query.getTraces({ serviceName: frontend, minDurationMicros: 10_000_000 });
    assert.deepEqual(none, []);
  });

  test("tag values containing ' and ' are matched client-side", async () => {
    const hit = await query.getTraces({ serviceName: salty, tags: { note: "salt and pepper" } });
    assert.deepEqual(
      hit.map((t) => t[0]?.traceId),
      [saltTrace],
    );
    const miss = await query.getTraces({ serviceName: salty, tags: { note: "salt and sugar" } });
    assert.deepEqual(miss, []);
  });

  test("getTraceMany keeps request order, dedupes and omits missing ids", async () => {
    const many = await query.getTraceMany([
      trace64,
      traceId.toUpperCase(),
      hex(16),
      `0000000000000000${trace64}`,
      traceId,
    ]);
    assert.deepEqual(
      many.map((t) => t[0]?.traceId),
      [trace64, traceId],
    );
    // More than one chunk (100 ids per request) still finds both traces.
    const ids = [...Array.from({ length: 150 }, () => hex(16)), traceId, trace64];
    const chunked = await query.getTraceMany(ids);
    assert.deepEqual(
      chunked.map((t) => t[0]?.traceId),
      [traceId, trace64],
    );
    assert.deepEqual(await query.getTraceMany([]), []);
    assert.equal((await query.getTraceMany([traceId]))[0]?.length, 3);
  });

  test("searchTraces summarises traces (SPEC §5)", async () => {
    const summaries = await query.searchTraces({
      service: frontend,
      operation: "get /api/orders",
      tags: { "grow.test": tag },
      start: new Date(Date.now() - 3_600_000),
      end: new Date(Date.now() + 60_000),
      limit: 10,
    });
    assert.equal(summaries.length, 1);
    assert.deepEqual(summaries[0], {
      traceId,
      rootServiceName: frontend,
      rootName: "get /api/orders",
      startMicros: nowMicros,
      durationMicros: 150_000,
      spanCount: 3,
    });
  });
});

describe("ZipkinQueryClient names, dependencies and autocomplete", () => {
  test("listServices / listSpans / listOperations / listRemoteServices", async () => {
    const services = await query.listServices();
    for (const s of [frontend, backend, lonely]) assert.ok(services.includes(s), s);
    assert.deepEqual(await query.listSpans(frontend), ["get /api/orders", "get /orders"]);
    assert.deepEqual(await query.listOperations(backend), ["get /orders"]);
    assert.deepEqual(await query.listRemoteServices(frontend), [backend]);
    assert.deepEqual(await query.listSpans(`unknown-${tag}`), []);
  });

  test("getDependencies reports the frontend → backend edge with its error", async () => {
    const native = await query.getDependencies({
      endTs: Date.now() + 60_000,
      lookbackMs: 3_600_000,
    });
    assert.deepEqual(
      native.find((l) => l.parent === frontend),
      { parent: frontend, child: backend, callCount: 1, errorCount: 1 },
    );
    const window = await query.getDependencies({
      start: new Date(Date.now() - 3_600_000),
      end: new Date(Date.now() + 60_000),
    });
    assert.ok(window.some((l) => l.parent === frontend && l.child === backend));
  });

  test("autocompleteKeys / autocompleteValues", async () => {
    const keys = await query.autocompleteKeys();
    for (const key of (process.env.ZIPKIN_AUTOCOMPLETE_KEYS ?? "grow.test,http.method").split(","))
      assert.ok(keys.includes(key), key);
    assert.ok((await query.autocompleteValues("grow.test")).includes(tag));
    assert.ok((await query.autocompleteValues("http.method")).includes("GET"));
    assert.deepEqual(await query.autocompleteValues(`not-allowlisted-${tag}`), []);
  });

  test("a standalone query client owns its connections", async () => {
    const standalone = ZipkinQueryClient.fromEnv();
    try {
      assert.ok((await standalone.listServices()).includes(frontend));
    } finally {
      await standalone.close();
    }
    const reporter = ZipkinReporter.fromEnv({}, { maxBatchSize: 1 });
    try {
      const id = hex(16);
      await reporter.report([
        {
          traceId: id,
          id: hex(8),
          name: "a",
          timestamp: nowMicros,
          localEndpoint: { serviceName: lonely },
        },
        {
          traceId: id,
          id: hex(8),
          name: "b",
          timestamp: nowMicros,
          localEndpoint: { serviceName: lonely },
        },
      ]);
      assert.equal((await query.getTraceSpans(id))?.length, 2);
    } finally {
      await reporter.close();
    }
  });
});
