import assert from "node:assert/strict";
import { after, test } from "node:test";

import { authDatabase, beginLogin, consumeLogin, createSession, revokeSession, touchSession } from
  "../src/lib/auth/store.ts";
import { opaqueHash, randomOpaqueValue } from "../src/lib/auth/session-policy.ts";

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
