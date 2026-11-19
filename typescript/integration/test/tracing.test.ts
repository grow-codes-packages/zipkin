// Integration tests for the tracing preset (ZipkinSpanExporter, propagation, span helpers) against
// the real Zipkin 3.6.1. Spans are exported with OpenTelemetry and read back via the query API.

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { after, before, describe, test } from "node:test";
import { ROOT_CONTEXT, SpanKind, SpanStatusCode, context, trace } from "@opentelemetry/api";
import {
  ZipkinClient,
  ZipkinSpanExporter,
  addEvent,
  buildTree,
  currentSpanId,
  currentTraceId,
  extract,
  initTracing,
  inject,
  recordException,
  setError,
  toZipkinSpan,
  traceFields,
  traced,
  tracingConfigFromEnv,
  withSpan,
  type Trace,
  type ZipkinQueryClient,
  type ZipkinSpan,
} from "@grow-codes/zipkin";
import { loadBootstrapEnv, runId } from "./env.ts";

const tag = `${runId}-${randomBytes(3).toString("hex")}`.toLowerCase().replace(/[^a-z0-9-]/g, "");
const gateway = `sdk-ts-otel-gw-${tag}`;
const worker = `sdk-ts-otel-wk-${tag}`;
const peer = `sdk-ts-otel-peer-${tag}`;
const helperService = `sdk-ts-helpers-${tag}`;

let client: ZipkinClient;
let query: ZipkinQueryClient;
let rootTraceId = "";

function find(t: Trace, name: string) {
  const span = t.spans.find((s) => s.name === name);
  assert.ok(span, `span ${name} missing in ${JSON.stringify(t.spans.map((s) => s.name))}`);
  return span;
}

function raw(spans: ZipkinSpan[] | null, name: string): ZipkinSpan {
  const span = spans?.find((s) => s.name === name);
  assert.ok(span, `zipkin span ${name} missing`);
  return span;
}

before(async () => {
  loadBootstrapEnv();
  client = ZipkinClient.fromEnv();
  query = client.query;

  // gateway (CLIENT, peer.service) → worker (SERVER with event + error, INTERNAL child); context
  // crosses the "network" through W3C + B3 headers.
  const gw = client.initTracing({
    serviceName: gateway,
    serviceVersion: "1.2.3",
    environment: "sdk-test",
    setGlobal: false,
  });
  const wk = client.initTracing({ serviceName: worker, setGlobal: false, compression: "none" });
  assert.equal(gw.config.protocol, "native");
  assert.deepEqual(gw.config.propagators, ["b3", "tracecontext", "baggage"]);
  const root = gw.tracer("sdk-int", "0.1.0").startSpan("GET /orders", {
    kind: SpanKind.CLIENT,
    attributes: { "peer.service": peer, "server.port": 9000, "order.id": tag },
  });
  const headers: Record<string, string> = {};
  gw.propagator.inject(trace.setSpan(ROOT_CONTEXT, root), headers, {
    set: (c, k, v) => {
      c[k] = v;
    },
  });
  assert.ok(headers.traceparent);
  assert.ok(headers.b3);
  const remoteCtx = wk.propagator.extract(ROOT_CONTEXT, headers, {
    get: (c, k) => c[k],
    keys: (c) => Object.keys(c),
  });
  const server = wk.tracer("sdk-int").startSpan(
    "handle /orders",
    {
      kind: SpanKind.SERVER,
      attributes: {
        "int.attr": 42,
        "double.attr": 1.5,
        "bool.attr": true,
        "array.attr": ["a", "b"],
      },
    },
    remoteCtx,
  );
  server.addEvent("cache.miss", { "cache.key": "k1" });
  const db = wk.tracer("sdk-int").startSpan("db.query", {}, trace.setSpan(remoteCtx, server));
  await new Promise((r) => setTimeout(r, 20));
  db.end();
  server.setStatus({ code: SpanStatusCode.ERROR, message: "boom" });
  server.end();
  root.setStatus({ code: SpanStatusCode.OK });
  root.end();
  rootTraceId = root.spanContext().traceId;
  // A CLIENT call without an instrumented server: the edge comes from remoteEndpoint.
  gw.tracer("sdk-int")
    .startSpan("call peer", { kind: SpanKind.CLIENT, attributes: { "peer.service": peer } })
    .end();
  await gw.forceFlush();
  await wk.forceFlush();
});

