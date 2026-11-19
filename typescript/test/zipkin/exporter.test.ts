import { SpanKind, SpanStatusCode, context, trace } from "@opentelemetry/api";
import { ExportResultCode, type ExportResult } from "@opentelemetry/core";
import { resourceFromAttributes } from "@opentelemetry/resources";
import {
  BasicTracerProvider,
  InMemorySpanExporter,
  SimpleSpanProcessor,
  type ReadableSpan,
} from "@opentelemetry/sdk-trace-base";
import { afterEach, describe, expect, it } from "vitest";
import {
  UNKNOWN_SERVICE,
  ZipkinConfig,
  ZipkinReporter,
  ZipkinSpanExporter,
  attributeToTag,
  toZipkinSpan,
} from "../../src/index.js";
import { startServer } from "../helpers/server.js";
import { fakeZipkin, type FakeZipkin } from "../helpers/zipkin.js";

const fakes: { close(): Promise<void> }[] = [];
afterEach(async () => {
  await Promise.all(fakes.splice(0).map((f) => f.close()));
});

function recorder(
  resource: Record<string, string> = { "service.name": "grow-exporter" },
  limits: { attributeCountLimit?: number; eventCountLimit?: number } = {},
) {
  const memory = new InMemorySpanExporter();
  const provider = new BasicTracerProvider({
    resource: resourceFromAttributes(resource),
    spanProcessors: [new SimpleSpanProcessor(memory)],
    spanLimits: limits,
  });
  const tracer = provider.getTracer("grow-scope", "1.2.3");
  return { memory, provider, tracer };
}

describe("toZipkinSpan", () => {
  it("maps a server span with attributes, events, status and scope", () => {
    const { memory, tracer } = recorder({
      "service.name": "Grow-Exporter",
      "service.version": "9.9.9",
      "deployment.environment.name": "test",
      "telemetry.sdk.language": "nodejs",
    });
    const parent = tracer.startSpan("parent");
    const span = tracer.startSpan(
      "GET /orders",
      {
        kind: SpanKind.SERVER,
        attributes: { s: "x", i: 7, d: 1.5, b: true, arr: ["a", "b"], "service.version": "span" },
        startTime: [1795035874, 5_000_500],
      },
      trace.setSpan(context.active(), parent),
    );
    span.addEvent("cache.miss", { "cache.key": "k1", hit: false }, [1795035874, 6_000_000]);
    span.addEvent("plain", [1795035874, 7_000_000]);
    span.setStatus({ code: SpanStatusCode.ERROR, message: "boom" });
    span.end([1795035874, 105_000_900]);
    parent.end();
    const z = toZipkinSpan(memory.getFinishedSpans()[0] as ReadableSpan);
    expect(z).toEqual({
      traceId: span.spanContext().traceId,
      id: span.spanContext().spanId,
      parentId: parent.spanContext().spanId,
      kind: "SERVER",
      name: "GET /orders",
      timestamp: 1795035874005000,
      duration: 100000,
      localEndpoint: { serviceName: "Grow-Exporter" },
      annotations: [
        { timestamp: 1795035874006000, value: '{"cache.miss":{"cache.key":"k1","hit":false}}' },
        { timestamp: 1795035874007000, value: "plain" },
      ],
      tags: {
        s: "x",
        i: "7",
        d: "1.5",
        b: "true",
        arr: '["a","b"]',
        "service.version": "span",
        "otel.scope.name": "grow-scope",
        "otel.scope.version": "1.2.3",
        "otel.status_code": "ERROR",
        error: "boom",
      },
    });
    const root = toZipkinSpan(memory.getFinishedSpans()[1] as ReadableSpan);
    expect(root.parentId).toBeUndefined();
    expect(root.kind).toBeUndefined();
    expect(root.duration).toBeGreaterThanOrEqual(1);
  });

  it("derives the remote endpoint of CLIENT/PRODUCER spans only", () => {
    const { memory, tracer } = recorder();
    const cases: [SpanKind, Record<string, string | number>][] = [
      [
        SpanKind.CLIENT,
        { "peer.service": "billing", "network.peer.address": "10.1.2.3", "server.port": 8443 },
      ],
      [
        SpanKind.PRODUCER,
        { "server.address": "kafka", "network.peer.address": "::1", "network.peer.port": "9092" },
      ],
      [SpanKind.CLIENT, { "network.peer.address": "192.168.0.9", "net.peer.port": 70000 }],
      [SpanKind.CLIENT, { "network.peer.address": "db.local" }],
      [SpanKind.CLIENT, { "server.port": 80 }],
      [SpanKind.SERVER, { "peer.service": "ignored" }],
      [SpanKind.CONSUMER, {}],
    ];
    for (const [kind, attributes] of cases) tracer.startSpan("s", { kind, attributes }).end();
    const remotes = memory.getFinishedSpans().map((s) => toZipkinSpan(s).remoteEndpoint);
    expect(remotes).toEqual([
      { serviceName: "billing" },
      { serviceName: "kafka" },
      { serviceName: "192.168.0.9" },
      { serviceName: "db.local" },
      undefined,
      undefined,
      undefined,
    ]);
    expect(memory.getFinishedSpans().map((s) => toZipkinSpan(s).kind)).toEqual([
      "CLIENT",
      "PRODUCER",
      "CLIENT",
      "CLIENT",
      "CLIENT",
      "SERVER",
      "CONSUMER",
    ]);
  });

  it("OK status, dropped counts, missing service name and empty scope", () => {
    const { memory, tracer } = recorder({}, { attributeCountLimit: 1, eventCountLimit: 1 });
    const span = tracer.startSpan("limited", { attributes: { a: "1", b: "2" } });
    span.addEvent("e1");
    span.addEvent("e2");
    span.setStatus({ code: SpanStatusCode.OK });
    span.end();
    const real = memory.getFinishedSpans()[0] as ReadableSpan;
    const z = toZipkinSpan(real);
    expect(z.tags).toMatchObject({
      a: "1",
      "otel.dropped_attributes_count": "1",
      "otel.dropped_events_count": "1",
      "otel.status_code": "OK",
    });
    const bare: ReadableSpan = {
      name: real.name,
      kind: real.kind,
      startTime: real.startTime,
      endTime: real.endTime,
      duration: [0, 0],
      ended: true,
      links: [],
      droppedLinksCount: 0,
      spanContext: () => real.spanContext(),
      resource: resourceFromAttributes({}),
      instrumentationScope: { name: "" },
      attributes: {},
      events: [],
      status: { code: SpanStatusCode.ERROR },
      droppedAttributesCount: 0,
      droppedEventsCount: 0,
      parentSpanContext: { traceId: "0".repeat(32), spanId: "0".repeat(16), traceFlags: 0 },
    };
    const zb = toZipkinSpan(bare);
    expect(zb.localEndpoint).toEqual({ serviceName: UNKNOWN_SERVICE });
    expect(zb.parentId).toBeUndefined();
    expect(zb.annotations).toBeUndefined();
    expect(zb.tags).toEqual({ "otel.status_code": "ERROR", error: "" });
    expect(zb.duration).toBe(1);
    expect(toZipkinSpan({ ...bare, status: { code: SpanStatusCode.UNSET } }).tags).toBeUndefined();
    expect(attributeToTag([true, false])).toBe("[true,false]");
  });
});

