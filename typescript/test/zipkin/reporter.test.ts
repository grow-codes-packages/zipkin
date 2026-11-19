import { gunzipSync } from "node:zlib";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  DEFAULT_MAX_BATCH_SIZE,
  UnavailableError,
  ValidationError,
  ZipkinClient,
  ZipkinReporter,
  type ZipkinSpanInput,
} from "../../src/index.js";
import { fixture, fixtureResponse } from "../helpers/fixtures.js";
import { type MockServer, startServer } from "../helpers/server.js";

const servers: MockServer[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

async function serve(handler: Parameters<typeof startServer>[0]): Promise<MockServer> {
  const s = await startServer(handler);
  servers.push(s);
  return s;
}

const fast = { retry: { maxRetries: 2, initialBackoffMs: 1, maxBackoffMs: 2 } };
const recorded = fixture("report.json").request.body as ZipkinSpanInput[];

describe("ZipkinReporter", () => {
  it("POSTs the recorded v2 JSON body to /api/v2/spans", async () => {
    const s = await serve([fixtureResponse("report.json")]);
    const reporter = new ZipkinReporter({ url: s.url });
    expect(reporter.endpoint).toBe(`${s.url}/api/v2/spans`);
    expect(reporter.compression).toBe("none");
    expect(reporter.maxBatchSize).toBe(DEFAULT_MAX_BATCH_SIZE);
    await reporter.report(recorded);
    const req = s.requests[0];
    expect(req?.method).toBe("POST");
    expect(req?.url).toBe("/api/v2/spans");
    expect(req?.headers["content-type"]).toBe("application/json");
    expect(JSON.parse(req?.body ?? "")).toEqual(recorded);
    await reporter.close();
    await reporter.close();
  });

  it("sends nothing for an empty list and splits large reports", async () => {
    const s = await serve([{ status: 202 }]);
    const reporter = new ZipkinReporter({ url: s.url }, { maxBatchSize: 2 });
    await reporter.report([]);
    expect(s.requests).toHaveLength(0);
    const spans = Array.from({ length: 5 }, (_, i) => ({
      traceId: "a".repeat(32),
      id: (i + 1).toString(16).padStart(16, "0"),
    }));
    await reporter.report(spans);
    expect(s.requests.map((r) => (JSON.parse(r.body) as unknown[]).length)).toEqual([2, 2, 1]);
    await reporter.close();
  });

  it("gzip compresses the body and normalizes spans locally", async () => {
    const s = await serve([{ status: 202 }]);
    const reporter = new ZipkinReporter(
      { url: s.url },
      { compression: "gzip", endpoint: `${s.url}/custom/spans` },
    );
    await reporter.report([
      { traceId: "A".repeat(32), id: "B".repeat(16), kind: "server", tags: { ok: true, n: 3 } },
    ]);
    const req = s.requests[0];
    expect(req?.url).toBe("/custom/spans");
    expect(req?.headers["content-encoding"]).toBe("gzip");
    expect(JSON.parse(gunzipSync(req!.rawBody).toString("utf8"))).toEqual([
      { traceId: "a".repeat(32), id: "b".repeat(16), kind: "SERVER", tags: { ok: "true", n: "3" } },
    ]);
    await reporter.close();
  });

  it("an invalid span throws ValidationError before sending", async () => {
    const s = await serve([{ status: 202 }]);
    const reporter = new ZipkinReporter({ url: s.url });
    await expect(reporter.report([{ traceId: "zz", id: "1".repeat(16) }])).rejects.toBeInstanceOf(
      ValidationError,
    );
    expect(s.requests).toHaveLength(0);
    await reporter.close();
  });

  it("server 400 is permanent; 413 is a ValidationError; 503 is retried", async () => {
    const bad = await serve([fixtureResponse("report.booleanTagRejected")]);
    const r1 = new ZipkinReporter({ url: bad.url, ...fast });
    const err = await r1.report(recorded).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect((err as Error).message).toContain("Expected a string but was BOOLEAN");
    expect(bad.requests).toHaveLength(1);
    const big = await serve([{ status: 413, body: "Request Entity Too Large" }]);
    const r2 = new ZipkinReporter({ url: big.url, ...fast });
    await expect(r2.report(recorded)).rejects.toMatchObject({
      code: "VALIDATION",
      httpStatus: 413,
    });
    const flaky = await serve([{ status: 503 }, { status: 502 }, { status: 202 }]);
    const r3 = new ZipkinReporter({ url: flaky.url, ...fast });
    await r3.report(recorded);
    expect(flaky.requests).toHaveLength(3);
    const down = await serve([{ status: 503 }]);
    const r4 = new ZipkinReporter({ url: down.url, ...fast });
    await expect(r4.report(recorded)).rejects.toBeInstanceOf(UnavailableError);
    await Promise.all([r1.close(), r2.close(), r3.close(), r4.close()]);
  });

  it("validates options and honours cancellation", async () => {
    expect(() => new ZipkinReporter({}, { compression: "br" as "gzip" })).toThrow(ConfigError);
    expect(() => new ZipkinReporter({}, { maxBatchSize: 0 })).toThrow(ConfigError);
    expect(() => new ZipkinReporter({}, { endpoint: "ftp://x" })).toThrow(ConfigError);
    const s = await serve([{ status: 202 }]);
    const reporter = new ZipkinReporter({ url: s.url });
    await expect(reporter.report(recorded, { signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await reporter.close();
  });

  it("client.reporter shares connections and fromEnv reads the endpoint", async () => {
    const s = await serve([{ status: 202 }]);
    const client = new ZipkinClient({ url: s.url, headers: { authorization: "Bearer gw" } });
    await client.reporter.report(recorded);
    await client.reporter.close();
    await client.reporter.report(recorded);
    expect(s.requests).toHaveLength(2);
    expect(s.requests[0]?.headers.authorization).toBe("Bearer gw");
    await client.close();
    await expect(client.reporter.report(recorded)).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.ping()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.transport.get("/x")).rejects.toMatchObject({
      code: "CANCELLED",
      message: "GET /x: client is closed",
    });
    process.env.GROW_ZIPKIN_ENDPOINT = `${s.url}/env/spans`;
    try {
      const r = ZipkinReporter.fromEnv({}, { maxBatchSize: 10 });
      expect(r.endpoint).toBe(`${s.url}/env/spans`);
      expect(r.maxBatchSize).toBe(10);
      await r.close();
    } finally {
      delete process.env.GROW_ZIPKIN_ENDPOINT;
    }
  });
});
