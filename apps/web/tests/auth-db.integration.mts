import assert from "node:assert/strict";
import { after, test } from "node:test";
import { NextRequest } from "next/server.js";

import { POST as createAssessmentRoute } from "../src/app/api/assessments/route.ts";
import { POST as updateCapabilitiesRoute } from "../src/app/api/assessments/[id]/capabilities/route.ts";
import { POST as weightedPreviewRoute } from "../src/app/api/assessments/[id]/weighted-preview/route.ts";
import { POST as weightSensitivityRoute } from "../src/app/api/assessments/[id]/weight-sensitivity/route.ts";
import { POST as evaluationContextRoute } from "../src/app/api/assessments/[id]/evaluation-context/route.ts";
import { POST as usagePlanningRoute } from "../src/app/api/assessments/[id]/usage-planning/route.ts";
import { capabilityFields } from "../src/lib/assessment/capabilities.ts";
import { authDatabase, beginLogin, consumeLogin, createSession, revokeSession, touchSession } from
  "../src/lib/auth/store.ts";
import { freshCuratorGrant } from "../src/lib/auth/curator.ts";
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
    assert.equal((await touchSession(first, pool))?.curatorScope, null);
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

test("curator grant is persisted only with its project and organization scope", async () => {
  const pool = authDatabase();
  const curatorScope = { projectId: "123456789012345678", organizationId: "987654321012345678" };
  const authenticatedAt = new Date();
  const id = await createSession({
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "https://synthetic.example.test", subject: "synthetic-curator",
    email: null, displayName: null, authenticatedAt, curatorScope,
  }, undefined, pool);
  try {
    const session = await touchSession(id, pool);
    assert.deepEqual(session?.curatorScope, curatorScope);
    assert.equal(freshCuratorGrant(session?.curatorScope, curatorScope,
      session?.authenticatedAt ?? new Date("invalid")), true);
    assert.equal(freshCuratorGrant(session?.curatorScope, { ...curatorScope, projectId: "111" },
      session?.authenticatedAt ?? new Date("invalid")), false);
  } finally {
    await revokeSession(id, pool);
  }
  await assert.rejects(createSession({
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "https://synthetic.example.test", subject: "malformed-curator-scope",
    email: null, displayName: null, authenticatedAt: new Date(),
    curatorScope: { projectId: "123", organizationId: "not-numeric" },
  }, undefined, pool), /Could not establish web session/);
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

test("capability route enforces session, origin, form scope and optimistic version", async () => {
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
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-capability-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    application: { type: "B2B_SAAS" },
    protocols: { federation: {}, oauth2ProtectedApis: "UNKNOWN", socialLogin: "UNKNOWN",
      enterpriseSingleSignOn: "UNKNOWN" },
    provisioning: { scim: "UNKNOWN", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "UNKNOWN" },
    security: { multiFactorAuthentication: "UNKNOWN" },
    operations: { retained: true },
  };
  const form = new URLSearchParams({ expectedVersion: "2" });
  for (const field of capabilityFields) form.set(field.capability, "UNKNOWN");
  form.set("SCIM", "PREFERRED");
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string | null, cookie: string | null, body = form.toString(),
    contentType = "application/x-www-form-urlencoded") => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/capabilities`, {
      method: "POST", headers: {
        ...(origin ? { Origin: origin } : {}),
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
        "Content-Type": contentType,
      }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 5, profile });
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, 2);
    assert.equal(update.profile.provisioning.scim, "PREFERRED");
    assert.deepEqual(update.profile.operations, profile.operations);
    return Response.json({ id: assessmentId, workspaceId, status: "DRAFT", version: 3,
      profileSchemaVersion: 5, profile: update.profile });
  };
  try {
    assert.equal((await updateCapabilitiesRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await updateCapabilitiesRoute(request("http://localhost:3000", null), context)).status, 401);
    assert.equal((await updateCapabilitiesRoute(request("http://localhost:3000", sessionId,
      form.toString(), "application/json"), context)).status, 415);
    assert.equal((await updateCapabilitiesRoute(request("http://localhost:3000", sessionId,
      "expectedVersion=2"), context)).status, 400);
    assert.equal(calls.length, 0);
    const saved = await updateCapabilitiesRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(saved.status, 303);
    assert.equal(saved.headers.get("location"), `http://localhost:3000/assessments/${assessmentId}`);
    assert.equal(saved.headers.get("cache-control"), "no-store");
    assert.equal(calls.length, 2);
    const stale = new URLSearchParams(form);
    stale.set("expectedVersion", "1");
    const conflict = await updateCapabilitiesRoute(
      request("http://localhost:3000", sessionId, stale.toString()), context);
    assert.equal(conflict.status, 303);
    assert.equal(conflict.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?editError=stale`);
    assert.equal(calls.length, 3);
  } finally {
    await revokeSession(sessionId);
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

test("weighted preview route enforces origin and session without saving an assessment", async () => {
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
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-weight-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    protocols: { federation: { OIDC: "PREFERRED" }, oauth2ProtectedApis: "UNKNOWN",
      socialLogin: "UNKNOWN", enterpriseSingleSignOn: "UNKNOWN" },
    provisioning: { scim: "UNKNOWN", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "UNKNOWN" },
    security: { multiFactorAuthentication: "UNKNOWN" },
  };
  const comparison = {
    workspaceId, assessmentId, assessmentVersion: 2, catalogVersion: "synthetic-test",
    catalogKind: "SYNTHETIC", policyVersion: "synthetic-comparison-1",
    hardConstraintPolicyVersion: "hard-constraint-preflight-1",
    preferencePolicyVersion: "capability-preference-1",
    evaluatedAt: "2026-09-22T12:00:00Z", scope: "SYNTHETIC_UNRANKED_COMPARISON",
    recommendationReady: false, rankingPerformed: false, deferredPaths: ["operations"],
    candidates: [{ optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo",
      region: "Synthetic region", hardVerdict: "UNRESOLVED", exclusionReasons: [],
      informationGaps: [{ dimension: "COVERAGE", profilePath: "assessment",
        reasonCode: "NO_AFFIRMATIVE_CHECKS", explanation: "Clarify the requirements." }],
      capabilityPreferences: [{ capability: "OIDC", profilePath: "protocols.federation.OIDC",
        outcome: "UNKNOWN", reasonCode: "EVIDENCE_MISSING", explanation: "Evidence is missing.",
        evidence: null }],
    }],
  };
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string, cookie: string | null, body = "expectedVersion=2&OIDC=100") => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/weighted-preview`, {
      method: "POST", headers: {
        Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
      }, body,
    },
  );
  const sensitivityRequest = (origin: string, cookie: string | null,
    body = "expectedVersion=2&baseline_OIDC=100&alternative_OIDC=100") => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/weight-sensitivity`, {
      method: "POST", headers: {
        Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
      }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 5, profile });
    if (String(input).endsWith("/weight-sensitivity-preview")) {
      assert.deepEqual(JSON.parse(String(init?.body)), {
        baselineWeights: { OIDC: 100 }, alternativeWeights: { OIDC: 100 },
      });
      const scores = [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
        score: null, contributions: [] }];
      return Response.json({ comparison, scoringPolicyVersion: "explicit-capability-weights-1",
        sensitivityPolicyVersion: "explicit-weight-sensitivity-1",
        baseline: { weights: { OIDC: 100 }, scores }, alternative: { weights: { OIDC: 100 }, scores },
        deltas: [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
          scoreDelta: null, capabilityDeltas: [] }], rankingPerformed: false, recommendationReady: false });
    }
    assert.deepEqual(JSON.parse(String(init?.body)), { weights: { OIDC: 100 } });
    return Response.json({ comparison, scoringPolicyVersion: "explicit-capability-weights-1",
      weights: { OIDC: 100 }, rankingPerformed: false, recommendationReady: false,
      scores: [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
        score: null, contributions: [] }] });
  };
  try {
    assert.equal((await weightedPreviewRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await weightedPreviewRoute(request("http://localhost:3000", null), context)).status, 401);
    assert.equal((await weightedPreviewRoute(request("http://localhost:3000", sessionId,
      "expectedVersion=2&OIDC=99"), context)).status, 400);
    assert.equal(calls.length, 0);
    const response = await weightedPreviewRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    const preview = await response.json();
    assert.equal(preview.candidates[0].score, null);
    assert.equal(preview.candidates[0].status, "UNRESOLVED_HARD_CONSTRAINTS");
    assert.equal("evidence" in preview.candidates[0], false);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "POST"]);
    const stale = await weightedPreviewRoute(request("http://localhost:3000", sessionId,
      "expectedVersion=1&OIDC=100"), context);
    assert.equal(stale.status, 409);
    assert.equal(calls.length, 3);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", null), context)).status, 401);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", sessionId,
      "expectedVersion=2&baseline_OIDC=100&alternative_OIDC=99"), context)).status, 400);
    assert.equal(calls.length, 3);
    const sensitivity = await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", sessionId), context);
    assert.equal(sensitivity.status, 200);
    assert.equal(sensitivity.headers.get("cache-control"), "no-store");
    const paired = await sensitivity.json();
    assert.equal(paired.candidates[0].scoreDelta, null);
    assert.deepEqual(paired.candidates[0].capabilityDeltas, []);
    assert.equal("evidence" in paired.candidates[0], false);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "POST", "GET", "GET", "POST"]);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", sessionId,
      "expectedVersion=1&baseline_OIDC=100&alternative_OIDC=100"), context)).status, 409);
    assert.equal(calls.length, 6);
  } finally {
    await revokeSession(sessionId);
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

test("evaluation context route accepts only a scoped form from the personal session", async () => {
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
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-context-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    application: { type: "UNKNOWN", clients: [] },
    audience: { populations: [], tenancy: "UNKNOWN", membership: "UNKNOWN" },
    protocols: { federation: { OIDC: "PREFERRED" } },
    security: { dataResidency: "UNKNOWN", browserTokenExposureMinimization: "UNKNOWN",
      dataResidencyDetails: { allowedCountries: [], dataCategories: [] },
      complianceScopeStatus: "UNKNOWN",
      complianceTargets: [],
      authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN",
        stepUpAuthentication: "UNKNOWN" } },
  };
  const form = new URLSearchParams({
    expectedVersion: "2", applicationType: "B2B_SAAS", tenancy: "SINGLE_ORGANIZATION",
    membership: "SINGLE_ORGANIZATION_PER_USER", dataResidency: "REQUIRED",
    allowedCountries: "US, CA",
    browserTokenExposureMinimization: "REQUIRED",
    phishingResistance: "NOT_REQUIRED", nonExportableKeys: "NOT_REQUIRED",
    stepUpAuthentication: "NOT_REQUIRED", complianceScopeStatus: "NONE_IDENTIFIED",
  });
  form.append("clients", "BROWSER");
  form.append("selectedPopulations", "EMPLOYEES");
  form.append("selectedDataCategories", "USER_PROFILES");
  form.append("selectedDataCategories", "BACKUPS");
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string, cookie: string | null, body = form.toString()) => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/evaluation-context`, {
      method: "POST", headers: { Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}) }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 5, profile });
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, 2);
    assert.equal(update.profile.application.type, "B2B_SAAS");
    assert.equal(update.profile.security.browserTokenExposureMinimization, "REQUIRED");
    if (update.profile.security.dataResidencyDetails.allowedCountries.includes("ZZ")) {
      return new Response(null, { status: 422 });
    }
    assert.deepEqual(update.profile.security.dataResidencyDetails,
      { allowedCountries: ["CA", "US"], dataCategories: ["USER_PROFILES", "BACKUPS"] });
    assert.deepEqual(update.profile.protocols, profile.protocols);
    return Response.json({ id: assessmentId, workspaceId, status: "DRAFT", version: 3,
      profileSchemaVersion: 5, profile: update.profile });
  };
  try {
    assert.equal((await evaluationContextRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await evaluationContextRoute(request("http://localhost:3000", null), context)).status, 401);
    const forged = new URLSearchParams(form);
    forged.append("workspaceId", "90000000-0000-4000-8000-000000000001");
    assert.equal((await evaluationContextRoute(request("http://localhost:3000", sessionId,
      forged.toString()), context)).status, 400);
    assert.equal(calls.length, 0);
    const malformedResidency = new URLSearchParams(form);
    malformedResidency.set("allowedCountries", "us, CA");
    assert.equal((await evaluationContextRoute(request("http://localhost:3000", sessionId,
      malformedResidency.toString()), context)).status, 400);
    assert.equal(calls.length, 0);
    const response = await evaluationContextRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(response.status, 303);
    assert.equal(response.headers.get("location"), `http://localhost:3000/assessments/${assessmentId}`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT"]);
    const invalidCountry = new URLSearchParams(form);
    invalidCountry.set("allowedCountries", "ZZ");
    const rejected = await evaluationContextRoute(request("http://localhost:3000", sessionId,
      invalidCountry.toString()), context);
    assert.equal(rejected.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?contextError=invalid`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT", "GET", "PUT"]);
    const stale = new URLSearchParams(form);
    stale.set("expectedVersion", "1");
    const conflict = await evaluationContextRoute(request("http://localhost:3000", sessionId,
      stale.toString()), context);
    assert.equal(conflict.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?contextError=stale`);
    assert.equal(calls.length, 5);
  } finally {
    await revokeSession(sessionId);
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

test("usage planning route preserves the personal session and writes only scoped inputs", async () => {
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
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-usage-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    application: { type: "B2B_SAAS" },
    security: { assurance: "UNKNOWN" },
    operations: { hosting: "UNKNOWN",
      usagePlanning: { scopeDescription: "", assumptions: [], volumes: {} } },
  };
  const form = new URLSearchParams({ expectedVersion: "2", scopeDescription: "First production year" });
  for (let index = 0; index < 10; index++) form.append("assumption", index === 0 ? "Launch forecast" : "");
  for (const metric of ["MONTHLY_ACTIVE_USERS", "ENTERPRISE_SSO_CONNECTIONS",
    "MONTHLY_M2M_TOKEN_ISSUANCES", "PEAK_HUMAN_LOGINS_PER_SECOND"]) {
    form.set(`basis_${metric}`, "UNKNOWN");
    form.set(`value_${metric}`, "");
  }
  form.set("basis_MONTHLY_ACTIVE_USERS", "ASSUMED");
  form.set("value_MONTHLY_ACTIVE_USERS", "500");
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string, cookie: string | null, body = form.toString()) => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/usage-planning`, {
      method: "POST", headers: { Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}) }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 5, profile });
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, 2);
    assert.deepEqual(update.profile.operations.usagePlanning, {
      scopeDescription: "First production year", assumptions: ["Launch forecast"],
      volumes: { MONTHLY_ACTIVE_USERS: { basis: "ASSUMED", value: 500 } },
    });
    assert.equal(update.profile.operations.hosting, "UNKNOWN");
    assert.deepEqual(update.profile.security, profile.security);
    return Response.json({ id: assessmentId, workspaceId, status: "DRAFT", version: 3,
      profileSchemaVersion: 5, profile: update.profile });
  };
  try {
    assert.equal((await usagePlanningRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await usagePlanningRoute(request("http://localhost:3000", null), context)).status, 401);
    const forged = new URLSearchParams(form);
    forged.set("workspaceId", workspaceId);
    assert.equal((await usagePlanningRoute(request("http://localhost:3000", sessionId,
      forged.toString()), context)).status, 400);
    const invalidNumber = new URLSearchParams(form);
    invalidNumber.set("value_MONTHLY_ACTIVE_USERS", "9007199254740992");
    assert.equal((await usagePlanningRoute(request("http://localhost:3000", sessionId,
      invalidNumber.toString()), context)).status, 400);
    assert.equal(calls.length, 0);
    const saved = await usagePlanningRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(saved.status, 303);
    assert.equal(saved.headers.get("location"), `http://localhost:3000/assessments/${assessmentId}`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT"]);
    const stale = new URLSearchParams(form);
    stale.set("expectedVersion", "1");
    const conflict = await usagePlanningRoute(request("http://localhost:3000", sessionId,
      stale.toString()), context);
    assert.equal(conflict.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?usageError=stale`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT", "GET"]);
  } finally {
    await revokeSession(sessionId);
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