function exportOnce(exporter: ZipkinSpanExporter, spans: ReadableSpan[]): Promise<ExportResult> {
  return new Promise((resolve) => {
    exporter.export(spans, resolve);
  });
}

describe("ZipkinSpanExporter", () => {
  let fake: FakeZipkin;

  it("exports gzip JSON to the endpoint and reports SUCCESS", async () => {
    fake = await fakeZipkin();
    fakes.push(fake);
    const exporter = new ZipkinSpanExporter({ config: { url: fake.url } });
    expect(exporter.reporter.compression).toBe("gzip");
    const { memory, tracer } = recorder();
    tracer.startSpan("one").end();
    const result = await exportOnce(exporter, memory.getFinishedSpans());
    expect(result.code).toBe(ExportResultCode.SUCCESS);
    expect(fake.spans.map((s) => s.name)).toEqual(["one"]);
    await exporter.forceFlush();
    await exporter.shutdown();
    await exporter.shutdown();
    const after = await exportOnce(exporter, memory.getFinishedSpans());
    expect(after.code).toBe(ExportResultCode.FAILED);
    expect(after.error?.message).toContain("shut down");
  });

  it("reports FAILED, calls onError and survives throwing hooks/callbacks", async () => {
    const s = await startServer([{ status: 400, body: "Expected a JSON_V2 encoded list" }]);
    fakes.push(s);
    const errors: Error[] = [];
    const exporter = new ZipkinSpanExporter({
      config: new ZipkinConfig({ url: s.url }),
      compression: "none",
      maxBatchSize: 1,
      onError: (e) => {
        errors.push(e);
        throw new Error("hook failure");
      },
    });
    const { memory, tracer } = recorder();
    tracer.startSpan("bad").end();
    const result = await exportOnce(exporter, memory.getFinishedSpans());
    expect(result.code).toBe(ExportResultCode.FAILED);
    expect(errors[0]?.message).toContain("JSON_V2");
    exporter.export(memory.getFinishedSpans(), () => {
      throw new Error("callback failure");
    });
    await exporter.forceFlush();
    await exporter.shutdown();
  });

  it("wraps non-Error failures and keeps a shared reporter open", async () => {
    fake = await fakeZipkin();
    fakes.push(fake);
    const reporter = new ZipkinReporter({ url: fake.url });
    const failing = new ZipkinSpanExporter({ reporter });
    const original = reporter.report.bind(reporter);
    (reporter as { report: unknown }).report = () => Promise.reject(new Error("x"));
    const { memory, tracer } = recorder();
    tracer.startSpan("s").end();
    expect((await exportOnce(failing, memory.getFinishedSpans())).code).toBe(
      ExportResultCode.FAILED,
    );
    // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors
    (reporter as { report: unknown }).report = () => Promise.reject("plain-string");
    const r = await exportOnce(failing, memory.getFinishedSpans());
    expect(r.error?.message).toBe("plain-string");
    (reporter as { report: unknown }).report = original;
    await failing.shutdown();
    await reporter.report([{ traceId: "a".repeat(32), id: "b".repeat(16) }]);
    expect(fake.spans).toHaveLength(1);
    await reporter.close();
  });

  it("defaults to ZipkinConfig.fromEnv()", async () => {
    process.env.GROW_ZIPKIN_URL = "http://127.0.0.1:1";
    try {
      const exporter = new ZipkinSpanExporter();
      expect(exporter.reporter.endpoint).toBe("http://127.0.0.1:1/api/v2/spans");
      await exporter.shutdown();
    } finally {
      delete process.env.GROW_ZIPKIN_URL;
    }
  });
});
