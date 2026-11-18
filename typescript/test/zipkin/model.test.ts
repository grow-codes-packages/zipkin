import { describe, expect, it } from "vitest";
import {
  ValidationError,
  annotationToEvent,
  dependenciesFromBody,
  normalizeZipkinSpan,
  stringsFromBody,
  summarizeZipkinTrace,
  tagValue,
  zipkinSpanToSpan,
  zipkinSpansFromBody,
  zipkinTraceId,
  zipkinTraceToTrace,
  zipkinTracesFromBody,
  type ZipkinSpanInput,
} from "../../src/index.js";
import { fixtureBody } from "../helpers/fixtures.js";

const T = "5af7183fb1d4cf5fad1c0df61d0a9b8c";
const S = "0000000000000001";

describe("normalizeZipkinSpan", () => {
  it("lower-cases ids, upper-cases kind, stringifies tags and drops empty fields", () => {
    const out = normalizeZipkinSpan({
      traceId: T.toUpperCase(),
      id: "00000000000000AB",
      parentId: S,
      kind: "client",
      name: "Get /Orders",
      timestamp: 1795035874005000.25,
      duration: 100000,
      localEndpoint: { serviceName: "Grow-Frontend", ipv4: "10.0.0.1", ipv6: "::1", port: 8080 },
      remoteEndpoint: { serviceName: "", port: 0 },
      annotations: [{ timestamp: 1795035874006000, value: "cache.miss" }],
      tags: { s: "x", n: 7, f: 1.5, b: true, nil: null, u: undefined },
      debug: true,
      shared: true,
    });
    expect(out).toEqual({
      traceId: T,
      id: "00000000000000ab",
      parentId: S,
      kind: "CLIENT",
      name: "Get /Orders",
      timestamp: 1795035874005000,
      duration: 100000,
      localEndpoint: { serviceName: "Grow-Frontend", ipv4: "10.0.0.1", ipv6: "::1", port: 8080 },
      annotations: [{ timestamp: 1795035874006000, value: "cache.miss" }],
      tags: { s: "x", n: "7", f: "1.5", b: "true" },
      debug: true,
      shared: true,
    });
    expect(
      normalizeZipkinSpan({ traceId: "463ac35cad1c0df6", id: S, parentId: "", name: "", tags: {} }),
    ).toEqual({ traceId: "463ac35cad1c0df6", id: S });
    expect(normalizeZipkinSpan({ traceId: T, id: S, timestamp: 0, duration: 0 })).toEqual({
      traceId: T,
      id: S,
    });
  });

  it("rejects what Zipkin would reject", () => {
    const bad: unknown[] = [
      null,
      { traceId: "zz", id: S },
      { traceId: "abc", id: S },
      { traceId: "0".repeat(32), id: S },
      { traceId: 5, id: S },
      { traceId: T, id: "0000000000000000" },
      { traceId: T, id: 12 },
      { traceId: T, id: S, parentId: "x" },
      { traceId: T, id: S, kind: "INTERNAL" },
      { traceId: T, id: S, timestamp: -1 },
      { traceId: T, id: S, duration: Number.NaN },
      { traceId: T, id: S, localEndpoint: { port: 70000 } },
      { traceId: T, id: S, remoteEndpoint: { port: 1.5 } },
      { traceId: T, id: S, annotations: [{ timestamp: 1, value: "" }] },
      { traceId: T, id: S, annotations: [{ value: "x" }] },
    ];
    for (const span of bad) {
      expect(() => normalizeZipkinSpan(span as ZipkinSpanInput), JSON.stringify(span)).toThrow(
        ValidationError,
      );
    }
  });

  it("tagValue and zipkinTraceId", () => {
    expect(tagValue(["a", 1])).toBe('["a",1]');
    expect(tagValue({ k: 1 })).toBe('{"k":1}');
    expect(tagValue(10n)).toBe("10");
    expect(zipkinTraceId(` ${T.toUpperCase()} `)).toBe(T);
    expect(() => zipkinTraceId("12", "op")).toThrow(/^op: trace id/);
  });
});

describe("decoding", () => {
  it("filters malformed entries", () => {
    expect(zipkinSpansFromBody({})).toEqual([]);
    expect(zipkinSpansFromBody([{ traceId: T, id: S }, { id: S }, 1])).toEqual([
      { traceId: T, id: S },
    ]);
    expect(zipkinTracesFromBody("x")).toEqual([]);
    expect(zipkinTracesFromBody([[{ traceId: T, id: S }], [], "x"])).toHaveLength(1);
    expect(stringsFromBody(["a", 1, "b"])).toEqual(["a", "b"]);
    expect(stringsFromBody(null)).toEqual([]);
    expect(dependenciesFromBody(fixtureBody("getDependencies.ok"))).toHaveLength(2);
    expect(dependenciesFromBody(null)).toEqual([]);
  });
});

describe("zipkinSpanToSpan", () => {
  it("normalizes kinds, status and endpoints", () => {
    const span = zipkinSpanToSpan({
      traceId: "463ac35cad1c0df6",
      id: "00000000000000AA",
      parentId: "00000000000000BB",
      kind: "PRODUCER",
      localEndpoint: { serviceName: "svc" },
      remoteEndpoint: { serviceName: "kafka" },
      tags: { "otel.status_code": "ERROR" },
      shared: true,
      debug: true,
    });
    expect(span).toMatchObject({
      traceId: "0000000000000000463ac35cad1c0df6",
      spanId: "00000000000000aa",
      parentSpanId: "00000000000000bb",
      name: "",
      kind: "producer",
      serviceName: "svc",
      startMicros: 0,
      endMicros: 0,
      status: { code: "error" },
      remoteEndpoint: { serviceName: "kafka" },
      shared: true,
      debug: true,
    });
    expect(zipkinSpanToSpan({ traceId: T, id: S, tags: { error: "" } }).status).toEqual({
      code: "error",
    });
    expect(
      zipkinSpanToSpan({ traceId: T, id: S, tags: { "otel.status_code": "ok" } }).status,
    ).toEqual({ code: "ok" });
    expect(zipkinSpanToSpan({ traceId: T, id: S, kind: "WEIRD" as "CLIENT" }).kind).toBe(
      "unspecified",
    );
    expect(zipkinSpanToSpan({ traceId: T, id: S }).serviceName).toBe("");
  });

  it("decodes events with attributes from the exporter's JSON form", () => {
    expect(annotationToEvent({ timestamp: 5, value: '{"cache.miss":{"key":"k1"}}' })).toEqual({
      name: "cache.miss",
      timeMicros: 5,
      attributes: { key: "k1" },
    });
    for (const value of ["{not json}", '{"a":1}', '{"a":{},"b":{}}', "plain", "{}"]) {
      expect(annotationToEvent({ timestamp: 1, value })).toEqual({
        name: value,
        timeMicros: 1,
        attributes: {},
      });
    }
  });

  it("converts and summarises a trace", () => {
    expect(zipkinTraceToTrace([])).toBeNull();
    expect(summarizeZipkinTrace([])).toBeNull();
    const official = fixtureBody("otelJs.zipkinExporter") as Parameters<
      typeof zipkinTraceToTrace
    >[0];
    const trace = zipkinTraceToTrace(official);
    expect(trace?.spans.map((s) => [s.name, s.kind, s.status.code])).toEqual([
      ["js-child", "client", "error"],
      ["js-parent", "server", "unset"],
    ]);
    expect(summarizeZipkinTrace(official)).toMatchObject({
      rootName: "js-parent",
      rootServiceName: "grow-js-otel",
      spanCount: 2,
    });
  });
});