after(async () => {
  await client.close();
});

describe("initTracing → ZipkinSpanExporter → Zipkin", () => {
  test("the span tree comes back across both services", async () => {
    const t = await query.waitForTrace(rootTraceId, { minSpans: 3, intervalMs: 200 });
    assert.equal(t.traceId, rootTraceId);
    const tree = buildTree(t.spans);
    assert.equal(tree.length, 1);
    assert.equal(tree[0]?.span.name, "get /orders");
    assert.deepEqual(
      tree[0]?.children.map((c) => c.span.name),
      ["handle /orders"],
    );
    assert.deepEqual(
      tree[0]?.children[0]?.children.map((c) => c.span.name),
      ["db.query"],
    );
    const server = find(t, "handle /orders");
    assert.equal(server.serviceName, worker);
    assert.equal(server.kind, "server");
    assert.deepEqual(server.status, { code: "error", message: "boom" });
    assert.deepEqual(
      server.events.map((e) => [e.name, e.attributes]),
      [["cache.miss", { "cache.key": "k1" }]],
    );
    assert.equal(find(t, "get /orders").status.code, "ok");
    assert.equal(find(t, "db.query").kind, "internal");
  });

  test("the wire mapping matches the documented OTel → Zipkin rules", async () => {
    const spans = await query.getTraceSpans(rootTraceId);
    const gwSpan = raw(spans, "get /orders");
    assert.equal(gwSpan.kind, "CLIENT");
    assert.equal(gwSpan.localEndpoint?.serviceName, gateway);
    assert.deepEqual(gwSpan.remoteEndpoint, { serviceName: peer });
    assert.equal(gwSpan.parentId, undefined);
    assert.equal(gwSpan.tags?.["otel.status_code"], "OK");
    assert.equal(gwSpan.tags?.["otel.scope.name"], "sdk-int");
    assert.equal(gwSpan.tags?.["otel.scope.version"], "0.1.0");
    assert.equal(gwSpan.tags?.["service.version"], undefined); // resource attributes are not copied
    assert.equal(gwSpan.tags?.["server.port"], "9000");
    const wkSpan = raw(spans, "handle /orders");
    assert.equal(wkSpan.kind, "SERVER");
    assert.equal(wkSpan.parentId, gwSpan.id);
    assert.equal(wkSpan.remoteEndpoint, undefined);
    assert.equal(wkSpan.tags?.["int.attr"], "42");
    assert.equal(wkSpan.tags?.["double.attr"], "1.5");
    assert.equal(wkSpan.tags?.["bool.attr"], "true");
    assert.equal(wkSpan.tags?.["array.attr"], '["a","b"]');
    assert.equal(wkSpan.tags?.["otel.status_code"], "ERROR");
    assert.equal(wkSpan.tags?.error, "boom");
    assert.equal(wkSpan.annotations?.[0]?.value, '{"cache.miss":{"cache.key":"k1"}}');
    assert.equal(wkSpan.debug, undefined);
    const dbSpan = raw(spans, "db.query");
    assert.equal(dbSpan.kind, undefined);
    assert.ok((dbSpan.duration ?? 0) >= 15_000);
  });

  test("dependency edge and remote service from the exported spans", async () => {
    // Zipkin links a CLIENT span to its SERVER child (gateway → worker, with the server error);
    // a CLIENT span without a child links to its remoteEndpoint (from peer.service).
    const links = await query.getDependencies({
      endTs: Date.now() + 60_000,
      lookbackMs: 3_600_000,
    });
    assert.deepEqual(
      links.filter((l) => l.parent === gateway),
      [
        { parent: gateway, child: peer, callCount: 1, errorCount: 0 },
        { parent: gateway, child: worker, callCount: 1, errorCount: 1 },
      ],
    );
    assert.deepEqual(await query.listRemoteServices(gateway), [peer]);
    assert.ok((await query.listServices()).includes(worker));
    assert.deepEqual(await query.listOperations(worker), ["db.query", "handle /orders"]);
  });

  test("a standalone ZipkinSpanExporter + toZipkinSpan round trip", async () => {
    const handle = initTracing({ serviceName: worker, setGlobal: false }, { spanProcessors: [] });
    const exporter = new ZipkinSpanExporter({ config: client.config, compression: "none" });
    try {
      const span = handle.tracer("direct").startSpan("direct-export");
      span.end();
      const spanData = span as unknown as Parameters<typeof toZipkinSpan>[0];
      const mapped = toZipkinSpan(spanData);
      const result = await new Promise<{ code: number }>((resolve) => {
        exporter.export([spanData], resolve);
      });
      assert.equal(result.code, 0);
      const back = await query.getTraceSpans(mapped.traceId);
      assert.equal(back?.[0]?.id, mapped.id);
      assert.equal(back?.[0]?.name, "direct-export");
    } finally {
      await exporter.shutdown();
      await handle.shutdown();
    }
  });

  test("tracingConfigFromEnv reads the Zipkin endpoint variables", () => {
    const cfg = tracingConfigFromEnv({ serviceName: "x" });
    const expected =
      process.env.GROW_ZIPKIN_ENDPOINT ??
      process.env.OTEL_EXPORTER_ZIPKIN_ENDPOINT ??
      `${process.env.GROW_ZIPKIN_URL?.replace(/\/+$/, "")}/api/v2/spans`;
    assert.equal(cfg.endpoint, expected);
  });
});

