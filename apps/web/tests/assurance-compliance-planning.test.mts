import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { criticalities } from "../src/lib/assessment/capabilities.ts";
import { clientTypes, complianceTargets, complianceScopeStatuses, populations } from "../src/lib/assessment/evaluation-context.ts";
import { assuranceExpectations, assurancePlanningBinding, assurancePlanningByteLimit, assurancePlanningFromCore, assurancePlanningValues } from "../src/lib/assessment/assurance-compliance-planning.ts";
import { readPersonalAssurancePlanning } from "../src/lib/auth/core-client.ts";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";
import { assuranceAt, assuranceWorkspaceId, assuranceAssessmentId, assuranceValues, assuranceProfile, assuranceFixture } from "./fixtures/assurance-compliance-planning.mts";

const binding = (values = assuranceValues(), expectedVersion = 7) => assurancePlanningBinding(assuranceWorkspaceId, assuranceAssessmentId, expectedVersion, values);
const session = { workspaceId: assuranceWorkspaceId, issuer: "http://localhost:8081", subject: "synthetic-assurance-owner", email: null, displayName: null, authenticatedAt: new Date(assuranceAt) };

test("Assurance consumer replays every target subset/scope/label without mapping standards or verifying compliance", () => {
  for (const assuranceExpectation of assuranceExpectations) for (const scope of complianceScopeStatuses) for (let mask = 0; mask < 64; mask++) {
    const values = assuranceValues(); values.assuranceExpectation = assuranceExpectation; values.complianceScopeStatus = scope;
    values.complianceTargets = complianceTargets.filter((_, i) => mask & 1 << i);
    const preview = assurancePlanningFromCore(assuranceFixture(values), binding(values));
    assert.equal(preview.status, "NEEDS_INFORMATION"); assert.equal(preview.assuranceItems[0].status, "INPUT_CLARIFICATION_NEEDED");
    assert.equal(preview.complianceItems.length, values.complianceTargets.length);
    for (const key of ["workspaceId", "assessmentId", "assuranceVerified", "complianceVerified", "publicationReady", "sourceUrl", "winner"]) assert.equal(key in preview, false);
  }
});
test("Every client/population subset preserves machine-only, human and unresolved scope without wildcard assumptions", () => {
  for (let c = 0; c < 8; c++) for (let u = 0; u < 64; u++) {
    const values = assuranceValues(); values.clients = clientTypes.filter((_, i) => c & 1 << i); values.populations = populations.filter((_, i) => u & 1 << i);
    const preview = assurancePlanningFromCore(assuranceFixture(values), binding(values));
    assert.equal(preview.humanScope, c === 4 ? "MACHINE_ONLY" : c > 0 && u > 0 ? "HUMAN_SCOPE_RECORDED" : "SCOPE_UNRESOLVED");
    for (const index of [1, 2, 3, 4]) assert.equal(preview.assuranceItems[index].status === "NOT_APPLIED", c === 4);
    assert.equal(preview.assuranceItems[5].status, c & 4 ? "EVIDENCE_NEEDED" : c === 0 ? "INPUT_CLARIFICATION_NEEDED" : "NOT_APPLIED");
  }
});
test("All625 independent control combinations keep requirement intent separate from generic assurance labels", () => {
  for (const multiFactorAuthentication of criticalities) for (const phishingResistance of criticalities)
    for (const nonExportableKeys of criticalities) for (const stepUpAuthentication of criticalities) {
      const values = assuranceValues(); values.controls = { multiFactorAuthentication, phishingResistance, nonExportableKeys, stepUpAuthentication };
      const preview = assurancePlanningFromCore(assuranceFixture(values), binding(values));
      assert.equal(preview.assuranceItems[2].status, Object.values(values.controls).some(c => c === "UNKNOWN" || c === "FORBIDDEN") ? "INPUT_CLARIFICATION_NEEDED" : "EVIDENCE_NEEDED");
    }
});
test("Saved v6 parsing has no missing-field defaults and binding freezes only scoped enums", () => {
  const profile = assuranceProfile(), original = structuredClone(profile);
  assert.deepEqual(assurancePlanningValues(profile), binding().values); assert.deepEqual(profile, original);
  for (const key of ["assurance", "authenticationControls", "complianceScopeStatus"]) {
    const malformed = structuredClone(profile); delete (malformed.security as Record<string, unknown>)[key]; assert.equal(assurancePlanningValues(malformed), null);
  }
  for (const malformed of [
    { ...profile, security: { ...profile.security, authenticationControls: null } },
    { ...profile, security: { ...profile.security, authenticationControls: { ...profile.security.authenticationControls, phishingResistance: "yes" } } },
    { ...profile, security: { ...profile.security, complianceTargets: ["GDPR", "GDPR"] } },
    { ...profile, application: { clients: ["UNKNOWN"] } }, { ...profile, audience: { populations: "everyone" } },
    { ...profile, security: { ...profile.security, multiFactorAuthentication: null } },
  ]) assert.equal(assurancePlanningValues(malformed), null);
  const values = assuranceValues(), bound = binding(values); values.clients.length = 0; values.controls.multiFactorAuthentication = "FORBIDDEN";
  assert.equal(bound.values.clients.length, 2); assert.equal(bound.values.controls.multiFactorAuthentication, "REQUIRED");
  for (const version of [-1, 0.1, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => binding(assuranceValues(), version));
  for (const id of ["../other", "not-a-uuid"]) assert.throws(() => assurancePlanningBinding(id, assuranceAssessmentId, 7, assuranceValues()));
  assert.throws(() => binding({ ...assuranceValues(), extra: "private" } as ReturnType<typeof assuranceValues>));
});
test("Strict consumer refuses complete valid-shaped row, binding, scope, narrative and authority substitutions", () => {
  const changes: ((r: ReturnType<typeof assuranceFixture>) => void)[] = [r => r.assessmentVersion++, r => r.workspaceId = "70000000-0000-4000-8000-000000000002", r => r.assessmentId = "80000000-0000-4000-8000-000000000002",
    r => r.inputs.assuranceExpectation = "BASELINE", r => r.inputs.controls.multiFactorAuthentication = "NOT_REQUIRED", r => r.inputs.clients.pop(), r => r.inputs.populations = [],
    r => r.assuranceItems.reverse(), r => r.assuranceItems.pop(), r => r.assuranceItems[1] = r.assuranceItems[0], r => r.assuranceItems[2].status = "NOT_APPLIED",
    r => r.assuranceItems[0].question = "All requirements met", r => r.assuranceItems[0].reasonCode = "EXPECTATION_UNRECORDED", r => r.complianceItems.pop(), r => r.complianceItems.reverse(),
    r => r.complianceItems[0].status = "EVIDENCE_NEEDED", r => r.complianceScopeCheck.outcome = "NOT_APPLIED", r => r.complianceScopeCheck.explanation = "Certified provider",
    r => r.complianceQuestions.reverse(), r => r.deferredBoundaries.pop(), r => r.checkedPaths.pop(), r => r.policyVersion = "other-policy", r => r.humanScope = "MACHINE_ONLY",
    r => r.evaluatedAt = "2026-02-30T12:00:00Z", r => r.evaluatedAt = "2026-10-06T12:00:00+00:00"];
  for (const mutate of changes) { const raw = assuranceFixture(); mutate(raw); assert.throws(() => assurancePlanningFromCore(raw, binding()), /Invalid assurance/); }
  for (const [flag, value] of Object.entries(assuranceFixture())) if (value === false) assert.throws(() => assurancePlanningFromCore({ ...assuranceFixture(), [flag]: true }, binding()));
  for (const field of ["winner", "formalAssuranceLevel", "sourceUrl", "ownerSubject", "scopeDescription"]) assert.throws(() => assurancePlanningFromCore({ ...assuranceFixture(), [field]: "forged" }, binding()));
  assert.equal(assurancePlanningFromCore(Object.fromEntries(Object.entries(assuranceFixture()).reverse()), binding()).evaluatedAt, assuranceAt);
  const nano = assuranceFixture(); nano.evaluatedAt = "2026-10-06T12:00:00.123456789Z"; assert.equal(assurancePlanningFromCore(nano, binding()).evaluatedAt, nano.evaluatedAt);
  const changedClock = assuranceFixture(); changedClock.evaluatedAt = "2026-10-06T12:00:01Z";
  const clockPreview = assurancePlanningFromCore(changedClock, binding()); assert.equal(clockPreview.status, "NEEDS_INFORMATION");
  assert.deepEqual(clockPreview.assuranceItems, assurancePlanningFromCore(assuranceFixture(), binding()).assuranceItems);
});
test("Assurance BFF uses one session-owned input-free bounded Core GET; invalid requests never reach fetch", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-assurance-service-token-000000000000000000"; let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v1/workspaces/${assuranceWorkspaceId}/assessments/${assuranceAssessmentId}/assurance-compliance-planning-preflight`);
    assert.equal(init?.method, "GET"); assert.equal(init?.body, undefined); assert.equal(init?.redirect, "error"); assert.equal(init?.cache, "no-store"); assert.ok(init?.signal instanceof AbortSignal);
    assert.deepEqual(init?.headers, { Authorization: `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`, "X-AuthWeave-Oidc-Issuer": session.issuer, "X-AuthWeave-Oidc-Subject": session.subject });
    return Response.json(assuranceFixture());
  };
  try {
    const result = await readPersonalAssurancePlanning(session, assuranceAssessmentId, 7, assuranceValues()); assert.equal(result.assessmentVersion, 7); assert.equal(calls, 1);
    for (const id of ["../other", "not-an-id"]) await assert.rejects(readPersonalAssurancePlanning(session, id, 7, assuranceValues()));
    await assert.rejects(readPersonalAssurancePlanning({ ...session, workspaceId: "../other" }, assuranceAssessmentId, 7, assuranceValues()));
    await assert.rejects(readPersonalAssurancePlanning({ ...session, subject: "" }, assuranceAssessmentId, 7, assuranceValues()));
    delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; await assert.rejects(readPersonalAssurancePlanning(session, assuranceAssessmentId, 7, assuranceValues())); assert.equal(calls, 1);
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});
test("Assurance BFF refuses stale, unavailable, malformed, redirected and oversized replies without showing upstream text", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-assurance-service-token-000000000000000000";
  try {
    for (const response of [Response.json(assuranceFixture(assuranceValues(), 8)), new Response("Private upstream detail", { status: 403 }), new Response("Private upstream detail", { status: 503 }),
      new Response(null, { status: 302, headers: { Location: "https://external.invalid" } }), new Response("not-json"), new Response("x".repeat(assurancePlanningByteLimit + 1)),
      Response.json(assuranceFixture(), { headers: { "Content-Length": String(assurancePlanningByteLimit + 1) } })]) {
      globalThis.fetch = async () => response; await assert.rejects(readPersonalAssurancePlanning(session, assuranceAssessmentId, 7, assuranceValues()), error => error instanceof Error && !error.message.includes("Private upstream"));
    }
    let cancelled = false;
    globalThis.fetch = async () => new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array(assurancePlanningByteLimit + 1)); }, cancel() { cancelled = true; } }));
    await assert.rejects(readPersonalAssurancePlanning(session, assuranceAssessmentId, 7, assuranceValues())); assert.equal(cancelled, true);
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});
test("Review UI explains saved labels, seven investigations, partial targets and read-only scope without private data or certification claims", async () => {
  const { AssuranceCompliancePlanning, AssuranceCompliancePlanningUnavailable } = await assessmentUiComponents();
  const render = (values = assuranceValues(), editable = true) => renderToStaticMarkup(createElement(AssuranceCompliancePlanning, { preview: assurancePlanningFromCore(assuranceFixture(values), binding(values)), editable }));
  const html = render();
  for (const text of ["what still needs evidence?", "Saved assessment version 7", "not a standards checklist", "not unsaved form selections", "does not automatically set MFA", "Enrollment and recovery",
    "Sessions and reauthentication", "Workload identity", "Federation and trust boundaries", "SOC 2", "Other (needs definition)", "Edit scope and independent controls", "Edit the MFA requirement"]) assert.ok(html.includes(text), text);
  assert.equal((html.match(/<article/g) ?? []).length, 7);
  for (const text of ["Synthetic private", "Bearer", "<form", "<select", assuranceWorkspaceId, assuranceAssessmentId, "100% complete", "Certified provider"]) assert.equal(html.includes(text), false);
  const values = assuranceValues(); values.complianceScopeStatus = "UNKNOWN";
  const partial = render(values); assert.ok(partial.includes("SOC 2")); assert.ok(partial.includes("Confirm the requirements scope first"));
  values.clients = ["MACHINE_TO_MACHINE"]; values.populations = []; values.complianceScopeStatus = "NONE_IDENTIFIED"; values.complianceTargets = [];
  const machine = render(values, false); assert.equal((machine.match(/Flow not selected/g) ?? []).length, 4); assert.ok(machine.includes("No target labels are recorded"));
  assert.equal(machine.includes("Edit scope"), false); assert.equal(machine.includes("Edit the MFA"), false);
  const unavailable = renderToStaticMarkup(createElement(AssuranceCompliancePlanningUnavailable)); assert.ok(unavailable.includes("investigation unavailable")); assert.ok(!unavailable.includes("SOC 2"));
});
