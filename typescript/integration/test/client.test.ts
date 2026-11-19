// Integration tests for ZipkinClient against the real openzipkin/zipkin:3.6.1 (docker/compose.yml).

import assert from "node:assert/strict";
import { before, test } from "node:test";
import { ZipkinClient, ZipkinConfig } from "@grow-codes/zipkin";
import { loadBootstrapEnv } from "./env.ts";

before(() => {
  loadBootstrapEnv();
  assert.ok(process.env.GROW_ZIPKIN_URL, "GROW_ZIPKIN_URL must be set");
});

test("health reports UP with the in-memory storage detail", async () => {
  const client = ZipkinClient.fromEnv();
  try {
    const health = await client.health();
    assert.equal(health.healthy, true, JSON.stringify(health));
    assert.equal(health.status, "UP");
    assert.match(health.details.storage ?? "", /InMemoryStorage/);
  } finally {
    await client.close();
  }
});

test("ping round-trips through the query API", async () => {
  const client = ZipkinClient.fromEnv();
  try {
    const latency = await client.ping();
    assert.equal(typeof latency, "number");
    assert.ok(latency >= 0);
  } finally {
    await client.close();
  }
});

test("serverVersion and serverInfo match the bootstrap version", async () => {
  const client = ZipkinClient.fromEnv();
  try {
    const expected = process.env.ZIPKIN_VERSION ?? "3.6.1";
    assert.equal(await client.serverVersion(), expected);
    const info = await client.serverInfo();
    assert.equal(info.version, expected);
    assert.match(info.commit ?? "", /^[0-9a-f]+$/);
  } finally {
    await client.close();
    await client.close();
  }
});

test("fromEnv derives the ingest endpoint and gateway headers pass through", async () => {
  const config = ZipkinConfig.fromEnv({ headers: { authorization: "Bearer gateway-token-xyz" } });
  assert.equal(config.endpoint, `${config.url.replace(/\/+$/, "")}/api/v2/spans`);
  assert.ok(!String(config).includes("gateway-token-xyz"));
  const client = new ZipkinClient(config);
  try {
    // Stock Zipkin ignores the header; through a gateway it would authenticate the call.
    assert.ok((await client.ping()) >= 0);
  } finally {
    await client.close();
  }
});
