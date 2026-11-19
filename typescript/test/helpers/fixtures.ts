// Loads the responses recorded from the real openzipkin/zipkin:3.6.1 container (prep verify.sh).

import { readFileSync } from "node:fs";
import type { MockResponse } from "./server.js";

interface Fixture {
  request: { method: string; path: string; body?: unknown };
  response: { status: number; headers?: string[]; body?: unknown };
}

/** The raw recorded fixture. */
export function fixture(name: string): Fixture {
  return JSON.parse(
    readFileSync(new URL(`../fixtures/${name}.json`, import.meta.url), "utf8"),
  ) as Fixture;
}

/** The recorded response body (parsed JSON or text). */
export function fixtureBody(name: string): unknown {
  return fixture(name).response.body;
}

/** The recorded response as a mock response with the recorded headers (errors are plain text). */
export function fixtureResponse(name: string): MockResponse {
  const r = fixture(name).response;
  const headers: Record<string, string> = {};
  for (const line of r.headers ?? []) {
    const i = line.indexOf(":");
    headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  const body = r.body ?? "";
  return {
    status: r.status,
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
  };
}
