import assert from "node:assert/strict";
import { test } from "node:test";
import { criticalities } from "../src/lib/assessment/capabilities.ts";
import { lifecycleConditions, lifecyclePreviewFromCore, parseLifecycleForm, provisioningRequirements, validateLifecycleInput,
  type LifecyclePattern } from "../src/lib/assessment/provisioning-lifecycle.ts";
import { lifecycleAssessmentId, lifecycleWorkspaceId, lifecycleFixture, lifecycleInput, lifecycleRequirements } from "./fixtures/provisioning-lifecycle.mts";
import { previewPersonalProvisioningLifecycle } from "../src/lib/auth/core-client.ts";
import { prerequisiteProfile } from "./fixtures/architecture-prerequisites.mts";

const binding = { workspaceId: lifecycleWorkspaceId, assessmentId: lifecycleAssessmentId, input: lifecycleInput, requirements: lifecycleRequirements };
test("lifecycle consumer replays all 375 saved criticality/pattern combinations without JIT substitution or group promotion", () => {
  for (const patternId of Object.keys(lifecycleConditions) as LifecyclePattern[]) for (const scim of criticalities) for (const justInTimeProvisioning of criticalities) for (const groupSynchronization of criticalities) {
    const input = { ...lifecycleInput, patternId, declarations: Object.fromEntries(lifecycleConditions[patternId].map(id => [id, "SATISFIED" as const])) };
    const requirements = { scim, justInTimeProvisioning, groupSynchronization };
    const result = lifecyclePreviewFromCore(lifecycleFixture(input, requirements), { ...binding, input, requirements });
    const checks = result.analysis.requirementChecks;
    if (scim === "REQUIRED" && patternId === "JIT_LOGIN") assert.equal(checks[0].outcome, "CONDITIONALLY_NOT_SATISFIED");
    if (["REQUIRED", "FORBIDDEN", "UNKNOWN"].includes(groupSynchronization)) assert.equal(checks[2].outcome, "UNKNOWN");
    assert.deepEqual(Object.keys(result).sort(), ["analysis", "assessmentVersion"]);
  }
});
test("form is scoped, duplicate-free and version-bound; omissions mean unknown", () => {
  const parsed = parseLifecycleForm(new URLSearchParams("expectedVersion=2&patternId=SCIM_PUSH"));
  assert.deepEqual(parsed, lifecycleInput);
  for (const body of ["expectedVersion=02&patternId=SCIM_PUSH", "expectedVersion=9007199254740992&patternId=SCIM_PUSH",
    "expectedVersion=2&expectedVersion=2&patternId=SCIM_PUSH", "expectedVersion=2&patternId=SCIM_PUSH&patternId=JIT_LOGIN",
    "expectedVersion=2&patternId=JIT_LOGIN&SCIM_USER_OPERATIONS=SATISFIED", "expectedVersion=2&patternId=SCIM_PUSH&workspaceId=spoof",
    "expectedVersion=2&patternId=SCIM_PUSH&SCIM_USER_OPERATIONS=VERIFIED", "expectedVersion=2&patternId=__proto__",
    "expectedVersion=2&patternId=SCIM_PUSH&SCIM_USER_OPERATIONS=UNKNOWN&SCIM_USER_OPERATIONS=SATISFIED"]) assert.throws(() => parseLifecycleForm(new URLSearchParams(body)));
  assert.throws(() => validateLifecycleInput({ ...parsed, expectedVersion: "2" } as never));
  assert.throws(() => validateLifecycleInput({ ...parsed, requirements: lifecycleRequirements } as never));
  assert.throws(() => provisioningRequirements({ provisioning: { ...lifecycleRequirements, observed: true } }));
});
test("consumer fails closed on binding, check, inventory, declarations, reference and readiness forgery", () => {
  const mutations: ((raw: ReturnType<typeof lifecycleFixture>) => void)[] = [
    raw => { raw.assessmentVersion++; }, raw => { raw.workspaceId = lifecycleAssessmentId; }, raw => { raw.assessmentId = lifecycleWorkspaceId; },
    raw => { raw.scope = "FULL_COVERAGE"; }, raw => { raw.policyVersion = "new"; }, raw => { raw.analysisBasis = "OBSERVED"; }, raw => { raw.evaluatedAt = "bad"; },
    raw => { raw.evaluatedAt = "2026-02-30T12:00:00Z"; },
    raw => { raw.analysis.requirements.scim = "PREFERRED"; }, raw => { raw.analysis.declarations.SCIM_USER_OPERATIONS = "SATISFIED"; },
    raw => { raw.analysis.status = "CONDITIONALLY_MATCHES"; }, raw => { raw.analysis.requirementChecks[0].reasonCode = "NO_REQUIREMENT"; },
    raw => { raw.analysis.conditionChecks.pop(); }, raw => { raw.analysis.conditionChecks.reverse(); }, raw => { raw.checkedProfilePaths.pop(); },
    raw => { raw.deferredBoundaries.pop(); }, raw => { raw.conditionDefinitions[0].description = "Private upstream details"; },
    raw => { (raw.patterns[0].references as string[])[0] = "https://evil.example.invalid"; }, raw => { (raw.patterns as unknown as unknown[]).reverse(); },
    raw => { (raw.patterns[0] as unknown as Record<string, unknown>).scimPlanned = false; },
    ...["configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified", "accessRevocationVerified", "writesPerformed", "publicationReady", "recommendationReady"].map(flag =>
      (raw: ReturnType<typeof lifecycleFixture>) => { (raw as unknown as Record<string, unknown>)[flag] = true; }),
  ];
  for (const mutate of mutations) { const raw = lifecycleFixture(); mutate(raw); assert.throws(() => lifecyclePreviewFromCore(raw, binding)); }
  const reordered = Object.fromEntries(Object.entries(lifecycleFixture()).reverse());
  assert.equal(lifecyclePreviewFromCore(reordered, binding).analysis.status, "NEEDS_INFORMATION");
});
test("BFF reads canonical v6 then previews with server identity and saved requirements; rejects stale or forged replies", async () => {
  const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-lifecycle-token-000000000000000000000";
  const session = { workspaceId: lifecycleWorkspaceId, issuer: "http://localhost:8081", subject: "synthetic-owner", email: null, displayName: null, authenticatedAt: new Date() };
  let version = 2, responseStatus = 200, forged = false, calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++;
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject); assert.equal(headers["X-AuthWeave-Oidc-Issuer"], session.issuer);
    assert.equal(headers.Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.ok(init?.signal instanceof AbortSignal);
    if (init?.method === "GET") {
      assert.equal(url, `http://127.0.0.1:8080/api/v6/workspaces/${lifecycleWorkspaceId}/assessments/${lifecycleAssessmentId}`);
      return Response.json({ id: lifecycleAssessmentId, workspaceId: lifecycleWorkspaceId, status: "DRAFT", version, profileSchemaVersion: 6,
        profile: { ...prerequisiteProfile, provisioning: lifecycleRequirements } });
    }
    assert.equal(url, `http://127.0.0.1:8080/api/v1/workspaces/${lifecycleWorkspaceId}/assessments/${lifecycleAssessmentId}/provisioning-lifecycle-preview`);
    assert.equal(init?.method, "POST"); assert.deepEqual(JSON.parse(String(init?.body)), lifecycleInput);
    const raw = lifecycleFixture(); if (forged) raw.analysis.requirements.scim = "PREFERRED";
    return responseStatus === 200 ? Response.json(raw) : new Response("Private upstream", { status: responseStatus });
  };
  try {
    assert.equal((await previewPersonalProvisioningLifecycle(session, lifecycleAssessmentId, lifecycleInput)).kind, "preview"); assert.equal(calls, 2);
    for (const [status, kind] of [[400, "invalid"], [404, "not-found"], [409, "conflict"]] as const) {
      responseStatus = status; assert.equal((await previewPersonalProvisioningLifecycle(session, lifecycleAssessmentId, lifecycleInput)).kind, kind);
    }
    responseStatus = 200; forged = true; await assert.rejects(previewPersonalProvisioningLifecycle(session, lifecycleAssessmentId, lifecycleInput));
    version = 3; const count = calls;
    assert.equal((await previewPersonalProvisioningLifecycle(session, lifecycleAssessmentId, lifecycleInput)).kind, "conflict"); assert.equal(calls, count + 1);
    await assert.rejects(previewPersonalProvisioningLifecycle(session, lifecycleAssessmentId, { ...lifecycleInput, patternId: "foreign" } as never)); assert.equal(calls, count + 1);
  } finally { globalThis.fetch = previousFetch; if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken; }
});