describe("span helpers with the global provider", () => {
  test("withSpan, traced, events, errors, propagation and traceFields round-trip", async () => {
    const handle = initTracing({ serviceName: helperService });
    let traceId = "";
    try {
      await withSpan(
        "helper-root",
        async () => {
          traceId = currentTraceId() ?? "";
          assert.match(traceId, /^[0-9a-f]{32}$/);
          assert.match(currentSpanId() ?? "", /^[0-9a-f]{16}$/);
          assert.equal(traceFields().trace_id, traceId);
          addEvent("helper.event", { n: 1 });
          const carrier: Record<string, string> = {};
          inject(carrier);
          assert.ok(carrier.traceparent?.includes(traceId));
          assert.ok(carrier.b3?.startsWith(traceId));
          const remote = extract(carrier);
          assert.equal(trace.getSpanContext(remote)?.traceId, traceId);
          const work = traced(async (x: number) => x * 2, "helper-traced");
          assert.equal(await work(21), 42);
          await withSpan("helper-failing", () => {
            const err = new Error("helper boom");
            recordException(err);
            setError(err);
          });
          assert.throws(
            () =>
              withSpan("helper-throws", () => {
                throw new Error("thrown");
              }),
            /thrown/,
          );
        },
        { attributes: { helper: true }, kind: SpanKind.SERVER },
      );
      assert.equal(context.active(), ROOT_CONTEXT);
      await handle.forceFlush();
      const t = await query.waitForTrace(traceId, { minSpans: 4, intervalMs: 200 });
      const root = find(t, "helper-root");
      assert.equal(root.attributes.helper, "true");
      assert.deepEqual(root.events.find((e) => e.name === "helper.event")?.attributes, { n: 1 });
      assert.equal(find(t, "helper-traced").parentSpanId, root.spanId);
      assert.equal(find(t, "helper-failing").status.code, "error");
      assert.ok(find(t, "helper-failing").events.some((e) => e.name === "exception"));
      assert.equal(find(t, "helper-throws").status.code, "error");
    } finally {
      await handle.shutdown();
    }
  });
});
