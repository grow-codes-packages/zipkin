import { ROOT_CONTEXT, trace } from "@opentelemetry/api";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  ZIPKIN_DEFAULT_PROPAGATORS,
  ZIPKIN_DEFAULT_PROTOCOL,
  createZipkinExporterFactory,
  initTracing,
  orderPropagators,
  resolveOtlpEndpoint,
  resolveTracingConfig,
  tracingConfigFromEnv,
  type ZipkinTracingHandle,
} from "../../src/index.js";
import { startServer } from "../helpers/server.js";
import { fakeZipkin } from "../helpers/zipkin.js";

const cleanup: { close(): Promise<void> }[] = [];
const handles: ZipkinTracingHandle[] = [];
afterEach(async () => {
  await Promise.allSettled(handles.splice(0).map((h) => h.shutdown()));
  await Promise.all(cleanup.splice(0).map((c) => c.close()));
});

const setter = {
  set: (c: Record<string, string>, k: string, v: string) => {
    c[k] = v;
  },
};
const getter = {
  get: (c: Record<string, string>, k: string) => c[k],
  keys: (c: Record<string, string>) => Object.keys(c),
};

describe("orderPropagators", () => {
  it("puts B3 first so W3C wins, dedupes and lower-cases", () => {
    expect(orderPropagators(ZIPKIN_DEFAULT_PROPAGATORS)).toEqual(["b3", "tracecontext", "baggage"]);
    expect(orderPropagators([" TraceContext ", "b3multi", "b3", "b3"])).toEqual([
      "b3multi",
      "b3",
      "tracecontext",
    ]);
  });
});

describe("tracingConfigFromEnv", () => {
  it("reads every supported variable", () => {
    const cfg = tracingConfigFromEnv(
      {},
      {
        GROW_ZIPKIN_ENDPOINT: "http://grow:9411/api/v2/spans",
        OTEL_EXPORTER_ZIPKIN_ENDPOINT: "http://otel:9411/api/v2/spans",
        OTEL_EXPORTER_ZIPKIN_TIMEOUT: "10s",
        GROW_ZIPKIN_COMPRESSION: "NONE",
        OTEL_RESOURCE_ATTRIBUTES: "service.name=from-res,team=sdk%20team,,",
        OTEL_TRACES_SAMPLER: "parentbased_traceidratio",
        OTEL_TRACES_SAMPLER_ARG: "0.25",
        OTEL_PROPAGATORS: "tracecontext, b3multi,",
      },
    );
    expect(cfg).toEqual({
      serviceName: "from-res",
      endpoint: "http://grow:9411/api/v2/spans",
      batch: { exportTimeoutMs: 10_000 },
      compression: "none",
      resourceAttributes: { team: "sdk team" },
      sampler: "parentbased_traceidratio",
      samplerRatio: 0.25,
      propagators: ["tracecontext", "b3multi"],
    });
  });

  it("endpoint precedence for native and OTLP", () => {
    expect(
      tracingConfigFromEnv({}, { OTEL_EXPORTER_ZIPKIN_ENDPOINT: "http://otel/api/v2/spans" })
        .endpoint,
    ).toBe("http://otel/api/v2/spans");
    expect(tracingConfigFromEnv({}, { ZIPKIN_URL: "http://z:9411/" }).endpoint).toBe(
      "http://z:9411/api/v2/spans",
    );
    expect(tracingConfigFromEnv({}, {}).endpoint).toBeUndefined();
    const otlp = { OTEL_EXPORTER_OTLP_ENDPOINT: "http://col:4318" };
    expect(tracingConfigFromEnv({ protocol: "http/protobuf" }, otlp).endpoint).toBe(
      "http://col:4318/v1/traces",
    );
    expect(tracingConfigFromEnv({ protocol: "grpc" }, otlp).endpoint).toBe("http://col:4318");
    expect(
      tracingConfigFromEnv(
        {},
        { GROW_ZIPKIN_PROTOCOL: "GRPC", OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "http://col:4317" },
      ),
    ).toMatchObject({ protocol: "grpc", endpoint: "http://col:4317" });
    expect(tracingConfigFromEnv({}, { OTEL_SERVICE_NAME: "svc" }).serviceName).toBe("svc");
    expect(tracingConfigFromEnv({}, { OTEL_TRACES_SAMPLER: "always_off" })).toMatchObject({
      sampler: "always_off",
    });
  });

  it("explicit values win and batch settings merge", () => {
    const cfg = tracingConfigFromEnv(
      { serviceName: "explicit", batch: { maxQueueSize: 10, scheduledDelayMs: undefined } },
      { OTEL_SERVICE_NAME: "env", OTEL_EXPORTER_ZIPKIN_TIMEOUT: "500" },
    );
    expect(cfg.serviceName).toBe("explicit");
    expect(cfg.batch).toEqual({ exportTimeoutMs: 500, maxQueueSize: 10 });
  });

  it("invalid values throw ConfigError naming the variable only", () => {
    const cases: Record<string, string>[] = [
      { GROW_ZIPKIN_PROTOCOL: "secret-proto" },
      { OTEL_EXPORTER_ZIPKIN_TIMEOUT: "secret-forever" },
      { OTEL_EXPORTER_ZIPKIN_TIMEOUT: "0" },
      { GROW_ZIPKIN_COMPRESSION: "secret-br" },
      { OTEL_RESOURCE_ATTRIBUTES: "secret-novalue" },
      { OTEL_RESOURCE_ATTRIBUTES: "k=%E0%A4%secret" },
      { OTEL_TRACES_SAMPLER: "secret-sampler" },
      { OTEL_TRACES_SAMPLER: "traceidratio", OTEL_TRACES_SAMPLER_ARG: "secret-2" },
    ];
    for (const env of cases) {
      let err: unknown;
      try {
        tracingConfigFromEnv({}, env);
      } catch (e) {
        err = e;
      }
      expect(err, JSON.stringify(env)).toBeInstanceOf(ConfigError);
      expect((err as Error).message).toMatch(/environment variable [A-Z_]+/);
      expect((err as Error).message).not.toContain("secret");
    }
  });

  it("resolveOtlpEndpoint", () => {
    expect(resolveOtlpEndpoint("http/protobuf", "http://c:4318/")).toBe("http://c:4318/v1/traces");
    expect(resolveOtlpEndpoint("http/protobuf", "http://c:4318/custom")).toBe(
      "http://c:4318/custom",
    );
    expect(resolveOtlpEndpoint("http/protobuf", "not a url")).toBe("not a url");
    expect(resolveOtlpEndpoint("grpc", "http://c:4317")).toBe("http://c:4317");
  });
});

