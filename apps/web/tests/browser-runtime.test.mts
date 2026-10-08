import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import { BrowserRuntime } from "./browser-runtime.mts";

for (const host of ["127.0.0.1", "::1"]) test(`busy ${host} preserves owner and releases owned reservations`, async () => {
  const owner = createServer((_request, response) => response.end("synthetic owner"));
  await new Promise<void>((resolve, reject) => { owner.once("error", reject); owner.listen(0, host, resolve); });
  const address = owner.address(); assert.ok(address && typeof address !== "string");
  const runtime = new BrowserRuntime();
  try {
    await assert.rejects(runtime.reservePorts([address.port]), { code: "EADDRINUSE" });
    await runtime.close();
    assert.ok(owner.listening);
    const response = await fetch(`http://${host === "::1" ? "[::1]" : host}:${address.port}`, { signal: AbortSignal.timeout(2000) });
    assert.equal(await response.text(), "synthetic owner");
    if (host === "::1") {
      const probe = createServer();
      await new Promise<void>((resolve, reject) => { probe.once("error", reject); probe.listen(address.port, "127.0.0.1", resolve); });
      await new Promise<void>(resolve => probe.close(() => resolve()));
    }
  } finally {
    await runtime.close(); owner.closeAllConnections(); await new Promise<void>(resolve => owner.close(() => resolve()));
  }
});
