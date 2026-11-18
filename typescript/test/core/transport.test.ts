import { afterEach, describe, expect, it } from "vitest";
import {
  type CommonConfigOptions,
  type ErrorInfo,
  HttpTransport,
  LANGUAGE,
  PACKAGE_KEY,
  REDACTED,
  type RequestInfo,
  type ResponseInfo,
  SDK_VERSION,
  type TransportOptions,
  ZipkinError,
  redactSecretsInText,
  resolveCommonConfig,
} from "../../src/core/index.js";
import { captureLogger } from "../helpers/logger.js";
import { type MockServer, closedPort, startConnectProxy, startServer } from "../helpers/server.js";

const servers: MockServer[] = [];
const transports: HttpTransport[] = [];

afterEach(async () => {
  await Promise.all(transports.splice(0).map((t) => t.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

async function serve(handler: Parameters<typeof startServer>[0]): Promise<MockServer> {
  const s = await startServer(handler);
  servers.push(s);
  return s;
}

function transport(
  url: string,
  options: CommonConfigOptions = {},
  extra: Omit<TransportOptions, "config"> = {},
): HttpTransport {
  const t = new HttpTransport({
    config: resolveCommonConfig(
      { retry: { initialBackoffMs: 1, maxBackoffMs: 5 }, ...options, url },
      url,
    ),
    ...extra,
  });
  transports.push(t);
  return t;
}

async function failure(p: Promise<unknown>): Promise<ZipkinError> {
  try {
    await p;
  } catch (err) {
    expect(err).toBeInstanceOf(ZipkinError);
    return err as ZipkinError;
  }
  throw new Error("expected the call to fail");
}

describe("HttpTransport retries", () => {
  it("core.retry.429-retry-after: waits at least Retry-After seconds, then succeeds", async () => {
    const s = await serve([
      { status: 429, headers: { "retry-after": "1" } },
      { status: 200, body: { ok: true } },
    ]);
    const t = transport(s.url);
    const start = performance.now();
    const res = await t.get<{ ok: boolean }>("/x");
    expect(performance.now() - start).toBeGreaterThanOrEqual(990);
    expect(res.data).toEqual({ ok: true });
    expect(res.attempts).toBe(2);
    expect(s.requests).toHaveLength(2);
  });

  it("core.retry.503-backoff: retries twice with full-jitter sleeps within bounds", async () => {
    const s = await serve([{ status: 503 }, { status: 503 }, { status: 200, body: "done" }]);
    const sleeps: number[] = [];
    const t = transport(
      s.url,
      { retry: { initialBackoffMs: 100, maxBackoffMs: 150, random: () => 0.5 } },
      {
        sleep: (ms) => {
          sleeps.push(ms);
          return Promise.resolve();
        },
      },
    );
    const res = await t.get("/x", { responseType: "text" });
    expect(res.data).toBe("done");
    expect(s.requests).toHaveLength(3);
    expect(sleeps).toEqual([50, 75]);
    for (const [i, ms] of sleeps.entries()) {
      expect(ms).toBeGreaterThanOrEqual(0);
      expect(ms).toBeLessThanOrEqual(Math.min(150, 100 * 2 ** i));
    }
  });

  it("core.retry.exhausted: 4 x 503 -> UnavailableError (retryable, HTTP 503)", async () => {
    const s = await serve([
      { status: 503, body: { message: "busy" }, headers: { "x-request-id": "req-1" } },
    ]);
    const err = await failure(transport(s.url).get("/x", { operation: "demo.get" }));
    expect(err.name).toBe("UnavailableError");
    expect(err.code).toBe("UNAVAILABLE");
    expect(err.retryable).toBe(true);
    expect(err.httpStatus).toBe(503);
    expect(err.requestId).toBe("req-1");
    expect(err.message).toBe("demo.get: busy (HTTP 503)");
    expect(s.requests).toHaveLength(4);
  });

  it("core.retry.post-not-replayed: POST + 500 is not retried, POST + 429 is", async () => {
    const s500 = await serve([{ status: 500 }]);
    const err = await failure(transport(s500.url).post("/x", { json: { a: 1 } }));
    expect(err.code).toBe("UNAVAILABLE");
    expect(s500.requests).toHaveLength(1);
    expect(s500.requests[0]?.headers["content-type"]).toBe("application/json");
    expect(s500.requests[0]?.body).toBe('{"a":1}');

    const s429 = await serve([{ status: 429 }, { status: 201, body: { id: 7 } }]);
    const res = await transport(s429.url).post<{ id: number }>("/x", { body: "raw" });
    expect(res.status).toBe(201);
    expect(res.data.id).toBe(7);
    expect(s429.requests).toHaveLength(2);

    const s502 = await serve([{ status: 502 }]);
    await failure(transport(s502.url).post("/x"));
    expect(s502.requests).toHaveLength(1);

    const marked = await serve([{ status: 500 }, { status: 200 }]);
    await transport(marked.url).post("/x", { idempotent: true });
    expect(marked.requests).toHaveLength(2);
  });

  it("idempotent GET retries 500, 502, 504 and 408", async () => {
    for (const status of [500, 502, 504, 408]) {
      const s = await serve([{ status }, { status: 204 }]);
      const res = await transport(s.url).get("/x");
      expect(res.status).toBe(204);
      expect(res.data).toBeUndefined();
    }
  });

  it("core.retry.retry-after-too-long: Retry-After 3600 -> RateLimitError at once with retryAfter", async () => {
    const s = await serve([{ status: 429, headers: { "retry-after": "3600" } }]);
    const start = performance.now();
    const err = await failure(transport(s.url).get("/x"));
    expect(performance.now() - start).toBeLessThan(1000);
    expect(err.name).toBe("RateLimitError");
    expect(err.code).toBe("RATE_LIMITED");
    expect(err.retryAfterMs).toBe(3_600_000);
    expect(s.requests).toHaveLength(1);
  });

  it("Retry-After beyond the remaining total budget stops at once", async () => {
    const s = await serve([{ status: 503, headers: { "retry-after": "5" } }]);
    const err = await failure(transport(s.url, { totalTimeoutMs: 2000 }).get("/x"));
    expect(err.code).toBe("UNAVAILABLE");
    expect(err.retryAfterMs).toBe(5000);
    expect(s.requests).toHaveLength(1);
  });

  it("Retry-After as HTTP date is honoured", async () => {
    const s = await serve([
      { status: 503, headers: { "retry-after": new Date(Date.now() - 1000).toUTCString() } },
      { status: 200 },
    ]);
    const res = await transport(s.url).get("/x", { responseType: "none" });
    expect(res.attempts).toBe(2);
  });

  it("retries connection resets for idempotent requests only", async () => {
    const s = await serve([{ destroy: true }, { status: 200, body: { ok: 1 } }]);
    const res = await transport(s.url).get("/x");
    expect(res.attempts).toBe(2);

    const p = await serve([{ destroy: true }]);
    const err = await failure(transport(p.url).post("/x"));
    expect(err.code).toBe("UNAVAILABLE");
    expect(p.requests).toHaveLength(1);
  });

  it("retries refused connections even for POST (never sent)", async () => {
    const port = await closedPort();
    const errors: ErrorInfo[] = [];
    const err = await failure(
      transport(`http://127.0.0.1:${port}`, { hooks: { onError: (i) => errors.push(i) } }).post(
        "/x",
      ),
    );
    expect(err.code).toBe("UNAVAILABLE");
    expect(err.message).toContain("ECONNREFUSED");
    expect(err.retryable).toBe(true);
    expect(errors.map((e) => e.willRetry)).toEqual([true, true, true, false]);
  });
});

describe("HttpTransport timeouts and cancellation", () => {
  it("core.timeout.request: slow server -> TimeoutError after requestTimeout", async () => {
    const s = await serve([{ status: 200, delayMs: 2000 }]);
    const start = performance.now();
    const err = await failure(
      transport(s.url, { requestTimeoutMs: 150, retry: { maxRetries: 0 } }).get("/x"),
    );
    const elapsed = performance.now() - start;
    expect(err.name).toBe("TimeoutError");
    expect(err.retryable).toBe(true);
    expect(err.message).toContain("150 ms");
    expect(elapsed).toBeGreaterThanOrEqual(140);
    expect(elapsed).toBeLessThan(1500);
  });

  it("per-attempt timeouts are retried for idempotent requests", async () => {
    const s = await serve([
      { status: 200, delayMs: 1000 },
      { status: 200, body: { ok: true } },
    ]);
    const res = await transport(s.url, { requestTimeoutMs: 100 }).get("/x");
    expect(res.attempts).toBe(2);
  });

  it("core.timeout.total: total budget exhausted across retries -> TimeoutError", async () => {
    const s = await serve([{ status: 503 }]);
    const start = performance.now();
    const err = await failure(
      transport(s.url, {
        totalTimeoutMs: 300,
        retry: { maxRetries: 10, initialBackoffMs: 100, maxBackoffMs: 100, random: () => 1 },
      }).get("/x"),
    );
    expect(err.name).toBe("TimeoutError");
    expect(err.message).toContain("total timeout");
    expect(err.cause).toBeInstanceOf(ZipkinError);
    expect(performance.now() - start).toBeLessThan(1500);
  });

  it("core.timeout.total: a slow attempt is cut by the remaining total budget", async () => {
    const s = await serve([{ status: 200, delayMs: 2000 }]);
    const err = await failure(
      transport(s.url, { totalTimeoutMs: 200, requestTimeoutMs: 1000 }).get("/x"),
    );
    expect(err.name).toBe("TimeoutError");
    expect(err.message).toContain("total timeout of 200 ms");
    expect(s.requests).toHaveLength(1);
  });

  it("total timeout already spent before an attempt", async () => {
    const s = await serve([{ status: 503 }]);
    let now = 0;
    const t = transport(
      s.url,
      { totalTimeoutMs: 100 },
      {
        now: () => now,
        sleep: () => {
          now += 1000;
          return Promise.resolve();
        },
      },
    );
    const err = await failure(t.get("/x"));
    expect(err.name).toBe("TimeoutError");
  });

  it("core.cancel: cancellation during the request -> CancelledError", async () => {
    const s = await serve([{ status: 200, delayMs: 2000 }]);
    const ac = new AbortController();
    setTimeout(() => ac.abort(), 100);
    const err = await failure(transport(s.url).get("/x", { signal: ac.signal }));
    expect(err.name).toBe("CancelledError");
    expect(err.code).toBe("CANCELLED");
    expect(err.retryable).toBe(false);
  });

  it("core.cancel: cancellation during backoff -> CancelledError", async () => {
    const s = await serve([{ status: 503 }]);
    const ac = new AbortController();
    const t = transport(s.url, {
      retry: { initialBackoffMs: 5000, maxBackoffMs: 5000, random: () => 1 },
    });
    setTimeout(() => ac.abort(), 150);
    const start = performance.now();
    const err = await failure(t.get("/x", { signal: ac.signal }));
    expect(err.name).toBe("CancelledError");
    expect(performance.now() - start).toBeLessThan(2000);
    expect(s.requests).toHaveLength(1);
  });

  it("core.cancel: an already aborted signal fails before sending", async () => {
    const s = await serve([{ status: 200 }]);
    const err = await failure(transport(s.url).get("/x", { signal: AbortSignal.abort() }));
    expect(err.code).toBe("CANCELLED");
    expect(s.requests).toHaveLength(0);
  });

  it("a non-SDK sleep failure becomes CancelledError", async () => {
    const s = await serve([{ status: 503 }]);
    const t = transport(s.url, {}, { sleep: () => Promise.reject(new Error("boom")) });
    const err = await failure(t.get("/x"));
    expect(err.code).toBe("CANCELLED");
  });
});

describe("HttpTransport errors", () => {
  const table: [number, string][] = [
    [400, "VALIDATION"],
    [401, "AUTHENTICATION"],
    [403, "PERMISSION_DENIED"],
    [404, "NOT_FOUND"],
    [409, "CONFLICT"],
    [412, "CONFLICT"],
    [413, "VALIDATION"],
    [422, "VALIDATION"],
    [429, "RATE_LIMITED"],
    [500, "UNAVAILABLE"],
    [501, "UNSUPPORTED"],
    [502, "UNAVAILABLE"],
    [503, "UNAVAILABLE"],
    [504, "TIMEOUT"],
  ];

  it("core.errors.mapping: statuses map per SPEC 2.1", async () => {
    for (const [status, code] of table) {
      const s = await serve([{ status }]);
      const err = await failure(
        transport(s.url, { retry: { maxRetries: 0 } }).get("/x", { operation: "op" }),
      );
      expect(err.code, `HTTP ${status}`).toBe(code);
      expect(err.httpStatus).toBe(status);
      expect(err.message).toMatch(new RegExp(`^op: .+ \\(HTTP ${status}\\)$`));
    }
  });

  it("extracts, truncates and redacts vendor error text", async () => {
    const long = "x".repeat(400);
    const s = await serve((req) => {
      if (req.url === "/long") return { status: 400, body: long };
      if (req.url === "/nested")
        return { status: 400, body: { errors: [{ detail: "bad field" }] } };
      if (req.url === "/html") return { status: 400, body: "<html>oops</html>" };
      if (req.url === "/assign")
        return { status: 400, body: { error: 'password="hunter2" api_key: abc Bearer abc.def' } };
      return { status: 400, body: { unrelated: true } };
    });
    const t = transport(s.url, { retry: { maxRetries: 0 } });
    const longErr = await failure(t.get("/long", { operation: "op" }));
    expect(longErr.message.length).toBeLessThan(330);
    expect(longErr.message).toContain("…");
    expect((await failure(t.get("/nested", { operation: "op" }))).message).toBe(
      "op: bad field (HTTP 400)",
    );
    expect((await failure(t.get("/html", { operation: "op" }))).message).toBe(
      "op: invalid request (HTTP 400)",
    );
    expect((await failure(t.get("/other", { operation: "op" }))).message).toBe(
      "op: invalid request (HTTP 400)",
    );
    const assign = (await failure(t.get("/assign", { operation: "op" }))).message;
    expect(assign).not.toContain("hunter2");
    expect(assign).not.toContain("abc");
  });

  it("allowStatus returns the response instead of throwing", async () => {
    const s = await serve([{ status: 404, body: { message: "nope" } }]);
    const res = await transport(s.url).get("/x", { allowStatus: [404] });
    expect(res.status).toBe(404);
    expect(res.data).toBeUndefined();
  });

  it("invalid JSON in a success response is a non-retryable UNAVAILABLE", async () => {
    const s = await serve([{ status: 200, body: "{not json" }]);
    const err = await failure(transport(s.url).get("/x"));
    expect(err.code).toBe("UNAVAILABLE");
    expect(err.retryable).toBe(false);
    expect(s.requests).toHaveLength(1);
  });

  it("decodes bytes, text and empty JSON bodies", async () => {
    const s = await serve([{ status: 200, body: "abc" }]);
    const t = transport(s.url);
    const bytes = await t.get<Uint8Array>("/x", { responseType: "bytes" });
    expect(new TextDecoder().decode(bytes.data)).toBe("abc");
    const empty = await serve([{ status: 200, body: "" }]);
    expect((await transport(empty.url).get("/x")).data).toBeUndefined();
  });

  it("requests after close fail with ConfigError; close is idempotent", async () => {
    const s = await serve([{ status: 200 }]);
    const t = transport(s.url);
    await t.get("/x", { responseType: "none" });
    await t.close();
    await t.close();
    expect(t.closed).toBe(true);
    const err = await failure(t.get("/x"));
    expect(err.code).toBe("CONFIG");
  });
});

describe("HttpTransport request building", () => {
  it("core.useragent: User-Agent is grow-codes-<key>/<version> (typescript)", async () => {
    const s = await serve([{ status: 200 }]);
    await transport(s.url).get("/x", { responseType: "none" });
    await transport(s.url, { userAgentSuffix: "my-app/2" }).get("/x", { responseType: "none" });
    expect(LANGUAGE).toBe("typescript");
    expect(s.requests[0]?.headers["user-agent"]).toBe(
      `grow-codes-${PACKAGE_KEY}/${SDK_VERSION} (typescript)`,
    );
    expect(s.requests[1]?.headers["user-agent"]).toBe(
      `grow-codes-${PACKAGE_KEY}/${SDK_VERSION} (typescript) my-app/2`,
    );
  });

  it("joins base URL paths and encodes queries", async () => {
    const s = await serve([{ status: 200 }]);
    const t = transport(
      `${s.url}/base/`,
      { headers: { "X-Extra": "1" } },
      { defaultHeaders: () => ({ "x-default": "d" }) },
    );
    await t.get("/v1/items", {
      query: { a: "x y", n: 2, b: true, skip: undefined, none: null, list: ["p", "q"] },
      headers: { "x-call": "c" },
      responseType: "none",
    });
    expect(s.requests[0]?.url).toBe("/base/v1/items?a=x+y&n=2&b=true&list=p&list=q");
    expect(s.requests[0]?.headers["x-extra"]).toBe("1");
    expect(s.requests[0]?.headers["x-default"]).toBe("d");
    expect(s.requests[0]?.headers["x-call"]).toBe("c");
    expect(t.buildUrl("https://other.example/abs").toString()).toBe("https://other.example/abs");
    const noSlash = transport(`${s.url}/base`);
    expect(noSlash.buildUrl("x").pathname).toBe("/base/x");
  });

  it("supports PUT and DELETE shortcuts", async () => {
    const s = await serve([{ status: 200 }]);
    const t = transport(s.url);
    await t.put("/a", {
      json: { v: 1 },
      headers: { "content-type": "application/merge-patch+json" },
      responseType: "none",
    });
    await t.delete("/a", { responseType: "none" });
    expect(s.requests.map((r) => r.method)).toEqual(["PUT", "DELETE"]);
    expect(s.requests[0]?.headers["content-type"]).toBe("application/merge-patch+json");
  });

  it("routes through an HTTP proxy when configured", async () => {
    const s = await serve([{ status: 200, body: { via: "proxy" } }]);
    const proxy = await startConnectProxy();
    try {
      const res = await transport(s.url, { proxy: proxy.url }).get<{ via: string }>("/x");
      expect(res.data.via).toBe("proxy");
      expect(proxy.tunnels()).toBe(1);
    } finally {
      await proxy.close();
    }
  });
});

describe("HttpTransport hooks, logging and redaction", () => {
  const token = "tok-super-secret-123";

  it("core.hooks: hooks receive redacted data; throwing hooks are ignored", async () => {
    const s = await serve([
      { status: 503 },
      { status: 200, headers: { "x-amzn-requestid": "aws-1" } },
    ]);
    const reqs: RequestInfo[] = [];
    const resps: ResponseInfo[] = [];
    const errs: ErrorInfo[] = [];
    const t = transport(
      s.url,
      {
        hooks: {
          onRequest: (i) => {
            reqs.push(i);
            throw new Error("hook failure");
          },
          onResponse: (i) => {
            resps.push(i);
            throw new Error("hook failure");
          },
          onError: (i) => {
            errs.push(i);
            throw new Error("hook failure");
          },
        },
      },
      { defaultHeaders: () => ({ authorization: `Bearer ${token}`, "x-api-key": token }) },
    );
    const res = await t.get("/x", { query: { token, page: 1 }, responseType: "none" });
    expect(res.requestId).toBe("aws-1");
    expect(reqs.map((r) => r.attempt)).toEqual([1, 2]);
    expect(reqs[0]?.headers.authorization).toBe(REDACTED);
    expect(reqs[0]?.headers["x-api-key"]).toBe(REDACTED);
    expect(reqs[0]?.headers["user-agent"]).toContain("grow-codes-");
    expect(reqs[0]?.url).toContain("token=[REDACTED]");
    expect(reqs[0]?.url).toContain("page=1");
    expect(resps.map((r) => [r.status, r.attempt])).toEqual([
      [503, 1],
      [200, 2],
    ]);
    expect(resps[1]?.requestId).toBe("aws-1");
    expect(resps[0]?.durationMs).toBeGreaterThanOrEqual(0);
    expect(errs).toHaveLength(1);
    expect(errs[0]?.willRetry).toBe(true);
    expect(errs[0]?.error.message).toContain("HTTP 503");
    expect(JSON.stringify([reqs, resps, errs])).not.toContain(token);
  });

  it("core.redaction.error-text: secrets never reach error messages, hook data or logs", async () => {
    const s = await serve([{ status: 401, body: { message: `invalid token ${token}` } }]);
    const logger = captureLogger();
    const seen: unknown[] = [];
    const t = transport(
      s.url,
      {
        logger,
        retry: { maxRetries: 0 },
        hooks: {
          onRequest: (i) => seen.push(i),
          onResponse: (i) => seen.push(i),
          onError: (i) => seen.push(i),
        },
      },
      { defaultHeaders: () => ({ authorization: `Bearer ${token}` }), knownSecrets: () => [token] },
    );
    const err = await failure(t.get(`/x?access_token=${token}`, { operation: "auth" }));
    expect(err.code).toBe("AUTHENTICATION");
    expect(err.message).toBe("auth: invalid token [REDACTED] (HTTP 401)");
    expect(JSON.stringify(err)).not.toContain(token);
    expect(logger.text()).not.toContain(token);
    expect(logger.lines.length).toBeGreaterThan(0);
    expect(JSON.stringify(seen)).not.toContain(token);
    expect(t.redact(`a ${token} b`)).toBe("a [REDACTED] b");
  });

  it("core.redaction.error-text: network error causes are redacted", async () => {
    const port = await closedPort();
    const t = transport(
      `http://127.0.0.1:${port}`,
      { retry: { maxRetries: 0 } },
      { knownSecrets: () => [token] },
    );
    const err = await failure(t.get(`/x?token=${token}`));
    expect(String(err.cause)).not.toContain(token);
    expect(err.message).not.toContain(token);
  });

  it("logs retries at warn and swallows logger failures", async () => {
    const s = await serve([{ status: 503 }, { status: 200 }]);
    const logger = captureLogger();
    await transport(s.url, { logger }).get("/x", { responseType: "none" });
    expect(logger.lines.some((l) => l.level === "warn" && l.message.includes("retrying"))).toBe(
      true,
    );
    const throwing = {
      debug: () => {
        throw new Error("x");
      },
      info: () => undefined,
      warn: () => {
        throw new Error("x");
      },
      error: () => undefined,
    };
    const s2 = await serve([{ status: 503 }, { status: 200 }]);
    await expect(
      transport(s2.url, { logger: throwing }).get("/x", { responseType: "none" }),
    ).resolves.toBeDefined();
  });

  it("redactSecretsInText hides key=value secrets and bearer tokens", () => {
    const out = redactSecretsInText(
      'token=abc&x=1 "password": "p" Authorization: Basic Zm9vOmJhcg==',
    );
    expect(out).toContain("token=[REDACTED]&x=1");
    expect(out).toContain('"password": "[REDACTED]"');
    expect(out).not.toContain("Zm9v");
    expect(redactSecretsInText("use Bearer abc.def")).toBe("use Bearer [REDACTED]");
  });
});