describe("initTracing (native)", () => {
  it("exports a span tree to Zipkin with the preset defaults", async () => {
    const fake = await fakeZipkin();
    cleanup.push(fake);
    const errors: Error[] = [];
    const handle = initTracing(
      { serviceName: "Grow-Setup", serviceVersion: "1.0.0", setGlobal: false },
      { env: false, clientConfig: { url: fake.url }, onExportError: (e) => errors.push(e) },
    );
    handles.push(handle);
    expect(ZIPKIN_DEFAULT_PROTOCOL).toBe("native");
    expect(handle.config.protocol).toBe("native");
    expect(handle.endpoint).toBe(`${fake.url}/api/v2/spans`);
    expect(handle.config.propagators).toEqual(["b3", "tracecontext", "baggage"]);
    const tracer = handle.tracer("setup-test", "0.1");
    const root = tracer.startSpan("root");
    tracer.startSpan("child", {}, trace.setSpan(ROOT_CONTEXT, root)).end();
    root.end();
    await handle.forceFlush();
    expect(fake.bodies).toHaveLength(1);
    expect(fake.spans.map((s) => s.name).sort()).toEqual(["child", "root"]);
    expect(fake.spans[0]?.localEndpoint?.serviceName).toBe("grow-setup");
    expect(fake.spans[0]?.tags?.["service.version"]).toBeUndefined();
    expect(errors).toEqual([]);
  });

  it("injects W3C + B3 single and lets traceparent win on extract", () => {
    const handle = initTracing({ serviceName: "prop", setGlobal: false }, { env: false });
    handles.push(handle);
    const span = handle.tracer("t").startSpan("s");
    const carrier: Record<string, string> = {};
    handle.propagator.inject(trace.setSpan(ROOT_CONTEXT, span), carrier, setter);
    span.end();
    const { traceId, spanId } = span.spanContext();
    expect(carrier.traceparent).toBe(`00-${traceId}-${spanId}-01`);
    expect(carrier.b3).toBe(`${traceId}-${spanId}-1`);
    expect(carrier["x-b3-traceid"]).toBeUndefined();

    const w3cTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
    const b3Trace = "80f198ee56343ba864fe8b2a57d3eff7";
    const ctx = handle.propagator.extract(
      ROOT_CONTEXT,
      {
        traceparent: `00-${w3cTrace}-00f067aa0ba902b7-01`,
        b3: `${b3Trace}-e457b5a2e4d86bd1-1`,
      },
      getter,
    );
    expect(trace.getSpanContext(ctx)?.traceId).toBe(w3cTrace);
    const b3Only = handle.propagator.extract(
      ROOT_CONTEXT,
      { "x-b3-traceid": b3Trace, "x-b3-spanid": "e457b5a2e4d86bd1", "x-b3-sampled": "1" },
      getter,
    );
    expect(trace.getSpanContext(b3Only)?.traceId).toBe(b3Trace);
  });

  it("b3multi injects the X-B3 headers; env fills unset fields", () => {
    const handle = initTracing(
      { serviceName: "", propagators: ["b3multi", "tracecontext"], setGlobal: false },
      { env: { OTEL_SERVICE_NAME: "from-env", ZIPKIN_URL: "http://127.0.0.1:9" } },
    );
    handles.push(handle);
    expect(handle.config.serviceName).toBe("from-env");
    expect(handle.endpoint).toBe("http://127.0.0.1:9/api/v2/spans");
    const span = handle.tracer("t").startSpan("s");
    const carrier: Record<string, string> = {};
    handle.propagator.inject(trace.setSpan(ROOT_CONTEXT, span), carrier, setter);
    span.end();
    expect(carrier["x-b3-traceid"]).toBe(span.spanContext().traceId);
    expect(carrier.traceparent).toBeDefined();
  });

  it("reads process.env by default", () => {
    process.env.GROW_ZIPKIN_URL = "http://127.0.0.1:8";
    try {
      const handle = initTracing({ serviceName: "penv", setGlobal: false });
      handles.push(handle);
      expect(handle.endpoint).toBe("http://127.0.0.1:8/api/v2/spans");
    } finally {
      delete process.env.GROW_ZIPKIN_URL;
    }
  });

  it("native factory merges client connection settings", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const factory = createZipkinExporterFactory({
      clientConfig: {
        url: "http://127.0.0.1:7",
        proxy: "http://proxy:3128",
        userAgentSuffix: "app/1",
        hooks,
        logger,
        headers: { authorization: "Bearer a" },
      },
    });
    const resolved = resolveTracingConfig({
      serviceName: "f",
      protocol: "native",
      compression: "none",
      headers: { "x-extra": "1" },
    });
    const exporter = factory(resolved) as unknown as {
      reporter: { endpoint: string; compression: string };
      shutdown(): Promise<void>;
    };
    expect(exporter.reporter.endpoint).toBe("http://127.0.0.1:7/api/v2/spans");
    expect(exporter.reporter.compression).toBe("none");
    void exporter.shutdown();
    const plain = createZipkinExporterFactory()(
      resolveTracingConfig({ serviceName: "g", protocol: "native" }),
    );
    void plain.shutdown();
  });
});

