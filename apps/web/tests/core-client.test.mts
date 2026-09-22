import assert from "node:assert/strict";
import { test } from "node:test";

import {
  createPersonalAssessment, provisionPersonalWorkspace, readPersonalAssessment,
} from "../src/lib/auth/core-client.ts";

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

const session = {
  ...identity,
  workspaceId: "70000000-0000-4000-8000-000000000001",
};
const assessmentId = "80000000-0000-4000-8000-000000000001";
const coreAssessment = {
  id: assessmentId, workspaceId: session.workspaceId, status: "DRAFT", version: 0,
  profileSchemaVersion: 5, profile: { application: { type: "UNKNOWN" } },
};

test("BFF creates and reads only within the server-side session workspace", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(String(input));
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${token}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Issuer"], session.issuer);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(init?.cache, "no-store");
    assert.equal(init?.redirect, "error");
    return Response.json(coreAssessment, { status: init?.method === "POST" ? 201 : 200 });
  };
  try {
    assert.deepEqual(await createPersonalAssessment(session), {
      id: assessmentId, status: "DRAFT", version: 0, profile: coreAssessment.profile,
    });
    assert.deepEqual(await readPersonalAssessment(session, assessmentId), {
      id: assessmentId, status: "DRAFT", version: 0, profile: coreAssessment.profile,
    });
    assert.deepEqual(calls, [
      `http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments`,
      `http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}`,
    ]);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF rejects malformed IDs, mismatched Core responses and Core authorization errors", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json(coreAssessment); };
  try {
    await assert.rejects(readPersonalAssessment(session, "../other"), /ID is invalid/);
    await assert.rejects(createPersonalAssessment({ ...session, workspaceId: "../other" }), /session is invalid/);
    assert.equal(calls, 0);
    globalThis.fetch = async () => Response.json({ ...coreAssessment,
      workspaceId: "70000000-0000-4000-8000-000000000002" }, { status: 201 });
    await assert.rejects(createPersonalAssessment(session), /response is invalid/);
    globalThis.fetch = async () => new Response(null, { status: 403 });
    await assert.rejects(readPersonalAssessment(session, assessmentId), /read failed/);
    globalThis.fetch = async () => new Response(null, { status: 404 });
    assert.equal(await readPersonalAssessment(session, assessmentId), null);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});
