// Fails when a production (runtime) dependency uses a licence outside the SPEC section 7 allowlist.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ALLOW = [
  "Apache-2.0",
  "MIT",
  "BSD-2-Clause",
  "BSD-3-Clause",
  "ISC",
  "Zlib",
  "Unicode-3.0",
  "Unicode-DFS-2016",
  "0BSD",
  "CC0-1.0",
  "MPL-2.0",
];

const root = fileURLToPath(new URL("..", import.meta.url));
const bin = process.platform === "win32" ? "npx.cmd" : "npx";
try {
  execFileSync(
    bin,
    [
      "--no-install",
      "license-checker-rseidelsohn",
      "--production",
      "--excludePrivatePackages",
      "--start",
      root,
      "--onlyAllow",
      ALLOW.join(";"),
      "--summary",
    ],
    { stdio: "inherit" },
  );
} catch {
  console.error("licence check failed: a runtime dependency is outside the allowlist");
  process.exit(1);
}
