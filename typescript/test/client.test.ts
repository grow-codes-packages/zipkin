import { inspect } from "node:util";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  PACKAGE_KEY,
  REDACTED,
  RetryPolicy,
  ZipkinClient,
  ZipkinConfig,
  ZipkinQueryClient,
  ZipkinReporter,
  baseFromEndpoint,
  spansEndpoint,
} from "../src/index.js";
import { type MockServer, closedPort, startServer } from "./helpers/server.js";
import { fakeZipkin, zipkinServer } from "./helpers/zipkin.js";

const servers: { close(): Promise<void> }[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

async function track<T extends { close(): Promise<void> }>(s: Promise<T>): Promise<T> {
  const server = await s;
  servers.push(server);
  return server;
}

const fast = { retry: { maxRetries: 1, initialBackoffMs: 1 } };

function stockServer(): Promise<MockServer> {
  return track(
    zipkinServer({
      "/health": "health.ok",
      "/info": "serverVersion.ok",
      "/api/v2/services": "listServices.ok",
    }),
  );
}

describe("ZipkinConfig", () => {
  it("uses defaults and freezes", () => {
    const c = new ZipkinConfig();
    expect(c.url).toBe("http://127.0.0.1:9411");
    expect(c.endpoint).toBe("http://127.0.0.1:9411/api/v2/spans");
    expect(c.requestTimeoutMs).toBe(30_000);
    expect(c.retry).toBeInstanceOf(RetryPolicy);
    expect(Object.isFrozen(c)).toBe(true);
    expect(ZipkinConfig.from(c)).toBe(c);
    expect(ZipkinConfig.from().url).toBe(c.url);
    const prefix = `GROW_${PACKAGE_KEY.toUpperCase().replaceAll("-", "_")}_`;
    expect(ZipkinConfig.ENV_PREFIX).toBe(prefix);
  });

  it("keeps a gateway prefix and optional settings", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const c = new ZipkinConfig({
      url: "https://gw.example/zipkin/",
      proxy: "http://p:1",
      userAgentSuffix: "s",
      hooks,
      logger,
      headers: { Authorization: "Bearer gw-secret", "x-team": "sdk", "x-api-key": "" },
    });
    expect(c.endpoint).toBe("https://gw.example/zipkin/api/v2/spans");
    expect(c.proxy).toBe("http://p:1");
    expect(c.userAgentSuffix).toBe("s");
    expect(c.hooks).toBe(hooks);
    expect(c.logger).toBe(logger);
    expect(c.knownSecrets()).toEqual(["Bearer gw-secret"]);
    expect(new ZipkinConfig({ endpoint: "" }).endpoint).toBe("http://127.0.0.1:9411/api/v2/spans");
  });

  it("spansEndpoint / baseFromEndpoint append and strip once", () => {
    expect(spansEndpoint("http://z:9411")).toBe("http://z:9411/api/v2/spans");
    expect(spansEndpoint("http://z:9411/api/v2/spans/")).toBe("http://z:9411/api/v2/spans");
    expect(baseFromEndpoint("http://z/p/api/v2/spans")).toBe("http://z/p");
    expect(baseFromEndpoint("http://z/other/")).toBe("http://z/other");
  });

  it("validate() raises ConfigError", () => {
    expect(() => new ZipkinConfig({ url: "ftp://x" })).toThrow(ConfigError);
    expect(() => new ZipkinConfig({ endpoint: "nope" })).toThrow(ConfigError);
  });

  it("core.redaction.config: toString/JSON/inspect hide credentials", () => {
    const c = new ZipkinConfig({
      endpoint: "http://user:pw-secret@z:9411/api/v2/spans",
      headers: { "x-api-key": "hdr-secret" },
    });
    for (const form of [String(c), JSON.stringify(c), inspect(c)]) {
      expect(form).not.toContain("hdr-secret");
      expect(form).not.toContain("pw-secret");
    }
    expect(String(c)).toContain(REDACTED);
    expect(String(c)).toMatch(/^ZipkinConfig\(/);
  });

  it("core.config.env-precedence: explicit > GROW_* > vendor > default", () => {
    const env = {
      GROW_ZIPKIN_URL: "http://grow:1",
      ZIPKIN_URL: "http://vendor:2",
      OTEL_EXPORTER_ZIPKIN_ENDPOINT: "http://otel:3/prefix/api/v2/spans",
      GROW_ZIPKIN_REQUEST_TIMEOUT: "5s",
      GROW_ZIPKIN_MAX_RETRIES: "7",
    };
    const fromEnv = ZipkinConfig.fromEnv({}, env);
    expect(fromEnv.url).toBe("http://grow:1");
    expect(fromEnv.endpoint).toBe("http://otel:3/prefix/api/v2/spans");
    expect(fromEnv.requestTimeoutMs).toBe(5000);
    expect(fromEnv.retry.maxRetries).toBe(7);

    const vendor = ZipkinConfig.fromEnv({}, { ZIPKIN_URL: "http://vendor:2" });
    expect(vendor.url).toBe("http://vendor:2");
    expect(vendor.endpoint).toBe("http://vendor:2/api/v2/spans");

    const otelOnly = ZipkinConfig.fromEnv(
      {},
      { OTEL_EXPORTER_ZIPKIN_ENDPOINT: "https://gw/zipkin/api/v2/spans" },
    );
    expect(otelOnly.url).toBe("https://gw/zipkin");
    expect(otelOnly.endpoint).toBe("https://gw/zipkin/api/v2/spans");

    const growEndpoint = ZipkinConfig.fromEnv(
      {},
      { ...env, GROW_ZIPKIN_ENDPOINT: "http://grow-ep:4/api/v2/spans" },
    );
    expect(growEndpoint.endpoint).toBe("http://grow-ep:4/api/v2/spans");

    const explicit = ZipkinConfig.fromEnv(
      { url: "http://explicit:3", endpoint: "http://explicit:3/x", retry: { initialBackoffMs: 1 } },
      env,
    );
    expect(explicit.url).toBe("http://explicit:3");
    expect(explicit.endpoint).toBe("http://explicit:3/x");
    expect(explicit.retry.maxRetries).toBe(7);
    expect(ZipkinConfig.fromEnv({}, {}).url).toBe(ZipkinConfig.DEFAULT_URL);
    expect(ZipkinConfig.fromEnv().url).toBeTypeOf("string");

    let err: unknown;
    try {
      ZipkinConfig.fromEnv({}, { GROW_ZIPKIN_TOTAL_TIMEOUT: "forever-and-secret" });
    } catch (e) {
      err = e;
    }
    expect(err).toBeInstanceOf(ConfigError);
    expect((err as Error).message).toContain("GROW_ZIPKIN_TOTAL_TIMEOUT");
    expect((err as Error).message).not.toContain("forever-and-secret");
  });
});

describe("ZipkinClient", () => {
  it("health, ping, serverInfo and serverVersion against recorded responses", async () => {
    const s = await stockServer();
    const client = new ZipkinClient({ url: s.url, ...fast });
    const health = await client.health();
    expect(health).toMatchObject({ healthy: true, status: "UP" });
    expect(health.details.zipkin).toBe("UP");
    expect(health.details.storage).toContain("InMemoryStorage");
    expect(await client.ping()).toBeGreaterThanOrEqual(0);
    expect(await client.serverVersion()).toBe("3.6.1");
    expect(await client.serverInfo()).toEqual({ version: "3.6.1", commit: "b2b0c98" });
    expect(client.transport.userAgent).toContain(`grow-codes-${PACKAGE_KEY}/`);
    expect(s.requests.map((r) => r.url)).toEqual(["/health", "/api/v2/services", "/info", "/info"]);
    expect(client.query).toBeInstanceOf(ZipkinQueryClient);
    expect(client.query).toBe(client.query);
    expect(client.reporter).toBeInstanceOf(ZipkinReporter);
    expect(client.reporter).toBe(client.reporter);
    expect(client.createReporter({ compression: "gzip" }).compression).toBe("gzip");
    await client.close();
  });

  it("sends gateway headers on every call", async () => {
    const s = await stockServer();
    const client = new ZipkinClient({ url: s.url, headers: { authorization: "Bearer gw" } });
    await client.ping();
    expect(s.requests[0]?.headers.authorization).toBe("Bearer gw");
    await client.close();
  });

  it("gateway 401 maps to AuthenticationError without leaking the header", async () => {
    const s = await track(startServer([{ status: 401, body: "denied for Bearer gw-secret-xyz" }]));
    const client = new ZipkinClient({
      url: s.url,
      headers: { authorization: "Bearer gw-secret-xyz" },
      ...fast,
    });
    const err = await client.ping().catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "AUTHENTICATION", httpStatus: 401 });
    expect((err as Error).message).not.toContain("gw-secret-xyz");
    await client.close();
  });

  it("serverVersion raises UnsupportedError when absent", async () => {
    const s = await track(startServer([{ status: 404, body: "404 Not Found" }]));
    const client = new ZipkinClient({ url: s.url, ...fast });
    await expect(client.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    expect(await client.serverInfo()).toEqual({});
    const s2 = await track(startServer([{ status: 200, body: { zipkin: { version: "" } } }]));
    const client2 = new ZipkinClient({ url: s2.url, ...fast });
    await expect(client2.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    const s3 = await track(startServer([{ status: 200 }]));
    const client3 = new ZipkinClient({ url: s3.url, ...fast });
    expect(await client3.serverInfo()).toEqual({});
    await Promise.all([client.close(), client2.close(), client3.close()]);
  });

  it("health: 503 DOWN, empty body, 500 and vendor words", async () => {
    let calls = 0;
    const down = await track(
      startServer(() => {
        calls++;
        return {
          status: 503,
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ status: "DOWN" }),
        };
      }),
    );
    const c1 = new ZipkinClient({ url: down.url, ...fast });
    expect(await c1.health()).toEqual({
      healthy: false,
      status: "DOWN",
      details: { httpStatus: "503" },
    });
    expect(calls).toBe(1);
    const empty = await track(startServer([{ status: 200 }]));
    const c2 = new ZipkinClient({ url: empty.url, ...fast });
    expect(await c2.health()).toMatchObject({ healthy: true, status: "UP", details: {} });
    const sick = await track(startServer([{ status: 500 }]));
    const c3 = new ZipkinClient({ url: sick.url, ...fast });
    expect(await c3.health()).toMatchObject({ healthy: false, status: "unhealthy" });
    const word = await track(startServer([{ status: 200, body: { status: "DEGRADED" } }]));
    const c4 = new ZipkinClient({ url: word.url, ...fast });
    expect(await c4.health()).toMatchObject({ healthy: false, status: "DEGRADED" });
    await Promise.all([c1.close(), c2.close(), c3.close(), c4.close()]);
  });

  it("core.health.unreachable: closed port -> healthy=false, status=unreachable", async () => {
    const port = await closedPort();
    const client = new ZipkinClient({ url: `http://127.0.0.1:${port}`, ...fast });
    expect(await client.health()).toEqual({
      healthy: false,
      status: "unreachable",
      details: { error: "UNAVAILABLE" },
    });
    await client.close();
  });

  it("health rethrows cancellation", async () => {
    const s = await stockServer();
    const client = new ZipkinClient({ url: s.url });
    await expect(client.health({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await client.close();
  });

  it("core.close.idempotent: close twice is safe and shuts tracing down", async () => {
    const fake = await track(fakeZipkin());
    const client = new ZipkinClient({ url: fake.url });
    const handle = client.initTracing(
      { serviceName: "client-close", setGlobal: false },
      { env: false },
    );
    expect(handle.endpoint).toBe(`${fake.url}/api/v2/spans`);
    handle.tracer("t").startSpan("pending-span").end();
    await client.close();
    await client.close();
    expect(fake.spans.map((s) => s.name)).toEqual(["pending-span"]);
    await expect(client.ping()).rejects.toMatchObject({ code: "CONFIG" });
    expect(() => client.initTracing({ serviceName: "x" })).toThrow(ConfigError);
  });

  it("initTracing: explicit endpoint wins; env protocol is honoured", async () => {
    const client = new ZipkinClient({ url: "http://127.0.0.1:1" });
    const explicit = client.initTracing(
      { serviceName: "a", endpoint: "http://127.0.0.1:2/api/v2/spans", setGlobal: false },
      { env: false },
    );
    expect(explicit.endpoint).toBe("http://127.0.0.1:2/api/v2/spans");
    const otlp = client.initTracing(
      { serviceName: "b", setGlobal: false },
      {
        env: {
          GROW_ZIPKIN_PROTOCOL: "http/protobuf",
          OTEL_EXPORTER_OTLP_ENDPOINT: "http://collector:4318",
        },
      },
    );
    expect(otlp.endpoint).toBe("http://collector:4318/v1/traces");
    expect(otlp.config.protocol).toBe("http/protobuf");
    const fromProcess = client.initTracing({ serviceName: "c", setGlobal: false });
    expect(fromProcess.endpoint).toBe("http://127.0.0.1:1/api/v2/spans");
    await client.close();
  });

  it("fromEnv builds from GROW_* variables", async () => {
    const s = await stockServer();
    process.env.GROW_ZIPKIN_URL = s.url;
    try {
      const client = ZipkinClient.fromEnv({ ...fast });
      expect(client.config.url).toBe(s.url);
      expect(await client.ping()).toBeGreaterThanOrEqual(0);
      await client.close();
    } finally {
      delete process.env.GROW_ZIPKIN_URL;
    }
  });
});
