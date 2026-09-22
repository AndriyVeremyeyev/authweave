import assert from "node:assert/strict";
import { test } from "node:test";

import { provisionPersonalWorkspace } from "../src/lib/auth/core-client.ts";

const identity = {
  issuer: "http://localhost:8081",
  subject: "synthetic-subject",
  email: null,
  displayName: null,
  authenticatedAt: new Date("2026-09-22T12:00:00Z"),
};

test("BFF provisions only through the fixed Core endpoint with a server-only credential", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  let calls = 0;
  globalThis.fetch = async (input, init) => {
    calls++;
    assert.equal(input, "http://127.0.0.1:8080/internal/v1/personal-workspaces");
    assert.equal(init?.method, "POST");
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${token}`);
    assert.equal(init?.redirect, "error");
    assert.equal(init?.cache, "no-store");
    assert.deepEqual(JSON.parse(String(init?.body)), {
      issuer: identity.issuer, subject: identity.subject,
    });
    return Response.json({ workspaceId: "70000000-0000-4000-8000-000000000001" });
  };
  try {
    assert.equal(await provisionPersonalWorkspace(identity), "70000000-0000-4000-8000-000000000001");
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF fails closed before a Core call when its credential is absent", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  globalThis.fetch = async () => { throw new Error("Unexpected network call"); };
  try {
    await assert.rejects(provisionPersonalWorkspace(identity), /not configured/);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF rejects Core failure and an invalid workspace response", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  try {
    globalThis.fetch = async () => new Response(null, { status: 503 });
    await assert.rejects(provisionPersonalWorkspace(identity), /provisioning failed/);
    globalThis.fetch = async () => Response.json({ workspaceId: "not-a-uuid" });
    await assert.rejects(provisionPersonalWorkspace(identity), /response is invalid/);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});