describe("initTracing (OTLP to a Collector)", () => {
  it("requires a Collector endpoint", () => {
    expect(() =>
      initTracing(
        { serviceName: "o", protocol: "http/protobuf", setGlobal: false },
        { env: false },
      ),
    ).toThrow(ConfigError);
    expect(() =>
      initTracing(
        {
          serviceName: "o",
          protocol: "grpc",
          endpoint: "http://zipkin:9411/api/v2/spans",
          setGlobal: false,
        },
        { env: false },
      ),
    ).toThrow(/OpenTelemetry Collector/);
  });

  it("http/protobuf posts OTLP protobuf to /v1/traces", async () => {
    const collector = await startServer([{ status: 200, body: "" }]);
    cleanup.push(collector);
    const handle = initTracing(
      {
        serviceName: "otlp",
        protocol: "http/protobuf",
        endpoint: collector.url,
        compression: "none",
        headers: { "x-collector": "1" },
        setGlobal: false,
      },
      { env: false },
    );
    handles.push(handle);
    expect(handle.endpoint).toBe(collector.url);
    handle.tracer("t").startSpan("otlp-span").end();
    await handle.forceFlush();
    expect(collector.requests[0]?.url).toBe("/v1/traces");
    expect(collector.requests[0]?.headers["content-type"]).toBe("application/x-protobuf");
    expect(collector.requests[0]?.headers["x-collector"]).toBe("1");
  });

  it("builds gRPC and TLS variants", async () => {
    const grpcPlain = initTracing(
      {
        serviceName: "g1",
        protocol: "grpc",
        endpoint: "http://127.0.0.1:1",
        headers: { "X-Key": "v" },
        setGlobal: false,
      },
      { env: false },
    );
    const grpcTls = initTracing(
      {
        serviceName: "g2",
        protocol: "grpc",
        endpoint: "https://127.0.0.1:1",
        tls: { insecureSkipVerify: true },
        setGlobal: false,
      },
      { env: false },
    );
    const grpcCa = initTracing(
      {
        serviceName: "g3",
        protocol: "grpc",
        endpoint: "http://127.0.0.1:1",
        tls: { caCertPem: "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n" },
        setGlobal: false,
      },
      { env: false },
    );
    const httpTls = initTracing(
      {
        serviceName: "h1",
        protocol: "http/protobuf",
        endpoint: "https://127.0.0.1:1/v1/traces",
        tls: { insecureSkipVerify: true, caCertPem: "pem" },
        setGlobal: false,
      },
      { env: false },
    );
    handles.push(grpcPlain, grpcTls, grpcCa, httpTls);
    expect(grpcPlain.config.protocol).toBe("grpc");
    expect(() =>
      initTracing(
        {
          serviceName: "bad",
          protocol: "http/protobuf",
          endpoint: "http://c:4318",
          tls: { clientCertPath: "/nonexistent/cert.pem", clientKeyPath: "/nonexistent/key.pem" },
          setGlobal: false,
        },
        { env: false },
      ),
    ).toThrow(/cannot read a TLS file/);
  });
});
