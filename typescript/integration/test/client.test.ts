// Integration tests for the skeleton client against the real service (docker/compose.yml).
// Service packages extend this file with one round-trip test per public function.

import assert from "node:assert/strict";
import { before, test } from "node:test";
import { ZipkinClient, ZipkinError } from "@grow-codes/zipkin";
import { loadBootstrapEnv } from "./env.ts";

before(() => {
  loadBootstrapEnv();
  const urlVar = "GROW_ZIPKIN_URL";
  assert.ok(process.env[urlVar], `${urlVar} must be set`);
});

test("health reports a healthy service", async () => {
  const client = ZipkinClient.fromEnv();
  try {
    const health = await client.health();
    assert.equal(health.healthy, true, JSON.stringify(health));
  } finally {
    await client.close();
  }
});

test("ping succeeds with the bootstrap credentials", async () => {
  const client = ZipkinClient.fromEnv();
  try {
    const latency = await client.ping();
    assert.equal(typeof latency, "number");
    assert.ok(latency >= 0);
  } finally {
    await client.close();
  }
});

test("ping with a wrong token raises AUTHENTICATION and never leaks it", async () => {
  const wrong = "wrong-token-for-integration";
  const client = ZipkinClient.fromEnv({ token: wrong });
  try {
    await assert.rejects(client.ping(), (err: unknown) => {
      assert.ok(err instanceof ZipkinError);
      assert.equal(err.code, "AUTHENTICATION");
      assert.ok(!err.message.includes(wrong));
      return true;
    });
  } finally {
    await client.close();
  }
});

test("serverVersion returns a version string", async () => {
  const client = ZipkinClient.fromEnv();
  try {
    assert.match(await client.serverVersion(), /\S/);
  } finally {
    await client.close();
    await client.close();
  }
});
