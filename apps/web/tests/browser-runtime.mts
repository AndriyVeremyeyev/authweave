import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { cp, mkdtemp, readFile, readdir, rm, stat } from "node:fs/promises";
import { createServer, request, type Server } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";

export const browserApp = "http://localhost:3000";

export function completion(child: ChildProcess) {
  return new Promise<number>((resolve, reject) => {
    child.once("error", reject); child.once("exit", code => resolve(code ?? 1));
  });
}

// Both browser suites own only their temporary app resources, never the identity lab.
export class BrowserRuntime {
  private servers: Server[] = [];
  private children: ChildProcess[] = [];
  private directory: string | undefined;
  private interrupted = false;
  private interrupt = () => {
    this.interrupted = true;
    for (const child of this.children) if (child.exitCode === null && child.signalCode === null) child.kill("SIGTERM");
  };

  constructor() {
    process.on("SIGINT", this.interrupt); process.on("SIGTERM", this.interrupt);
  }

  listen(server: Server, port: number, host: string): Promise<void> {
    return new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(port, host, () => { server.off("error", reject); this.servers.push(server); resolve(); });
    });
  }

  async reservePorts(ports: number[]) {
    const reservations: Server[] = [];
    // Refuse either busy loopback family. Never kill an owner service or use its app DB.
    for (const port of ports) for (const host of ["127.0.0.1", "::1"]) {
      const server = createServer(); await this.listen(server, port, host); reservations.push(server);
    }
    for (const server of reservations) await new Promise<void>(resolve => server.close(() => resolve()));
  }

  run(args: string[], cwd: string, env: NodeJS.ProcessEnv, quiet = false) {
    const child = spawn(process.execPath, args, { cwd, env, stdio: quiet ? "ignore" : "inherit" });
    this.children.push(child); return child;
  }

  async startWeb(auth: Record<string, string>, quiet = false): Promise<NodeJS.ProcessEnv> {
    const core = new URL(process.env.AUTHWEAVE_TEST_CORE_ORIGIN!);
    assert.match(core.href, /^http:\/\/127\.0\.0\.1:[1-9][0-9]*\/$/);
    assert.notEqual(core.port, "8080");
    const proxy = createServer((incoming, outgoing) => {
      const upstream = request(new URL(incoming.url!, core), { method: incoming.method, headers: incoming.headers,
        timeout: 10_000 }, response => { outgoing.writeHead(response.statusCode!, response.headers); response.pipe(outgoing); });
      upstream.on("timeout", () => upstream.destroy());
      upstream.on("error", () => { if (!outgoing.headersSent) outgoing.writeHead(502); outgoing.end(); });
      incoming.pipe(upstream);
    });
    await this.listen(proxy, 8080, "127.0.0.1");
    const web = process.cwd(), standalone = path.join(web, ".next/standalone");
    assert.ok((await stat(path.join(standalone, "server.js"))).isFile(), "Run make check-web first");
    const prerender = JSON.parse(await readFile(path.join(web, ".next/prerender-manifest.json"), "utf8"));
    assert.ok(!Object.hasOwn(prerender.routes, "/account"), "Account must remain request-time without build OIDC configuration");
    assert.ok(!(await readdir(standalone)).some(name => name.startsWith(".env")), "Do not copy local environment files");
    this.directory = await mkdtemp(path.join(tmpdir(), "authweave-browser-"));
    await cp(standalone, this.directory, { recursive: true });
    await cp(path.join(web, ".next/static"), path.join(this.directory, ".next/static"), { recursive: true });
    const publicDirectory = await stat(path.join(web, "public")).catch((error: NodeJS.ErrnoException) => {
      if (error.code !== "ENOENT") throw error;
      return null;
    });
    if (publicDirectory) await cp(path.join(web, "public"), path.join(this.directory, "public"), { recursive: true });
    assert.ok(!this.interrupted, "Browser harness was interrupted");
    const env: NodeJS.ProcessEnv = { ...process.env, NODE_ENV: "production", HOSTNAME: "localhost", PORT: "3000" };
    for (const key of Object.keys(env)) if (key.startsWith("AUTHWEAVE_")) delete env[key];
    Object.assign(env, { ...auth, AUTHWEAVE_PUBLIC_ORIGIN: browserApp,
      AUTHWEAVE_POSTGRES_DB: process.env.AUTHWEAVE_POSTGRES_DB, AUTHWEAVE_POSTGRES_PORT: process.env.AUTHWEAVE_POSTGRES_PORT,
      AUTHWEAVE_WEB_DB_PASSWORD: "web-test-password", AUTHWEAVE_CORE_SERVICE_TOKEN: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN });
    const child = this.run(["server.js"], this.directory, env, quiet);
    let ready = false;
    const readiness = { responses: 0, unavailable: 0, networkFailures: 0, lastStatus: 0 };
    for (let attempt = 0; attempt < 100; attempt++) {
      assert.ok(!this.interrupted, "Browser harness was interrupted");
      if (child.exitCode !== null) throw new Error("Isolated web server exited before readiness");
      try {
        const response = await fetch(`${browserApp}/account`, { signal: AbortSignal.timeout(1000) });
        const html = await response.text();
        readiness.responses++; readiness.lastStatus = response.status;
        if (html.includes("Authentication is temporarily unavailable.")) readiness.unavailable++;
        if (response.ok && html.includes("Sign in with ZITADEL")) { ready = true; break; }
      } catch { readiness.networkFailures++; }
      await delay(100);
    }
    assert.ok(ready, `Isolated production web server did not become ready: ${JSON.stringify(readiness)}`);
    return env;
  }

  async close() {
    try {
      for (const child of this.children.toReversed()) {
        if (child.exitCode !== null || child.signalCode !== null) continue;
        const exited = completion(child); child.kill("SIGTERM");
        if (!await Promise.race([exited.then(() => true), delay(3000).then(() => false)])) child.kill("SIGKILL");
        await exited;
      }
      for (const server of this.servers.toReversed()) {
        server.closeAllConnections();
        await new Promise<void>(resolve => server.close(() => resolve()));
      }
      if (this.directory) await rm(this.directory, { recursive: true, force: true });
    } finally {
      process.off("SIGINT", this.interrupt); process.off("SIGTERM", this.interrupt);
    }
  }
}
