import assert from "node:assert/strict";
import { test } from "node:test";

import {
  createPersonalAssessment, listPersonalAssessments, provisionPersonalWorkspace, readPersonalAssessment,
  readSyntheticComparison, updatePersonalCapabilities,
} from "../src/lib/auth/core-client.ts";
import { capabilityFields, type CapabilityValues } from "../src/lib/assessment/capabilities.ts";

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
const unknownCapabilities = Object.fromEntries(
  capabilityFields.map(field => [field.capability, "UNKNOWN"]),
) as CapabilityValues;
const editableProfile = {
  application: { type: "B2B_SAAS" },
  protocols: { federation: {}, oauth2ProtectedApis: "UNKNOWN", socialLogin: "UNKNOWN",
    enterpriseSingleSignOn: "UNKNOWN" },
  provisioning: { scim: "UNKNOWN", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "UNKNOWN" },
  security: { multiFactorAuthentication: "UNKNOWN", assurance: "UNKNOWN" },
  operations: { source: "keep" },
};

test("BFF updates only capabilities via a fresh Core profile and expected version", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  const values = { ...unknownCapabilities, OIDC: "REQUIRED", SCIM: "PREFERRED" } as CapabilityValues;
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers.Authorization, `Bearer ${token}`);
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(init?.cache, "no-store");
    assert.equal(init?.redirect, "error");
    if (init?.method === "GET") return Response.json({ ...coreAssessment, version: 3, profile: editableProfile });
    assert.equal(init?.method, "PUT");
    assert.equal(headers["Content-Type"], "application/json");
    const request = JSON.parse(String(init?.body));
    assert.equal(request.expectedVersion, 3);
    assert.deepEqual(request.profile.protocols.federation, { OIDC: "REQUIRED" });
    assert.equal(request.profile.provisioning.scim, "PREFERRED");
    assert.deepEqual(request.profile.operations, editableProfile.operations);
    return Response.json({ ...coreAssessment, version: 4, profile: request.profile });
  };
  try {
    assert.equal(await updatePersonalCapabilities(session, assessmentId, 3, values), "saved");
    assert.deepEqual(calls, [
      `GET http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}`,
      `PUT http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}/profile`,
    ]);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF blocks stale and non-draft edits before PUT and reports Core conflicts", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  let puts = 0;
  globalThis.fetch = async (_input, init) => {
    if (init?.method === "PUT") { puts++; return new Response(null, { status: 409 }); }
    return Response.json({ ...coreAssessment, version: 2, profile: editableProfile });
  };
  try {
    assert.equal(await updatePersonalCapabilities(session, assessmentId, 1, unknownCapabilities), "conflict");
    assert.equal(puts, 0);
    assert.equal(await updatePersonalCapabilities(session, assessmentId, 2, unknownCapabilities), "conflict");
    assert.equal(puts, 1);
    globalThis.fetch = async (_input, init) => {
      if (init?.method === "PUT") throw new Error("Unexpected PUT");
      return Response.json({ ...coreAssessment, status: "ARCHIVED", version: 2, profile: editableProfile });
    };
    assert.equal(await updatePersonalCapabilities(session, assessmentId, 2, unknownCapabilities), "not-editable");
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

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

test("BFF lists bounded assessment summaries using only its session workspace", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  const item = {
    id: assessmentId, status: "DRAFT", version: 0,
    createdAt: "2026-09-22T12:00:00Z", updatedAt: "2026-09-22T12:00:00Z",
  };
  let calls = 0;
  globalThis.fetch = async (input, init) => {
    calls++;
    assert.equal(input,
      `http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments?limit=20&beforeId=${assessmentId}`);
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${token}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(init?.cache, "no-store");
    return Response.json({ items: [item], nextBeforeId: assessmentId });
  };
  try {
    await assert.rejects(listPersonalAssessments(session, "../other"), /cursor is invalid/);
    assert.equal(calls, 0);
    assert.deepEqual(await listPersonalAssessments(session, assessmentId), {
      items: [item], nextBeforeId: assessmentId,
    });
    assert.equal(calls, 1);
    globalThis.fetch = async () => Response.json({ items: [{ ...item, profile: {} }], nextBeforeId: null });
    await assert.rejects(listPersonalAssessments(session), /response is invalid/);
    globalThis.fetch = async () => Response.json({ items: [item], nextBeforeId:
      "90000000-0000-4000-8000-000000000001" });
    await assert.rejects(listPersonalAssessments(session), /response is invalid/);
    globalThis.fetch = async () => new Response(null, { status: 403 });
    await assert.rejects(listPersonalAssessments(session), /list failed/);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

const coreComparison = {
  workspaceId: session.workspaceId, assessmentId, assessmentVersion: 2,
  catalogVersion: "synthetic-test", catalogKind: "SYNTHETIC",
  policyVersion: "synthetic-comparison-1",
  hardConstraintPolicyVersion: "hard-constraint-preflight-1",
  preferencePolicyVersion: "capability-preference-1",
  evaluatedAt: "2026-09-22T12:00:00Z", scope: "SYNTHETIC_UNRANKED_COMPARISON",
  recommendationReady: false, rankingPerformed: false,
  deferredPaths: ["operations"],
  candidates: [{
    optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo",
    region: "Synthetic region", hardVerdict: "UNRESOLVED", exclusionReasons: [],
    informationGaps: [{ dimension: "COVERAGE", profilePath: "assessment",
      reasonCode: "NO_AFFIRMATIVE_CHECKS", explanation: "Clarify the requirements." }],
    capabilityPreferences: [{ capability: "SOCIAL_LOGIN", profilePath: "protocols.socialLogin",
      outcome: "UNKNOWN", reasonCode: "EVIDENCE_MISSING", explanation: "Evidence is missing.",
      evidence: null }],
  }],
};

test("BFF reads synthetic comparison only for the session workspace and assessment version", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  let calls = 0;
  globalThis.fetch = async (input, init) => {
    calls++;
    assert.equal(input,
      `http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}/comparison-preflight`);
    assert.equal(init?.method, "GET");
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${token}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(init?.cache, "no-store");
    assert.equal(init?.redirect, "error");
    return Response.json(coreComparison);
  };
  try {
    const result = await readSyntheticComparison(session, assessmentId, 2);
    assert.equal(result.assessmentVersion, 2);
    assert.equal(result.candidates[0].hardVerdict, "UNRESOLVED");
    assert.equal(result.candidates[0].informationGaps[0].reasonCode, "NO_AFFIRMATIVE_CHECKS");
    assert.equal(result.candidates[0].capabilityPreferences[0].outcome, "UNKNOWN");
    assert.equal("winnerId" in result, false);
    assert.equal("evidence" in result.candidates[0].capabilityPreferences[0], false);
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF rejects forged ranking, stale or cross-workspace comparisons before rendering", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json(coreComparison); };
  try {
    await assert.rejects(readSyntheticComparison(session, "../other", 2), /request is invalid/);
    await assert.rejects(readSyntheticComparison(session, assessmentId, -1), /request is invalid/);
    await assert.rejects(readSyntheticComparison({ ...session, workspaceId: "../other" }, assessmentId, 2), /session is invalid/);
    assert.equal(calls, 0);
    for (const invalid of [
      { ...coreComparison, workspaceId: "70000000-0000-4000-8000-000000000002" },
      { ...coreComparison, assessmentId: "80000000-0000-4000-8000-000000000002" },
      { ...coreComparison, assessmentVersion: 1 },
      { ...coreComparison, rankingPerformed: true },
      { ...coreComparison, winnerId: "fictional-plan" },
      { ...coreComparison, candidates: [{ ...coreComparison.candidates[0],
        hardVerdict: "PASSES_CHECKED_REQUIREMENTS" }] },
    ]) {
      globalThis.fetch = async () => Response.json(invalid);
      await assert.rejects(readSyntheticComparison(session, assessmentId, 2), /response is invalid/);
    }
    globalThis.fetch = async () => new Response(null, { status: 403 });
    await assert.rejects(readSyntheticComparison(session, assessmentId, 2), /read failed/);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});
