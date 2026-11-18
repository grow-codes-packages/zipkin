import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { ZipkinClient, initTracing } from "../../src/index.js";
import {
  type ConformanceOptions,
  conformanceCases,
  formatReport,
  runConformance,
} from "../../src/testing/index.js";
import { fixtureResponse } from "../helpers/fixtures.js";
import { type FakeZipkin, fakeZipkin } from "../helpers/zipkin.js";
import { type MockServer, startServer } from "../helpers/server.js";

const scenariosDir = fileURLToPath(new URL("../../../conformance/scenarios/", import.meta.url));
let stock: MockServer;
let fake: FakeZipkin;
let shared: ZipkinClient;

beforeAll(async () => {
  stock = await startServer((req) => {
    if (req.url === "/health") return fixtureResponse("health.ok");
    if (req.url === "/info") return fixtureResponse("serverVersion.ok");
    if (req.url === "/api/v2/services") return fixtureResponse("listServices.ok");
    return fixtureResponse("deleteTrace.unsupported");
  });
  fake = await fakeZipkin();
  shared = new ZipkinClient({ url: fake.url });
});

afterAll(async () => {
  await shared.close();
  await stock.close();
  await fake.close();
});

function options(): ConformanceOptions {
  return {
    scenariosDir,
    factories: {
      core: () => new ZipkinClient({ url: stock.url }),
      tracing: ({ run }) => ({
        handle: initTracing(
          { serviceName: `conformance-ts-${run}`, setGlobal: false },
          { env: false, clientConfig: { url: fake.url } },
        ),
        query: shared.query,
      }),
    },
    capabilities: { core: ["version"], tracing: ["query", "services"] },
  };
}

describe("conformance against recorded/fake Zipkin APIs", () => {
  it("every core and tracing scenario passes", async () => {
    const report = await runConformance(options());
    expect(report.failed, formatReport(report)).toBe(0);
    expect(report.skipped).toBe(0);
    expect(report.results.map((r) => r.id)).toEqual(
      expect.arrayContaining(["core.health", "tracing.services", "tracing.tree"]),
    );
  });

  for (const c of conformanceCases(options())) {
    it.skipIf(c.skip !== undefined)(`case ${c.id}`, async () => {
      expect((await c.run()).status).toBe("passed");
    });
  }
});
