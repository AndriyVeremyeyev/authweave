import assert from "node:assert/strict";
import { after, test } from "node:test";
import { NextRequest } from "next/server.js";

import { POST as createAssessmentRoute } from "../src/app/api/assessments/route.ts";
import { authDatabase, beginLogin, consumeLogin, createSession, revokeSession, touchSession } from
  "../src/lib/auth/store.ts";
import { opaqueHash, randomOpaqueValue, sessionCookieName } from "../src/lib/auth/session-policy.ts";

after(async () => { await authDatabase().end(); });

test("login state is browser-bound, expires in the database and can be consumed only once", async () => {
  const pool = authDatabase();
  const state = randomOpaqueValue();
  const binding = randomOpaqueValue();
  await beginLogin(state, binding, "synthetic-code-verifier", "synthetic-nonce", pool);
  assert.equal(await consumeLogin(state, randomOpaqueValue(), pool), null);
  assert.deepEqual(await consumeLogin(state, binding, pool), {
    codeVerifier: "synthetic-code-verifier", nonce: "synthetic-nonce",
  });
  assert.equal(await consumeLogin(state, binding, pool), null);

  const expiredState = randomOpaqueValue();
  await pool.query(
    `INSERT INTO web.oidc_login_transactions
       (state_hash, browser_binding_hash, code_verifier, nonce, created_at, expires_at)
     VALUES ($1, $2, 'expired-verifier', 'expired-nonce',
             CURRENT_TIMESTAMP - INTERVAL '11 minutes',
             CURRENT_TIMESTAMP - INTERVAL '1 minute')`,
    [opaqueHash(expiredState), opaqueHash(binding)],
  );
  try {
    assert.equal(await consumeLogin(expiredState, binding, pool), null);
  } finally {
    await pool.query("DELETE FROM web.oidc_login_transactions WHERE state_hash = $1",
      [opaqueHash(expiredState)]);
  }
});

test("opaque session rotates, idle expiry rejects access, and logout revokes locally", async () => {
  const pool = authDatabase();
  const identity = {
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "https://synthetic.example.test",
    subject: "synthetic-subject",
    email: "synthetic@example.test",
    displayName: "Synthetic User",
    authenticatedAt: new Date(),
  };
  const ids: string[] = [];
  try {
    const first = await createSession(identity, undefined, pool);
    ids.push(first);
    assert.equal((await touchSession(first, pool))?.subject, identity.subject);
    const second = await createSession(identity, first, pool);
    ids.push(second);
    assert.equal(await touchSession(first, pool), null);
    assert.equal((await touchSession(second, pool))?.email, identity.email);
    assert.equal((await touchSession(second, pool))?.workspaceId, identity.workspaceId);

    await pool.query(
      `UPDATE web.sessions SET created_at = CURRENT_TIMESTAMP - INTERVAL '1 hour',
         idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
       WHERE session_hash = $1`, [opaqueHash(second)],
    );
    assert.equal(await touchSession(second, pool), null);
    await revokeSession(second, pool);
    assert.equal(await touchSession(second, pool), null);

    const third = await createSession(identity, undefined, pool);
    ids.push(third);
    await pool.query(
      `UPDATE web.sessions SET created_at = CURRENT_TIMESTAMP - INTERVAL '9 hours',
         absolute_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second',
         idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
       WHERE session_hash = $1`, [opaqueHash(third)],
    );
    assert.equal(await touchSession(third, pool), null);
  } finally {
    for (const id of ids) await revokeSession(id, pool);
  }
});

test("web runtime cannot inspect core data or migration history", async () => {
  const pool = authDatabase();
  for (const table of ["core.assessments", "core.personal_workspaces", "web.schema_migrations"]) {
    await assert.rejects(pool.query(`SELECT 1 FROM ${table} LIMIT 1`), (failure: unknown) =>
      typeof failure === "object" && failure !== null && "code" in failure && failure.code === "42501");
  }
  assert.equal(await touchSession(undefined, pool), null);
});

test("sessions created before workspace binding cannot gain workspace access", async () => {
  const pool = authDatabase();
  const id = randomOpaqueValue();
  await pool.query(
    `INSERT INTO web.sessions
       (session_hash, issuer, subject, authenticated_at, idle_expires_at, absolute_expires_at)
     VALUES ($1, 'https://synthetic.example.test', 'legacy-subject', CURRENT_TIMESTAMP,
             CURRENT_TIMESTAMP + INTERVAL '30 minutes', CURRENT_TIMESTAMP + INTERVAL '8 hours')`,
    [opaqueHash(id)],
  );
  try {
    assert.equal(await touchSession(id, pool), null);
  } finally {
    await revokeSession(id, pool);
  }
});

test("assessment route requires same-origin session and never trusts a browser workspace ID", async () => {
  const previous = {
    issuer: process.env.AUTHWEAVE_OIDC_ISSUER,
    clientId: process.env.AUTHWEAVE_OIDC_CLIENT_ID,
    origin: process.env.AUTHWEAVE_PUBLIC_ORIGIN,
    token: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN,
    fetch: globalThis.fetch,
  };
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  process.env.AUTHWEAVE_PUBLIC_ORIGIN = "http://localhost:3000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const identity = {
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "http://localhost:8081",
    subject: "synthetic-route-user",
    email: null,
    displayName: null,
    authenticatedAt: new Date(),
  };
  const id = await createSession(identity, undefined);
  let calls = 0;
  globalThis.fetch = async (input, init) => {
    calls++;
    assert.equal(input, `http://127.0.0.1:8080/api/v5/workspaces/${identity.workspaceId}/assessments`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    return Response.json({
      id: "80000000-0000-4000-8000-000000000001", workspaceId: identity.workspaceId,
      status: "DRAFT", version: 0, profileSchemaVersion: 5, profile: {},
    }, { status: 201 });
  };
  const request = (origin: string, cookie?: string) => new NextRequest(
    "http://localhost:3000/api/assessments", {
      method: "POST", headers: {
        Origin: origin, ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
      },
      body: JSON.stringify({ workspaceId: "90000000-0000-4000-8000-000000000001" }),
    },
  );
  try {
    assert.equal((await createAssessmentRoute(request("https://other.example.test", id))).status, 403);
    assert.equal((await createAssessmentRoute(request("http://localhost:3000"))).status, 401);
    assert.equal(calls, 0);
    const response = await createAssessmentRoute(request("http://localhost:3000", id));
    assert.equal(response.status, 303);
    assert.equal(response.headers.get("location"),
      "http://localhost:3000/assessments/80000000-0000-4000-8000-000000000001");
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(calls, 1);
  } finally {
    await revokeSession(id);
    globalThis.fetch = previous.fetch;
    for (const [key, value] of [
      ["AUTHWEAVE_OIDC_ISSUER", previous.issuer],
      ["AUTHWEAVE_OIDC_CLIENT_ID", previous.clientId],
      ["AUTHWEAVE_PUBLIC_ORIGIN", previous.origin],
      ["AUTHWEAVE_CORE_SERVICE_TOKEN", previous.token],
    ] as const) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});
