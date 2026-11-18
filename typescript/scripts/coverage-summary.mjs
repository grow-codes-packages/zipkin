// Converts vitest's json-summary output into the shared coverage/summary.json format:
// {"lines": {"covered", "total", "pct"}, "branches": {"covered", "total", "pct"} | null}
import { readFileSync, writeFileSync } from "node:fs";

const src = new URL("../coverage/coverage-summary.json", import.meta.url);
const dst = new URL("../coverage/summary.json", import.meta.url);
const total = JSON.parse(readFileSync(src, "utf8")).total;

const pick = (m) =>
  m && m.total > 0 ? { covered: m.covered, total: m.total, pct: Number(m.pct) } : null;

const summary = {
  lines: pick(total.lines) ?? { covered: 0, total: 0, pct: 100 },
  branches: pick(total.branches),
};
writeFileSync(dst, JSON.stringify(summary, null, 2) + "\n");
console.log(
  `coverage: lines ${summary.lines.pct}% (${summary.lines.covered}/${summary.lines.total}), ` +
    (summary.branches
      ? `branches ${summary.branches.pct}% (${summary.branches.covered}/${summary.branches.total})`
      : "branches n/a"),
);
