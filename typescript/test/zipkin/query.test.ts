import { afterEach, describe, expect, it } from "vitest";
import {
  DEFAULT_SEARCH_LIMIT,
  DEFAULT_SEARCH_WINDOW_MS,
  NotFoundError,
  TRACE_MANY_CHUNK,
  ValidationError,
  ZipkinClient,
  ZipkinQueryClient,
  buildAnnotationQuery,
  type ZipkinSpan,
} from "../../src/index.js";
import { fixtureBody } from "../helpers/fixtures.js";
import type { MockServer } from "../helpers/server.js";
import { params, zipkinServer, type Route } from "../helpers/zipkin.js";

const servers: MockServer[] = [];
const clients: { close(): Promise<void> }[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

const NOW = 1_795_035_940_000;
const TRACE = "5af7183fb1d4cf5fad1c0df61d0a9b8c";
const TRACE64 = "463ac35cad1c0df6";

async function setup(
  routes: Record<string, Route>,
): Promise<{ server: MockServer; query: ZipkinQueryClient }> {
  const server = await zipkinServer(routes);
  servers.push(server);
  const query = new ZipkinQueryClient(
    { url: server.url, retry: { maxRetries: 1, initialBackoffMs: 1 } },
    { now: () => NOW },
  );
  clients.push(query);
  return { server, query };
}

describe("getTraceSpans / getTrace", () => {
  it("returns native spans and the normalized trace", async () => {
    const { server, query } = await setup({ [`/api/v2/trace/${TRACE}`]: "getTrace.ok" });
    const raw = await query.getTraceSpans(TRACE.toUpperCase());
    expect(raw).toEqual(fixtureBody("getTrace.ok"));
    const trace = await query.getTrace(TRACE);
    expect(trace?.traceId).toBe(TRACE);
    expect(trace?.spans.map((s) => s.name)).toEqual([
      "get /api/orders",
      "get /orders",
      "get /orders",
    ]);
    const root = trace?.spans[0];
    expect(root).toMatchObject({
      spanId: "0000000000000001",
      kind: "server",
      serviceName: "grow-frontend",
      startMicros: 1795035874000000,
      endMicros: 1795035874150000,
      durationMicros: 150000,
      status: { code: "unset" },
      events: [{ name: "cache.miss", timeMicros: 1795035874001000, attributes: {} }],
      links: [],
    });
    expect(trace?.spans[2]?.status).toEqual({ code: "error", message: "boom" });
    expect(trace?.spans[2]?.parentSpanId).toBe("0000000000000002");
    expect(server.requests.map((r) => r.url)).toEqual([
      `/api/v2/trace/${TRACE}`,
      `/api/v2/trace/${TRACE}`,
    ]);
  });

  it("pads a 64-bit trace id in the normalized model", async () => {
    const { query } = await setup({ [`/api/v2/trace/${TRACE64}`]: "getTrace.id64" });
    const trace = await query.getTrace(TRACE64);
    expect(trace?.traceId).toBe(`0000000000000000${TRACE64}`);
    expect(trace?.spans[0]?.kind).toBe("internal");
  });

  it("returns null for 404 and an empty body", async () => {
    const { query } = await setup({
      "/api/v2/trace/ffffffffffffffffffffffffffffffff": "getTrace.notFound",
      "/api/v2/trace/eeeeeeeeeeeeeeee": { status: 200, body: [] },
    });
    expect(await query.getTraceSpans("ffffffffffffffffffffffffffffffff")).toBeNull();
    expect(await query.getTrace("ffffffffffffffffffffffffffffffff")).toBeNull();
    expect(await query.getTrace("eeeeeeeeeeeeeeee")).toBeNull();
  });

  it("rejects invalid ids locally and maps a server 400", async () => {
    const { server, query } = await setup({
      "/api/v2/trace/abababababababab": "getTrace.invalid400",
    });
    for (const bad of ["zz", "0000000000000000", "abc", ""]) {
      await expect(query.getTrace(bad)).rejects.toBeInstanceOf(ValidationError);
    }
    expect(server.requests).toHaveLength(0);
    const err = await query.getTrace("abababababababab").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect((err as Error).message).toContain("lower-hex");
  });

  it("waitForTrace polls until the trace exists", async () => {
    let n = 0;
    const { query } = await setup({
      [`/api/v2/trace/${TRACE}`]: () => (++n < 2 ? "getTrace.notFound" : "getTrace.ok"),
    });
    const trace = await query.waitForTrace(TRACE, { intervalMs: 5, minSpans: 3 });
    expect(trace.spans).toHaveLength(3);
  });
});

describe("getTraces", () => {
  it("sends every filter and returns native traces", async () => {
    const { server, query } = await setup({ "/api/v2/traces": "getTraces.allFilters" });
    const traces = await query.getTraces({
      serviceName: "grow-frontend",
      spanName: "get /api/orders",
      tags: { "http.method": "GET" },
      annotations: ["cache.miss"],
      minDurationMicros: 100000,
      maxDurationMicros: 200000,
      endTs: new Date(NOW),
      lookbackMs: 3600000,
      limit: 5,
    });
    expect(traces).toEqual(fixtureBody("getTraces.allFilters"));
    const p = params(server.requests[0]);
    expect(Object.fromEntries(p)).toEqual({
      serviceName: "grow-frontend",
      spanName: "get /api/orders",
      annotationQuery: "http.method=GET and cache.miss",
      minDuration: "100000",
      maxDuration: "200000",
      endTs: String(NOW),
      lookback: "3600000",
      limit: "5",
    });
  });

  it("combines a raw annotationQuery, remoteServiceName and omits blanks", async () => {
    const { server, query } = await setup({ "/api/v2/traces": "getTraces.remoteServiceName" });
    await query.getTraces({
      serviceName: " ",
      remoteServiceName: "grow-backend",
      spanName: "",
      annotationQuery: " error ",
      tags: { "grow.test": "run-a" },
      endTs: NOW,
    });
    expect(Object.fromEntries(params(server.requests[0]))).toEqual({
      remoteServiceName: "grow-backend",
      annotationQuery: "error and grow.test=run-a",
      endTs: String(NOW),
    });
    await query.getTraces({ annotationQuery: "" });
    expect(server.requests[1]?.url).toBe("/api/v2/traces");
    await query.getTraces();
  });

  it("matches terms Zipkin cannot express client-side", async () => {
    const traceA: ZipkinSpan[] = [
      { traceId: "a".repeat(32), id: "1".repeat(16), tags: { note: "salt and pepper" } },
    ];
    const traceB: ZipkinSpan[] = [
      { traceId: "b".repeat(32), id: "2".repeat(16), tags: { note: "salt" } },
      {
        traceId: "b".repeat(32),
        id: "3".repeat(16),
        annotations: [{ timestamp: 1, value: "rock and roll" }],
      },
    ];
    const { server, query } = await setup({
      "/api/v2/traces": { status: 200, body: [traceA, traceB] },
    });
    const byValue = await query.getTraces({ tags: { note: "salt and pepper", x: "1" } });
    expect(params(server.requests[0]).get("annotationQuery")).toBe("x=1");
    expect(byValue).toEqual([traceA]);
    const byAnnotation = await query.getTraces({ annotations: ["rock and roll"] });
    expect(params(server.requests[1]).has("annotationQuery")).toBe(false);
    expect(byAnnotation).toEqual([traceB]);
    const byKey = await query.getTraces({ annotations: ["a=b and c"] });
    expect(byKey).toEqual([]);
    const tagKey = await query.getTraces({ tags: { "k=v": "x" } });
    expect(tagKey).toEqual([]);
  });

  it("validates filters locally", async () => {
    const { server, query } = await setup({ "/api/v2/traces": "getTraces.defaults" });
    const bad = [
      { limit: 0 },
      { limit: 1.5 },
      { lookbackMs: 0 },
      { lookbackMs: Number.NaN },
      { endTs: -1 },
      { endTs: new Date(Number.NaN) },
      { maxDurationMicros: 5 },
      { minDurationMicros: 10, maxDurationMicros: 5 },
      { minDurationMicros: -1 },
      { tags: { "": "x" } },
      { annotations: [""] },
    ];
    for (const q of bad) {
      await expect(query.getTraces(q), JSON.stringify(q)).rejects.toBeInstanceOf(ValidationError);
    }
    expect(server.requests).toHaveLength(0);
  });

  it("maps server 400 text to ValidationError", async () => {
    const { query } = await setup({ "/api/v2/traces": "getTraces.limitZero400" });
    const err = await query.getTraces({ limit: 3 }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect((err as Error).message).toBe("zipkin.getTraces: limit <= 0 (HTTP 400)");
  });
});

describe("getTraceMany", () => {
  it("dedupes, keeps request order and omits missing traces", async () => {
    const body = fixtureBody("getTraceMany.ok") as ZipkinSpan[][];
    const { server, query } = await setup({
      "/api/v2/traceMany": { status: 200, body: [...body].reverse() },
    });
    const ids = [TRACE64, TRACE.toUpperCase(), `0000000000000000${TRACE64}`, "f".repeat(32), TRACE];
    const traces = await query.getTraceMany(ids);
    expect(traces.map((t) => t[0]?.traceId)).toEqual([TRACE64, TRACE]);
    expect(params(server.requests[0]).get("traceIds")).toBe(
      `${TRACE64},${TRACE},${"f".repeat(32)}`,
    );
  });

  it("empty list sends nothing; one id uses /trace/{id}", async () => {
    const { server, query } = await setup({
      [`/api/v2/trace/${TRACE}`]: "getTrace.ok",
      "/api/v2/trace/ffffffffffffffffffffffffffffffff": "getTrace.notFound",
    });
    expect(await query.getTraceMany([])).toEqual([]);
    expect(server.requests).toHaveLength(0);
    expect(await query.getTraceMany([TRACE, TRACE])).toHaveLength(1);
    expect(await query.getTraceMany(["f".repeat(32)])).toEqual([]);
    expect(server.requests.map((r) => new URL(r.url, "http://x").pathname)).toEqual([
      `/api/v2/trace/${TRACE}`,
      "/api/v2/trace/ffffffffffffffffffffffffffffffff",
    ]);
    await expect(query.getTraceMany(["nope"])).rejects.toBeInstanceOf(ValidationError);
  });

  it("splits more than TRACE_MANY_CHUNK ids (a single leftover uses /trace/{id})", async () => {
    const ids = Array.from({ length: TRACE_MANY_CHUNK + 1 }, (_, i) =>
      (i + 1).toString(16).padStart(32, "0"),
    );
    const last = ids[ids.length - 1] as string;
    const { server, query } = await setup({
      "/api/v2/traceMany": "getTraceMany.allMissing",
      [`/api/v2/trace/${last}`]: {
        status: 200,
        body: [{ traceId: last, id: "1".repeat(16), name: "x" }],
      },
    });
    const traces = await query.getTraceMany(ids);
    expect(traces).toHaveLength(1);
    expect(params(server.requests[0]).get("traceIds")?.split(",")).toHaveLength(TRACE_MANY_CHUNK);
    expect(server.requests[1]?.url).toBe(`/api/v2/trace/${last}`);
  });
});

describe("searchTraces", () => {
  it("maps the SPEC query and summarises traces", async () => {
    const { server, query } = await setup({ "/api/v2/traces": "getTraces.andQuery" });
    const summaries = await query.searchTraces({
      service: "grow-backend",
      operation: "get /orders",
      tags: { "grow.test": "run-a" },
      minDurationMicros: 1,
      maxDurationMicros: 999999,
      start: new Date(NOW - 60_000),
      end: new Date(NOW),
    });
    expect(summaries).toEqual([
      {
        traceId: TRACE,
        rootServiceName: "grow-frontend",
        rootName: "get /api/orders",
        startMicros: 1795035874000000,
        durationMicros: 150000,
        spanCount: 3,
      },
    ]);
    expect(Object.fromEntries(params(server.requests[0]))).toEqual({
      serviceName: "grow-backend",
      spanName: "get /orders",
      annotationQuery: "grow.test=run-a",
      minDuration: "1",
      maxDuration: "999999",
      endTs: String(NOW),
      lookback: "60000",
      limit: String(DEFAULT_SEARCH_LIMIT),
    });
  });

  it("defaults to the last hour without start and validates the window", async () => {
    const { server, query } = await setup({ "/api/v2/traces": "getTraces.defaults" });
    await query.searchTraces({}, { signal: new AbortController().signal });
    expect(Object.fromEntries(params(server.requests[0]))).toEqual({
      endTs: String(NOW),
      lookback: String(DEFAULT_SEARCH_WINDOW_MS),
      limit: "20",
    });
    expect(DEFAULT_SEARCH_WINDOW_MS).toBe(3_600_000);
    await query.searchTraces();
    await expect(
      query.searchTraces({ start: new Date(NOW + 1), end: new Date(NOW) }),
    ).rejects.toBeInstanceOf(ValidationError);
    await expect(query.searchTraces({ start: new Date(Number.NaN) })).rejects.toBeInstanceOf(
      ValidationError,
    );
  });

  it("falls back to the earliest span when every span has a parent in the trace", async () => {
    const cyclic: ZipkinSpan[] = [
      { traceId: TRACE, id: "1".repeat(16), parentId: "2".repeat(16), name: "b", timestamp: 2 },
      { traceId: TRACE, id: "2".repeat(16), parentId: "1".repeat(16), name: "a", timestamp: 1 },
    ];
    const { query } = await setup({ "/api/v2/traces": { status: 200, body: [cyclic, []] } });
    const [summary] = await query.searchTraces();
    expect(summary).toMatchObject({ rootName: "a", rootServiceName: "", spanCount: 2 });
  });
});

describe("names, dependencies and autocomplete", () => {
  it("listServices / listSpans / listOperations / listRemoteServices", async () => {
    const { server, query } = await setup({
      "/api/v2/services": "listServices.ok",
      "/api/v2/spans": (_req, url) =>
        url.searchParams.get("serviceName") === "nobody"
          ? "listSpans.unknownService"
          : "listSpans.ok",
      "/api/v2/remoteServices": "listRemoteServices.ok",
    });
    expect(await query.listServices()).toEqual(fixtureBody("listServices.ok"));
    expect(await query.listSpans("grow-frontend")).toEqual(fixtureBody("listSpans.ok"));
    expect(await query.listOperations("grow-frontend")).toEqual(fixtureBody("listSpans.ok"));
    expect(await query.listSpans("nobody")).toEqual([]);
    expect(await query.listRemoteServices("grow-frontend")).toEqual(
      fixtureBody("listRemoteServices.ok"),
    );
    expect(params(server.requests[1]).get("serviceName")).toBe("grow-frontend");
    await expect(query.listSpans(" ")).rejects.toBeInstanceOf(ValidationError);
    await expect(query.listRemoteServices("")).rejects.toBeInstanceOf(ValidationError);
  });

  it("server 400 for a missing parameter and odd bodies", async () => {
    const { query } = await setup({
      "/api/v2/spans": "listSpans.missing400",
      "/api/v2/services": { status: 200, body: { not: "a list" } },
      "/api/v2/remoteServices": "listRemoteServices.missing400",
    });
    await expect(query.listSpans("x")).rejects.toBeInstanceOf(ValidationError);
    await expect(query.listRemoteServices("x")).rejects.toBeInstanceOf(ValidationError);
    expect(await query.listServices()).toEqual([]);
  });

  it("getDependencies: native window, category window and errorCount", async () => {
    const { server, query } = await setup({
      "/api/v2/dependencies": (_req, url) =>
        url.searchParams.get("lookback") === "1"
          ? "getDependencies.emptyWindow"
          : "getDependencies.ok",
    });
    const links = await query.getDependencies({ endTs: NOW, lookbackMs: 3600000 });
    expect(links).toEqual([
      { parent: "grow-frontend", child: "grow-backend", callCount: 1, errorCount: 1 },
      { parent: "grow-mixed", child: "kafka-broker", callCount: 1, errorCount: 0 },
    ]);
    await query.getDependencies({ start: new Date(NOW - 120_000), end: new Date(NOW) });
    expect(Object.fromEntries(params(server.requests[1]))).toEqual({
      endTs: String(NOW),
      lookback: "120000",
    });
    await query.getDependencies();
    expect(Object.fromEntries(params(server.requests[2]))).toEqual({ endTs: String(NOW) });
    expect(await query.getDependencies({ endTs: NOW, lookbackMs: 1 })).toEqual([]);
    await expect(query.getDependencies({ lookbackMs: 0 })).rejects.toBeInstanceOf(ValidationError);
    await expect(
      query.getDependencies({ start: new Date(NOW + 5), end: new Date(NOW) }),
    ).rejects.toBeInstanceOf(ValidationError);
  });

  it("getDependencies decodes defensively", async () => {
    const { query } = await setup({
      "/api/v2/dependencies": {
        status: 200,
        body: [
          { parent: "b", child: "a" },
          { parent: "a", child: "c" },
          { parent: "a", child: "b" },
          "x",
        ],
      },
    });
    expect(await query.getDependencies()).toEqual([
      { parent: "a", child: "b", callCount: 0, errorCount: 0 },
      { parent: "a", child: "c", callCount: 0, errorCount: 0 },
      { parent: "b", child: "a", callCount: 0, errorCount: 0 },
    ]);
    const { query: q2 } = await setup({ "/api/v2/dependencies": { status: 200, body: {} } });
    expect(await q2.getDependencies()).toEqual([]);
  });

  it("autocompleteKeys / autocompleteValues", async () => {
    const { server, query } = await setup({
      "/api/v2/autocompleteKeys": "autocompleteKeys.ok",
      "/api/v2/autocompleteValues": (_req, url) =>
        url.searchParams.get("key") === "grow.test"
          ? "autocompleteValues.ok"
          : "autocompleteValues.notAllowlisted",
    });
    expect(await query.autocompleteKeys()).toEqual(["grow.test", "http.method"]);
    expect(await query.autocompleteValues("grow.test")).toEqual(
      fixtureBody("autocompleteValues.ok"),
    );
    expect(await query.autocompleteValues("other")).toEqual([]);
    expect(params(server.requests[1]).get("key")).toBe("grow.test");
    await expect(query.autocompleteValues("")).rejects.toBeInstanceOf(ValidationError);
  });

  it("an unknown route is a NotFoundError", async () => {
    const { query } = await setup({});
    await expect(query.listServices()).rejects.toBeInstanceOf(NotFoundError);
  });
});

describe("construction", () => {
  it("shares the client's transport; close is a no-op then", async () => {
    const server = await zipkinServer({ "/api/v2/services": "listServices.ok" });
    servers.push(server);
    const client = new ZipkinClient({ url: server.url });
    const query = new ZipkinQueryClient(client);
    await query.close();
    expect(await query.listServices()).toHaveLength(9);
    await client.close();
  });

  it("fromEnv builds an owned client", async () => {
    const q = ZipkinQueryClient.fromEnv({ url: "http://127.0.0.1:1" });
    await q.close();
    await q.close();
    await expect(q.listServices()).rejects.toMatchObject({
      code: "CANCELLED",
      message: "zipkin.listServices: client is closed",
    });
  });
});

describe("buildAnnotationQuery", () => {
  it("joins terms with ' and ' and rejects inexpressible ones", () => {
    expect(buildAnnotationQuery({ "http.method": "GET" }, ["error"])).toBe(
      "http.method=GET and error",
    );
    expect(buildAnnotationQuery()).toBeUndefined();
    expect(() => buildAnnotationQuery({ a: "x and y" })).toThrow(ValidationError);
    expect(() => buildAnnotationQuery({ "a=b": "x" })).toThrow(ValidationError);
    expect(() => buildAnnotationQuery({}, [""])).toThrow(ValidationError);
  });
});
