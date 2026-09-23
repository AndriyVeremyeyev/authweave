import assert from "node:assert/strict";
import { test } from "node:test";

import {
  createPersonalAssessment, listPersonalAssessments, provisionPersonalWorkspace, readPersonalAssessment,
  readPersonalArchitecturePatterns, readSyntheticComparison, updatePersonalCapabilities,
  previewPersonalWeightedComparison,
  previewPersonalWeightSensitivity,
  updatePersonalEvaluationContext,
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
const contextProfile = {
  ...editableProfile,
  application: { type: "UNKNOWN", clients: [] },
  audience: { populations: [], tenancy: "UNKNOWN", membership: "UNKNOWN" },
  security: { ...editableProfile.security, dataResidency: "UNKNOWN",
    browserTokenExposureMinimization: "UNKNOWN", complianceScopeStatus: "UNKNOWN",
    dataResidencyDetails: { allowedCountries: [], dataCategories: [] },
    complianceTargets: [],
    authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN",
      stepUpAuthentication: "UNKNOWN" } },
};

test("BFF context update preserves capabilities and uses the existing optimistic Core write", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  let puts = 0;
  globalThis.fetch = async (_input, init) => {
    if (init?.method === "GET") return Response.json({ ...coreAssessment, version: 3, profile: contextProfile });
    puts++;
    assert.equal(init?.method, "PUT");
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, 3);
    assert.equal(update.profile.application.type, "B2B_SAAS");
    assert.deepEqual(update.profile.application.clients, ["BROWSER"]);
    assert.deepEqual(update.profile.security.dataResidencyDetails,
      { allowedCountries: ["CA", "US"], dataCategories: ["USER_PROFILES", "BACKUPS"] });
    assert.deepEqual(update.profile.protocols, contextProfile.protocols);
    assert.deepEqual(update.profile.operations, contextProfile.operations);
    return Response.json({ ...coreAssessment, version: 4, profile: update.profile });
  };
  const values = {
    applicationType: "B2B_SAAS" as const, clients: ["BROWSER" as const],
    selectedPopulations: ["EXTERNAL_CUSTOMERS" as const],
    tenancy: "MULTI_TENANT_ORGANIZATIONS" as const,
    membership: "MULTIPLE_ORGANIZATIONS_PER_USER" as const,
    dataResidency: "REQUIRED" as const,
    allowedCountries: ["CA", "US"],
    selectedDataCategories: ["USER_PROFILES" as const, "BACKUPS" as const],
    browserTokenExposureMinimization: "REQUIRED" as const,
    phishingResistance: "NOT_REQUIRED" as const,
    nonExportableKeys: "NOT_REQUIRED" as const, stepUpAuthentication: "NOT_REQUIRED" as const,
    complianceScopeStatus: "NONE_IDENTIFIED" as const,
    selectedComplianceTargets: [] as ("SOC_2" | "ISO_27001")[],
  };
  try {
    assert.equal(await updatePersonalEvaluationContext(session, assessmentId, 2, values), "conflict");
    assert.equal(puts, 0);
    assert.equal(await updatePersonalEvaluationContext(session, assessmentId, 3, values), "saved");
    assert.equal(puts, 1);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

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

const patternContext = { clients: ["BROWSER" as const],
  browserTokenExposureMinimization: "REQUIRED" as const };
const patternDefinitions = [
  ["BFF_SESSION", "BROWSER", "SERVER_SIDE", "MATCHES_CHECKED_REQUIREMENTS"],
  ["SERVER_SIDE_SESSION", "BROWSER", "SERVER_SIDE", "MATCHES_CHECKED_REQUIREMENTS"],
  ["SPA_CODE_PKCE", "BROWSER", "BROWSER", "NEEDS_INFORMATION"],
  ["NATIVE_CODE_PKCE", "NATIVE_MOBILE", "NATIVE_APP", "NOT_APPLICABLE"],
  ["M2M_CLIENT_CREDENTIALS", "MACHINE_TO_MACHINE", "WORKLOAD", "NOT_APPLICABLE"],
] as const;
const corePatternPreflight = {
  workspaceId: session.workspaceId, assessmentId, assessmentVersion: 2,
  policyVersion: "architecture-pattern-preflight-1", evaluatedAt: "2026-09-22T12:00:00Z",
  scope: "ARCHITECTURE_PATTERN_PREFLIGHT", recommendationReady: false,
  selectedClients: ["BROWSER"], browserTokenExposureRequirement: "REQUIRED",
  checkedPaths: ["application.clients", "security.browserTokenExposureMinimization"],
  deferredPaths: ["application.type", "audience", "protocols", "provisioning",
    "security.multiFactorAuthentication", "security.auditability", "security.dataResidency",
    "security.assurance", "security.complianceTargets", "operations"],
  patterns: patternDefinitions.map(([patternId, clientType, tokenHandling, status]) => ({
    patternId, displayName: `${patternId} pattern`, clientType, tokenHandling, status,
    checks: [{ profilePath: "application.clients", outcome: clientType === "BROWSER" ? "PASS" : "NOT_APPLIED",
      reasonCode: clientType === "BROWSER" ? "CLIENT_SELECTED" : "CLIENT_NOT_SELECTED",
      explanation: "Client selection was checked." },
    { profilePath: "security.browserTokenExposureMinimization",
      outcome: clientType !== "BROWSER" ? "NOT_APPLIED" : tokenHandling === "BROWSER" ? "UNKNOWN" : "PASS",
      reasonCode: clientType !== "BROWSER" ? "PATTERN_NOT_APPLICABLE" :
        tokenHandling === "BROWSER" ? "ACCEPTABLE_EXPOSURE_UNDEFINED" : "TOKENS_HELD_SERVER_SIDE",
      explanation: "Token handling was checked." }],
    advantages: ["One advantage."], tradeoffs: ["One trade-off."],
    prerequisites: ["One prerequisite to verify."],
    references: ["https://www.rfc-editor.org/rfc/rfc8252.html#section-4"],
  })),
};

test("BFF reads only a version-matched personal architecture preflight and projects trade-offs", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  let calls = 0;
  globalThis.fetch = async (input, init) => {
    calls++;
    assert.equal(input,
      `http://127.0.0.1:8080/api/v1/workspaces/${session.workspaceId}/assessments/${assessmentId}/architecture-pattern-preflight`);
    assert.equal(init?.method, "GET");
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${token}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(init?.cache, "no-store");
    return Response.json(corePatternPreflight);
  };
  try {
    const result = await readPersonalArchitecturePatterns(session, assessmentId, 2, patternContext);
    assert.equal(result.assessmentVersion, 2);
    assert.deepEqual(result.selectedClients, ["BROWSER"]);
    assert.deepEqual(result.patterns.map(pattern => pattern.status), [
      "MATCHES_CHECKED_REQUIREMENTS", "MATCHES_CHECKED_REQUIREMENTS", "NEEDS_INFORMATION",
      "NOT_APPLICABLE", "NOT_APPLICABLE",
    ]);
    assert.equal(result.patterns[0].advantages[0], "One advantage.");
    assert.equal(result.patterns[2].checks[1].reasonCode, "ACCEPTABLE_EXPOSURE_UNDEFINED");
    assert.equal("recommendationReady" in result, false);
    assert.equal("winnerId" in result, false);
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF rejects forged or cross-workspace architecture claims", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json(corePatternPreflight); };
  try {
    await assert.rejects(readPersonalArchitecturePatterns(session, "../other", 2, patternContext), /request is invalid/);
    assert.equal(calls, 0);
    const first = corePatternPreflight.patterns[0];
    for (const invalid of [
      { ...corePatternPreflight, recommendationReady: true },
      { ...corePatternPreflight, winnerId: "BFF_SESSION" },
      { ...corePatternPreflight, workspaceId: "70000000-0000-4000-8000-000000000002" },
      { ...corePatternPreflight, assessmentVersion: 3 },
      { ...corePatternPreflight, selectedClients: ["NATIVE_MOBILE"] },
      { ...corePatternPreflight, browserTokenExposureRequirement: "NOT_REQUIRED" },
      { ...corePatternPreflight, patterns: [first, first, ...corePatternPreflight.patterns.slice(2)] },
      { ...corePatternPreflight, patterns: [{ ...first, status: "NOT_APPLICABLE" },
        ...corePatternPreflight.patterns.slice(1)] },
      { ...corePatternPreflight, patterns: [{ ...first,
        references: ["https://untrusted.example.test/claim"] }, ...corePatternPreflight.patterns.slice(1)] },
    ]) {
      globalThis.fetch = async () => Response.json(invalid);
      await assert.rejects(readPersonalArchitecturePatterns(session, assessmentId, 2, patternContext),
        /response is invalid/);
    }
    globalThis.fetch = async () => new Response(null, { status: 403 });
    await assert.rejects(readPersonalArchitecturePatterns(session, assessmentId, 2, patternContext), /read failed/);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

const preferredProfile = {
  ...editableProfile,
  protocols: { ...editableProfile.protocols, socialLogin: "PREFERRED" },
};
const scoredComparison = {
  ...coreComparison,
  candidates: [{ ...coreComparison.candidates[0], hardVerdict: "PASSES_CHECKED_REQUIREMENTS",
    informationGaps: [], capabilityPreferences: [{ ...coreComparison.candidates[0].capabilityPreferences[0],
      outcome: "AVAILABLE", reasonCode: "PREFERRED_CAPABILITY_AVAILABLE" }] }],
};
const coreWeightedPreview = {
  comparison: scoredComparison, scoringPolicyVersion: "explicit-capability-weights-1",
  weights: { SOCIAL_LOGIN: 100 }, rankingPerformed: false, recommendationReady: false,
  scores: [{ optionId: "fictional-plan", status: "SCORED", score: 100,
    contributions: [{ capability: "SOCIAL_LOGIN", weight: 100,
      outcome: "AVAILABLE", earnedPoints: 100 }] }],
};

test("BFF previews explicit weights through the session workspace and projects safe score data", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${token}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(init?.cache, "no-store");
    if (init?.method === "GET") return Response.json({ ...coreAssessment, version: 2, profile: preferredProfile });
    assert.equal(init?.method, "POST");
    assert.deepEqual(JSON.parse(String(init?.body)), { weights: { SOCIAL_LOGIN: 100 } });
    return Response.json(coreWeightedPreview);
  };
  try {
    const result = await previewPersonalWeightedComparison(session, assessmentId, 2, { SOCIAL_LOGIN: 100 });
    assert.equal(result.kind, "preview");
    if (result.kind !== "preview") return;
    assert.deepEqual(result.preview.candidates, [{
      optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo",
      region: "Synthetic region", status: "SCORED", score: 100,
      contributions: [{ capability: "SOCIAL_LOGIN", weight: 100,
        outcome: "AVAILABLE", earnedPoints: 100 }],
    }]);
    assert.equal("evidence" in result.preview.candidates[0], false);
    assert.equal("winnerId" in result.preview, false);
    assert.deepEqual(calls, [
      `GET http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}`,
      `POST http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}/weighted-comparison-preview`,
    ]);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF keeps points withheld when preferred evidence is unknown", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const unknownComparison = { ...scoredComparison, candidates: [{ ...scoredComparison.candidates[0],
    capabilityPreferences: [{ ...scoredComparison.candidates[0].capabilityPreferences[0],
      outcome: "UNKNOWN", reasonCode: "EVIDENCE_MISSING" }] }] };
  globalThis.fetch = async (_input, init) => init?.method === "GET" ?
    Response.json({ ...coreAssessment, version: 2, profile: preferredProfile }) :
    Response.json({ ...coreWeightedPreview, comparison: unknownComparison,
      scores: [{ optionId: "fictional-plan", status: "UNKNOWN_PREFERENCE_EVIDENCE",
        score: null, contributions: [] }] });
  try {
    const result = await previewPersonalWeightedComparison(session, assessmentId, 2, { SOCIAL_LOGIN: 100 });
    assert.equal(result.kind, "preview");
    if (result.kind === "preview") {
      assert.equal(result.preview.candidates[0].status, "UNKNOWN_PREFERENCE_EVIDENCE");
      assert.equal(result.preview.candidates[0].score, null);
      assert.deepEqual(result.preview.candidates[0].contributions, []);
    }
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF rejects stale or mismatched weights and forged score responses", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  let posts = 0;
  globalThis.fetch = async (_input, init) => {
    if (init?.method === "POST") { posts++; return Response.json(coreWeightedPreview); }
    return Response.json({ ...coreAssessment, version: 2, profile: preferredProfile });
  };
  try {
    assert.deepEqual(await previewPersonalWeightedComparison(session, assessmentId, 1,
      { SOCIAL_LOGIN: 100 }), { kind: "conflict" });
    assert.deepEqual(await previewPersonalWeightedComparison(session, assessmentId, 2,
      { SCIM: 100 }), { kind: "invalid" });
    assert.equal(posts, 0);
    for (const invalid of [
      { ...coreWeightedPreview, rankingPerformed: true },
      { ...coreWeightedPreview, winnerId: "fictional-plan" },
      { ...coreWeightedPreview, weights: { SOCIAL_LOGIN: 99 } },
      { ...coreWeightedPreview, comparison: { ...scoredComparison,
        workspaceId: "70000000-0000-4000-8000-000000000002" } },
      { ...coreWeightedPreview, scores: [{ ...coreWeightedPreview.scores[0], score: 99 }] },
      { ...coreWeightedPreview, scores: [{ ...coreWeightedPreview.scores[0],
        contributions: [{ ...coreWeightedPreview.scores[0].contributions[0], earnedPoints: 99 }] }] },
    ]) {
      globalThis.fetch = async (_input, init) => init?.method === "GET" ?
        Response.json({ ...coreAssessment, version: 2, profile: preferredProfile }) : Response.json(invalid);
      await assert.rejects(previewPersonalWeightedComparison(session, assessmentId, 2,
        { SOCIAL_LOGIN: 100 }), /response is invalid/);
    }
    globalThis.fetch = async (_input, init) => init?.method === "GET" ?
      Response.json({ ...coreAssessment, version: 2, profile: preferredProfile }) :
      Response.json({ ...coreWeightedPreview,
        comparison: { ...scoredComparison, assessmentVersion: 3 } });
    assert.deepEqual(await previewPersonalWeightedComparison(session, assessmentId, 2,
      { SOCIAL_LOGIN: 100 }), { kind: "conflict" });
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

const pairedWeights = { baselineWeights: { SOCIAL_LOGIN: 60, JIT: 40 },
  alternativeWeights: { SOCIAL_LOGIN: 20, JIT: 80 } };
const pairedProfile = { ...preferredProfile,
  provisioning: { ...preferredProfile.provisioning, justInTimeProvisioning: "PREFERRED" } };
const pairedComparison = { ...scoredComparison, candidates: [{ ...scoredComparison.candidates[0],
  capabilityPreferences: [...scoredComparison.candidates[0].capabilityPreferences,
    { ...coreComparison.candidates[0].capabilityPreferences[0], capability: "JIT",
      profilePath: "provisioning.justInTimeProvisioning", outcome: "UNAVAILABLE",
      reasonCode: "PREFERRED_CAPABILITY_UNAVAILABLE" }],
}] };
const pairedSensitivity = {
  comparison: pairedComparison, scoringPolicyVersion: "explicit-capability-weights-1",
  sensitivityPolicyVersion: "explicit-weight-sensitivity-1",
  baseline: { weights: pairedWeights.baselineWeights,
    scores: [{ optionId: "fictional-plan", status: "SCORED", score: 60,
      contributions: [{ capability: "SOCIAL_LOGIN", weight: 60, outcome: "AVAILABLE", earnedPoints: 60 },
        { capability: "JIT", weight: 40, outcome: "UNAVAILABLE", earnedPoints: 0 }] }] },
  alternative: { weights: pairedWeights.alternativeWeights,
    scores: [{ optionId: "fictional-plan", status: "SCORED", score: 20,
      contributions: [{ capability: "SOCIAL_LOGIN", weight: 20, outcome: "AVAILABLE", earnedPoints: 20 },
        { capability: "JIT", weight: 80, outcome: "UNAVAILABLE", earnedPoints: 0 }] }] },
  deltas: [{ optionId: "fictional-plan", status: "SCORED", scoreDelta: -40,
    capabilityDeltas: [{ capability: "SOCIAL_LOGIN", baselineWeight: 60,
      alternativeWeight: 20, outcome: "AVAILABLE", pointChange: -40 },
    { capability: "JIT", baselineWeight: 40,
      alternativeWeight: 80, outcome: "UNAVAILABLE", pointChange: 0 }] }],
  rankingPerformed: false, recommendationReady: false,
};

test("BFF compares explicit weights on one Core snapshot without exposing evidence or a winner", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  const token = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token;
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${token}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(init?.cache, "no-store");
    if (init?.method === "GET") return Response.json({ ...coreAssessment, version: 2, profile: pairedProfile });
    assert.deepEqual(JSON.parse(String(init?.body)), pairedWeights);
    return Response.json(pairedSensitivity);
  };
  try {
    const result = await previewPersonalWeightSensitivity(session, assessmentId, 2,
      pairedWeights.baselineWeights, pairedWeights.alternativeWeights);
    assert.equal(result.kind, "preview");
    if (result.kind !== "preview") return;
    assert.deepEqual(result.preview.candidates, [{ optionId: "fictional-plan", displayName: "Fictional Plan",
      plan: "Demo", region: "Synthetic region", status: "SCORED", baselineScore: 60,
      alternativeScore: 20, scoreDelta: -40,
      capabilityDeltas: [{ capability: "SOCIAL_LOGIN", baselineWeight: 60,
        alternativeWeight: 20, outcome: "AVAILABLE", pointChange: -40 },
      { capability: "JIT", baselineWeight: 40,
        alternativeWeight: 80, outcome: "UNAVAILABLE", pointChange: 0 }] }]);
    assert.equal("evidence" in result.preview.candidates[0], false);
    assert.equal("winnerId" in result.preview, false);
    assert.deepEqual(calls, [
      `GET http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}`,
      `POST http://127.0.0.1:8080/api/v5/workspaces/${session.workspaceId}/assessments/${assessmentId}/weight-sensitivity-preview`,
    ]);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

test("BFF withholds paired deltas and rejects stale, mismatched or forged sensitivity", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  let posts = 0;
  globalThis.fetch = async (_input, init) => {
    if (init?.method === "POST") { posts++; return Response.json(pairedSensitivity); }
    return Response.json({ ...coreAssessment, version: 2, profile: pairedProfile });
  };
  try {
    assert.deepEqual(await previewPersonalWeightSensitivity(session, assessmentId, 1,
      pairedWeights.baselineWeights, pairedWeights.alternativeWeights), { kind: "conflict" });
    assert.deepEqual(await previewPersonalWeightSensitivity(session, assessmentId, 2,
      pairedWeights.baselineWeights, { SCIM: 100 }), { kind: "invalid" });
    assert.equal(posts, 0);
    const withheldComparison = { ...pairedComparison, candidates: [{ ...pairedComparison.candidates[0],
      hardVerdict: "UNRESOLVED", informationGaps: coreComparison.candidates[0].informationGaps }] };
    const withheld = { ...pairedSensitivity, comparison: withheldComparison,
      baseline: { ...pairedSensitivity.baseline, scores: [{ optionId: "fictional-plan",
        status: "UNRESOLVED_HARD_CONSTRAINTS", score: null, contributions: [] }] },
      alternative: { ...pairedSensitivity.alternative, scores: [{ optionId: "fictional-plan",
        status: "UNRESOLVED_HARD_CONSTRAINTS", score: null, contributions: [] }] },
      deltas: [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
        scoreDelta: null, capabilityDeltas: [] }] };
    globalThis.fetch = async (_input, init) => init?.method === "GET" ?
      Response.json({ ...coreAssessment, version: 2, profile: pairedProfile }) : Response.json(withheld);
    const result = await previewPersonalWeightSensitivity(session, assessmentId, 2,
      pairedWeights.baselineWeights, pairedWeights.alternativeWeights);
    assert.equal(result.kind, "preview");
    if (result.kind === "preview") {
      assert.equal(result.preview.candidates[0].scoreDelta, null);
      assert.deepEqual(result.preview.candidates[0].capabilityDeltas, []);
    }
    for (const invalid of [
      { ...pairedSensitivity, rankingPerformed: true },
      { ...pairedSensitivity, winnerId: "fictional-plan" },
      { ...pairedSensitivity, deltas: [{ ...pairedSensitivity.deltas[0], scoreDelta: -39 }] },
      { ...pairedSensitivity, alternative: { ...pairedSensitivity.alternative,
        weights: pairedSensitivity.baseline.weights } },
      { ...pairedSensitivity, comparison: { ...pairedComparison,
        workspaceId: "70000000-0000-4000-8000-000000000002" } },
      { ...withheld, deltas: [{ ...withheld.deltas[0], scoreDelta: 0 }] },
    ]) {
      globalThis.fetch = async (_input, init) => init?.method === "GET" ?
        Response.json({ ...coreAssessment, version: 2, profile: pairedProfile }) : Response.json(invalid);
      await assert.rejects(previewPersonalWeightSensitivity(session, assessmentId, 2,
        pairedWeights.baselineWeights, pairedWeights.alternativeWeights), /response is invalid/);
    }
    globalThis.fetch = async (_input, init) => init?.method === "GET" ?
      Response.json({ ...coreAssessment, version: 2, profile: pairedProfile }) :
      Response.json({ ...pairedSensitivity, comparison: { ...pairedComparison, assessmentVersion: 3 } });
    assert.deepEqual(await previewPersonalWeightSensitivity(session, assessmentId, 2,
      pairedWeights.baselineWeights, pairedWeights.alternativeWeights), { kind: "conflict" });
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});
