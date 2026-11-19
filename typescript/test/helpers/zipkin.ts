// Mock Zipkin servers: one routed by path to recorded fixtures, one in-memory fake that stores
// reported spans and answers the query API (used for exporter/tracing/conformance tests).

import { gunzipSync } from "node:zlib";
import { fixtureResponse } from "./fixtures.js";
import { type MockResponse, type MockServer, type RecordedRequest, startServer } from "./server.js";

export type Route =
  | string
  | MockResponse
  | ((req: RecordedRequest, url: URL) => MockResponse | string);

/** Starts a server; each route maps a pathname to a fixture name, a response or a function. */
export async function zipkinServer(routes: Record<string, Route>): Promise<MockServer> {
  return startServer((req) => {
    const url = new URL(req.url, "http://x");
    const route = routes[url.pathname];
    const resolved = typeof route === "function" ? route(req, url) : route;
    if (resolved === undefined) return fixtureResponse("deleteTrace.unsupported");
    return typeof resolved === "string" ? fixtureResponse(resolved) : resolved;
  });
}

/** Query parameters of a recorded request. */
export function params(req: RecordedRequest | undefined): URLSearchParams {
  return new URL(req?.url ?? "/", "http://x").searchParams;
}

export interface StoredSpan {
  traceId: string;
  id: string;
  parentId?: string;
  name?: string;
  kind?: string;
  timestamp?: number;
  duration?: number;
  localEndpoint?: { serviceName?: string };
  remoteEndpoint?: { serviceName?: string; port?: number; ipv4?: string; ipv6?: string };
  annotations?: { timestamp: number; value: string }[];
  tags?: Record<string, string>;
}

export interface FakeZipkin {
  server: MockServer;
  url: string;
  spans: StoredSpan[];
  /** Raw bodies received on POST /api/v2/spans (gunzipped). */
  bodies: string[];
  close(): Promise<void>;
}

/** An in-memory Zipkin: POST /api/v2/spans stores, the query API reads (lower-cases names). */
export async function fakeZipkin(): Promise<FakeZipkin> {
  const spans: StoredSpan[] = [];
  const bodies: string[] = [];
  const server = await startServer((req) => {
    const url = new URL(req.url, "http://x");
    if (req.method === "POST" && url.pathname === "/api/v2/spans") {
      const raw =
        req.headers["content-encoding"] === "gzip"
          ? gunzipSync(req.raw).toString("utf8")
          : req.body;
      bodies.push(raw);
      for (const s of JSON.parse(raw) as StoredSpan[]) {
        if (s.name !== undefined) s.name = s.name.toLowerCase();
        if (s.localEndpoint?.serviceName !== undefined)
          s.localEndpoint.serviceName = s.localEndpoint.serviceName.toLowerCase();
        spans.push(s);
      }
      return { status: 202 };
    }
    if (url.pathname.startsWith("/api/v2/trace/")) {
      const id = url.pathname.slice("/api/v2/trace/".length);
      const found = spans.filter((s) => s.traceId.padStart(32, "0") === id.padStart(32, "0"));
      return found.length === 0
        ? { status: 404, headers: { "content-type": "text/*" }, body: `${id} not found` }
        : { status: 200, body: found };
    }
    if (url.pathname === "/api/v2/services") {
      const names = [...new Set(spans.map((s) => s.localEndpoint?.serviceName ?? ""))].sort();
      return { status: 200, body: names.filter((n) => n !== "") };
    }
    return { status: 404, headers: { "content-type": "text/plain" }, body: "404 Not Found" };
  });
  return { server, url: server.url, spans, bodies, close: () => server.close() };
}
