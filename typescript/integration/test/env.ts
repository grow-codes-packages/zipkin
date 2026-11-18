// Loads /bootstrap/env (KEY=value lines written by the compose `bootstrap` job) into process.env.
// Existing variables win, so a developer can override any value locally.

import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export function loadBootstrapEnv(path = process.env.GROW_BOOTSTRAP_ENV ?? "/bootstrap/env"): void {
  if (!existsSync(path)) return;
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed === "" || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    let value = trimmed.slice(eq + 1).trim();
    if (
      value.length >= 2 &&
      value[0] === value[value.length - 1] &&
      (value[0] === '"' || value[0] === "'")
    ) {
      value = value.slice(1, -1);
    }
    process.env[key] ??= value;
  }
}

/** `<repo>/conformance/scenarios`, or `GROW_CONFORMANCE_DIR`. */
export const scenariosDir =
  process.env.GROW_CONFORMANCE_DIR ??
  fileURLToPath(new URL("../../../conformance/scenarios/", import.meta.url));

/** Unique id of this run (set by the compose harness). */
export const runId = process.env.GROW_TEST_RUN_ID ?? "local";
