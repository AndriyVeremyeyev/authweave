import assert from "node:assert/strict";
import { test } from "node:test";
import { criticalities } from "../src/lib/assessment/capabilities.ts";
import { lifecycleV2Conditions, lifecycleV2PatternConditions, lifecycleGroupConditions, lifecycleV2PreviewFromCore,
  parseLifecycleV2Form, validateLifecycleV2Input, type LifecycleGroupStrategy } from "../src/lib/assessment/provisioning-lifecycle-v2.ts";
import type { LifecyclePattern } from "../src/lib/assessment/provisioning-lifecycle.ts";
import { lifecycleAssessmentId, lifecycleWorkspaceId, lifecycleV2Fixture, lifecycleV2Input, lifecycleRequirements } from "./fixtures/provisioning-lifecycle-v2.mts";
import { previewPersonalProvisioningLifecycleV2 } from "../src/lib/auth/core-client.ts";
import { prerequisiteProfile } from "./fixtures/architecture-prerequisites.mts";

const binding = { workspaceId: lifecycleWorkspaceId, assessmentId: lifecycleAssessmentId, input: lifecycleV2Input, requirements: lifecycleRequirements };
test("v2 consumer replays 1500 criticality/pattern/group combinations without substituting a bridge for SCIM", () => {
  let combinations = 0;
  for (const patternId of Object.keys(lifecycleV2PatternConditions) as LifecyclePattern[]) for (const groupStrategy of Object.keys(lifecycleGroupConditions) as LifecycleGroupStrategy[])
    for (const scim of criticalities) for (const justInTimeProvisioning of criticalities) for (const groupSynchronization of criticalities) {
      const input = { ...lifecycleV2Input, patternId, groupStrategy, declarations: Object.fromEntries(lifecycleV2Conditions(patternId, groupStrategy).map(id => [id, "SATISFIED" as const])) };
      const requirements = { scim, justInTimeProvisioning, groupSynchronization };
      const result = lifecycleV2PreviewFromCore(lifecycleV2Fixture(input, requirements), { ...binding, input, requirements }); combinations++;
      const groupCheck = result.analysis.requirementChecks[2];
      if (scim === "REQUIRED" && patternId === "JIT_LOGIN") assert.equal(result.analysis.requirementChecks[0].outcome, "CONDITIONALLY_NOT_SATISFIED");
      if (["REQUIRED", "FORBIDDEN"].includes(groupSynchronization) && groupStrategy === "UNKNOWN") assert.equal(groupCheck.reasonCode, "GROUP_STRATEGY_UNKNOWN");
      if (groupSynchronization === "REQUIRED" && groupStrategy === "NONE") assert.equal(groupCheck.outcome, "CONDITIONALLY_NOT_SATISFIED");
      if (groupSynchronization === "FORBIDDEN" && ["SCIM_GROUPS", "APPLICATION_BRIDGE"].includes(groupStrategy)) assert.equal(groupCheck.outcome, "CONDITIONALLY_NOT_SATISFIED");
      if (patternId === "JIT_LOGIN" && groupStrategy === "SCIM_GROUPS") assert.equal(result.analysis.designChecks[0].reasonCode, "SCIM_GROUPS_REQUIRE_SCIM_PATTERN");
      assert.equal(result.analysis.conditionChecks.length, lifecycleV2Conditions(patternId, groupStrategy).length);
      assert.deepEqual(Object.keys(result).sort(), ["analysis", "assessmentVersion"]);
    }
  assert.equal(combinations, 1500);
});
test("v2 form requires an explicit group choice and accepts only current scoped duplicate-free conditions", () => {
  const base = "expectedVersion=2&patternId=SCIM_PUSH&groupStrategy=UNKNOWN";
  assert.deepEqual(parseLifecycleV2Form(new URLSearchParams(base)), lifecycleV2Input);
  for (const suffix of ["&GROUP_SOURCE_AND_MEMBERSHIP_MAPPING=SATISFIED", "&OFFBOARDING_AND_ACCESS_REVOCATION=SATISFIED", "&requirements=private",
    "&groupStrategy=NONE", "&SCIM_USER_OPERATIONS=UNKNOWN&SCIM_USER_OPERATIONS=SATISFIED", "&SCIM_USER_OPERATIONS=VERIFIED"]) assert.throws(() => parseLifecycleV2Form(new URLSearchParams(base + suffix)));
  for (const body of [base.replace("=2&", "=02&"), base.replace("=2&", "=9007199254740992&"), base.replace("&groupStrategy=UNKNOWN", ""),
    base.replace("UNKNOWN", "__proto__"), base.replace("SCIM_PUSH", "toString"), `${base}&expectedVersion=2`,
    base.replace("UNKNOWN", "SCIM_GROUPS") + "&APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY=SATISFIED",
    base.replace("UNKNOWN", "APPLICATION_BRIDGE") + "&SCIM_GROUP_OPERATIONS=SATISFIED"]) assert.throws(() => parseLifecycleV2Form(new URLSearchParams(body)));
  for (const groupStrategy of ["SCIM_GROUPS", "APPLICATION_BRIDGE"] as const) {
    const params = new URLSearchParams(base); params.set("groupStrategy", groupStrategy);
    for (const condition of lifecycleV2Conditions("SCIM_PUSH", groupStrategy)) params.append(condition, "UNKNOWN");
    const parsed = parseLifecycleV2Form(params); validateLifecycleV2Input(parsed); assert.equal(Object.keys(parsed.declarations).length, 13);
  }
  for (const input of [{ ...lifecycleV2Input, expectedVersion: "2" }, { ...lifecycleV2Input, expectedVersion: true },
    { ...lifecycleV2Input, declarations: [] }, { ...lifecycleV2Input, requirements: lifecycleRequirements }]) assert.throws(() => validateLifecycleV2Input(input as never));
});
test("offboarding answers are independent and hard mismatches never hide unknown group or token gaps", () => {
  const input = { ...lifecycleV2Input, declarations: { ACCOUNT_DISABLE_AND_LOGIN_BLOCK: "SATISFIED" as const, APPLICATION_SESSION_INVALIDATION: "NOT_SATISFIED" as const } };
  const result = lifecycleV2PreviewFromCore(lifecycleV2Fixture(input), { ...binding, input });
  assert.equal(result.analysis.status, "CONDITIONALLY_DOES_NOT_MATCH");
  assert.equal(result.analysis.conditionChecks.find(c => c.conditionId === "TOKEN_REVOCATION_OR_BOUNDED_EXPIRY")!.outcome, "UNKNOWN");
  assert.equal(result.analysis.designChecks[0].outcome, "UNKNOWN");
});
test("v2 refuses identity, strategy, separate offboarding, inventory, concept URL and readiness forgeries", () => {
  const mutations: ((raw: ReturnType<typeof lifecycleV2Fixture>) => void)[] = [
    raw => { raw.assessmentVersion++; }, raw => { raw.workspaceId = lifecycleAssessmentId; }, raw => { raw.assessmentId = lifecycleWorkspaceId; },
    raw => { raw.scope = "FULL_COVERAGE"; }, raw => { raw.policyVersion = "provisioning-lifecycle-design-1"; }, raw => { raw.analysisBasis = "OBSERVED"; },
    raw => { raw.evaluatedAt = "2026-02-30T12:00:00Z"; }, raw => { raw.evaluatedAt = "private"; },
    raw => { raw.analysis.groupStrategy = "NONE"; }, raw => { raw.analysis.requirements.scim = "PREFERRED"; },
    raw => { raw.analysis.declarations.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY = "SATISFIED"; }, raw => { raw.analysis.status = "CONDITIONALLY_MATCHES"; },
    raw => { raw.analysis.designChecks[0].reasonCode = "GROUP_TRANSPORT_PLANNED"; }, raw => { raw.analysis.designChecks.pop(); },
    raw => { raw.analysis.requirementChecks[0].outcome = "NOT_APPLIED"; }, raw => { raw.analysis.conditionChecks.pop(); }, raw => { raw.analysis.conditionChecks.reverse(); },
    raw => { raw.conditionDefinitions[0].description = "Private upstream text"; }, raw => { raw.conditionDefinitions.pop(); },
    raw => { (raw.groupStrategies as unknown as unknown[]).reverse(); }, raw => { (raw.groupStrategies[2].references as unknown as string[])[0] = "https://evil.example.invalid"; },
    raw => { raw.offboardingReferences[0] = "https://evil.example.invalid"; }, raw => { raw.checkedProfilePaths.pop(); }, raw => { raw.deferredBoundaries.pop(); },
    ...["configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified", "accessRevocationVerified", "writesPerformed", "publicationReady", "recommendationReady"].map(flag =>
      (raw: ReturnType<typeof lifecycleV2Fixture>) => { (raw as unknown as Record<string, unknown>)[flag] = true; }),
    raw => { (raw as unknown as Record<string, unknown>).private = "upstream"; },
  ];
  for (const mutate of mutations) { const raw = lifecycleV2Fixture(); mutate(raw); assert.throws(() => lifecycleV2PreviewFromCore(raw, binding)); }
  const reordered = Object.fromEntries(Object.entries(lifecycleV2Fixture()).reverse());
  assert.equal(lifecycleV2PreviewFromCore(reordered, binding).analysis.status, "NEEDS_INFORMATION");
});
test("v2 Core client reads saved canonical v6, forwards only scoped design with server identity and fails closed", async () => {
  const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-lifecycle-v2-token-000000000000000";
  const session = { workspaceId: lifecycleWorkspaceId, issuer: "http://localhost:8081", subject: "synthetic-owner", email: null, displayName: null, authenticatedAt: new Date() };
  let version = 2, responseStatus = 200, response: Response | undefined, calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++; const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject); assert.equal(headers["X-AuthWeave-Oidc-Issuer"], session.issuer);
    assert.equal(headers.Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.ok(init?.signal instanceof AbortSignal);
    if (init?.method === "GET") {
      assert.equal(url, `http://127.0.0.1:8080/api/v6/workspaces/${lifecycleWorkspaceId}/assessments/${lifecycleAssessmentId}`);
      return Response.json({ id: lifecycleAssessmentId, workspaceId: lifecycleWorkspaceId, status: "DRAFT", version, profileSchemaVersion: 6,
        profile: { ...prerequisiteProfile, provisioning: lifecycleRequirements } });
    }
    assert.equal(url, `http://127.0.0.1:8080/api/v2/workspaces/${lifecycleWorkspaceId}/assessments/${lifecycleAssessmentId}/provisioning-lifecycle-preview`);
    assert.equal(init?.method, "POST"); assert.deepEqual(JSON.parse(String(init?.body)), lifecycleV2Input);
    return response ?? (responseStatus === 200 ? Response.json(lifecycleV2Fixture()) : new Response("Private upstream", { status: responseStatus }));
  };
  try {
    assert.equal((await previewPersonalProvisioningLifecycleV2(session, lifecycleAssessmentId, lifecycleV2Input)).kind, "preview"); assert.equal(calls, 2);
    for (const [status, kind] of [[400, "invalid"], [404, "not-found"], [409, "conflict"]] as const) {
      responseStatus = status; assert.equal((await previewPersonalProvisioningLifecycleV2(session, lifecycleAssessmentId, lifecycleV2Input)).kind, kind);
    }
    responseStatus = 503; await assert.rejects(previewPersonalProvisioningLifecycleV2(session, lifecycleAssessmentId, lifecycleV2Input));
    const forged = lifecycleV2Fixture(); forged.groupSynchronizationVerified = true;
    for (const value of [Response.json(forged), new Response("private".repeat(5000)), new Response("{}", { headers: { "content-length": "40000" } })]) {
      response = value; await assert.rejects(previewPersonalProvisioningLifecycleV2(session, lifecycleAssessmentId, lifecycleV2Input));
    }
    version = 3; const count = calls;
    assert.equal((await previewPersonalProvisioningLifecycleV2(session, lifecycleAssessmentId, lifecycleV2Input)).kind, "conflict"); assert.equal(calls, count + 1);
    await assert.rejects(previewPersonalProvisioningLifecycleV2(session, lifecycleAssessmentId, { ...lifecycleV2Input, groupStrategy: "foreign" } as never)); assert.equal(calls, count + 1);
  } finally { globalThis.fetch = previousFetch; if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken; }
});
