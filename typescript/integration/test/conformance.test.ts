// Runs the shared conformance scenarios (conformance/scenarios) against the real Zipkin.
// Capabilities: core `version` (GET /info); tracing `query` + `services`. Zipkin's in-memory
// storage makes spans queryable as soon as POST /api/v2/spans returns, so no warm-up is needed;
// service and span names are lower-case because Zipkin lower-cases them on ingest.

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { ZipkinClient, initTracing } from "@grow-codes/zipkin";
import { formatReport, runConformance } from "@grow-codes/zipkin/testing";
import { loadBootstrapEnv, scenariosDir } from "./env.ts";

let shared: ZipkinClient;

before(() => {
  loadBootstrapEnv();
  shared = ZipkinClient.fromEnv();
});

after(async () => {
  await shared.close();
});

test("conformance: core", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["core"],
    factories: { core: () => ZipkinClient.fromEnv() },
    capabilities: { core: ["version"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
  assert.ok(report.passed > 0);
});

test("conformance: tracing", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["tracing"],
    factories: {
      tracing: ({ run }) => ({
        // Unique service per scenario run, so stale names never satisfy listServices.
        handle: initTracing({ serviceName: `conformance-ts-${run}`, setGlobal: false }),
        query: shared.query,
      }),
    },
    capabilities: { tracing: ["query", "services"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
  assert.ok(report.passed >= 2);
});
