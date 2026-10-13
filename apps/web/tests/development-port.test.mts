import assert from "node:assert/strict";
import { execFile, type ExecFileException } from "node:child_process";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { createServer } from "node:net";
import { test } from "node:test";
import { authConfiguration } from "../src/lib/auth/config.ts";

const require = createRequire(import.meta.url);
const manifest = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));

test("the development command explicitly selects the registered local OIDC port", () => {
  assert.equal(manifest.scripts.dev, "next dev --port 3000");
  const config = authConfiguration({ AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-port-check" });
  assert.equal(config.origin.origin, "http://localhost:3000");
  assert.equal(config.callbackUrl.href, "http://localhost:3000/api/auth/callback");
});

test("the pinned Next CLI refuses an occupied explicit port instead of starting on another", { timeout: 30_000 }, async () => {
  // Reserve only our own ephemeral socket; never contact or stop another project's server.
  const blocker = createServer();
  await new Promise<void>((resolve, reject) => { blocker.once("error", reject); blocker.listen(0, resolve); });
  try {
    const address = blocker.address(); assert(address && typeof address !== "string");
    // Keep the shipped arguments, substituting only the isolated socket's port.
    const args = manifest.scripts.dev.split(" ").slice(1); args[args.indexOf("--port") + 1] = String(address.port);
    const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith("AUTHWEAVE_") &&
      !["PORT", "DEBUG", "PWDEBUG", "NODE_DEBUG", "NODE_DEBUG_NATIVE", "NODE_OPTIONS"].includes(key)));
    const outcome = await new Promise<{ error: ExecFileException; stdout: string; stderr: string }>((resolve, reject) => {
      execFile(process.execPath, [require.resolve("next/dist/bin/next"), ...args], {
        cwd: new URL("../", import.meta.url), env: { ...env, PORT: "3001", NODE_ENV: "development", NEXT_TELEMETRY_DISABLED: "1" }, timeout: 20_000, maxBuffer: 65_536,
      }, (error, stdout, stderr) => error ? resolve({ error, stdout, stderr }) : reject(new Error("Occupied-port startup unexpectedly succeeded")));
    });
    assert.equal(outcome.error.code, 1);
    assert.match(outcome.stderr, /EADDRINUSE/);
    assert.doesNotMatch(outcome.stdout + outcome.stderr, /using available port|Ready in/);
    assert.equal(blocker.listening, true);
  } finally { await new Promise<void>((resolve, reject) => blocker.close(error => error ? reject(error) : resolve())); }
});
