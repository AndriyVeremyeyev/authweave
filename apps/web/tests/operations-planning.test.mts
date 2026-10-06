import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { hostingPreferences, deploymentTargets, identityExpertiseLevels, budgetSensitivities,
  operationsPlanningBinding, operationsPlanningFromCore, operationsPlanningValues, operationsPlanningByteLimit } from "../src/lib/assessment/operations-planning.ts";
import { readPersonalOperationsPlanning } from "../src/lib/auth/core-client.ts";
import { usageMetrics } from "../src/lib/assessment/usage-planning.ts";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";
import { operationsValues, operationsProfile, operationsFixture, operationsAt, operationsWorkspaceId, operationsAssessmentId } from "./fixtures/operations-planning.mts";

const binding = (values = operationsValues(), expectedVersion = 7) => ({ workspaceId: operationsWorkspaceId,
  assessmentId: operationsAssessmentId, expectedVersion, values });
const session = { workspaceId: operationsWorkspaceId, issuer: "http://localhost:8081", subject: "synthetic-operations-owner",
  email: null, displayName: null, authenticatedAt: new Date(operationsAt) };

test("Operations consumer independently replays all 384 saved preference contexts without an exclusion or winner", () => {
  let count = 0;
  for (const hosting of hostingPreferences) for (const deploymentTarget of deploymentTargets)
    for (const identityExpertise of identityExpertiseLevels) for (const budgetSensitivity of budgetSensitivities) {
      const values = operationsValues(); Object.assign(values.inputs, { hosting, deploymentTarget, identityExpertise, budgetSensitivity });
      const result = operationsPlanningFromCore(operationsFixture(values), binding(values));
      assert.deepEqual(result.inputs, values.inputs); assert.equal(result.options.length, 2);
      assert.equal(result.options[0].hostingAlignment, hosting === "UNKNOWN" ? "PREFERENCE_UNKNOWN" : hosting === "NO_PREFERENCE" ? "NO_PREFERENCE"
        : hosting === "MANAGED" ? "PREFERENCE_ALIGNED" : "PREFERENCE_DIFFERS");
      assert.equal("winner" in result, false); assert.equal("workspaceId" in result, false); count++;
    }
  assert.equal(count, 384);
});

test("Operations usage presence preserves zero, unknown, assumptions and the exact Java whitespace policy", () => {
  for (const [scope, missing] of [["", true], ["\u2003", true], ["\u00a0", false], ["\ufeff", false]] as const) {
    for (const basis of ["ASSUMED", "OBSERVED"] as const) {
      const values = operationsValues(); values.usagePlanning = { scopeDescription: scope, assumptions: [],
        volumes: Object.fromEntries(usageMetrics.map(m => [m.key, { basis, value: 0 }])) };
      const result = operationsPlanningFromCore(operationsFixture(values), binding(values));
      assert.equal(result.usageInputs.missingPaths.includes("operations.usagePlanning.scopeDescription"), missing);
      assert.equal(result.usageInputs.missingPaths.includes("operations.usagePlanning.assumptions"), basis === "ASSUMED");
      assert.equal(result.status, missing || basis === "ASSUMED" ? "NEEDS_INFORMATION" : "INPUTS_RECORDED");
      assert.equal(result.usageInputs.recordedMetrics.length, 4); assert.equal("volumes" in result.usageInputs, false);
    }
  }
  const empty = operationsValues(); empty.usagePlanning = { scopeDescription: "", assumptions: [], volumes: {} };
  assert.deepEqual(operationsPlanningFromCore(operationsFixture(empty), binding(empty)).usageInputs.recordedMetrics, []);
  const large = operationsValues(); large.usagePlanning.volumes.MONTHLY_ACTIVE_USERS!.value = Number.MAX_SAFE_INTEGER;
  assert.equal(operationsPlanningFromCore(operationsFixture(large), binding(large)).usageInputs.recordedMetrics.length, 1);
});

test("Operations binding refuses unsafe, missing or extra owner inputs without defaults and does not mutate its sources", () => {
  const profile = operationsProfile(), before = structuredClone(profile);
  const values = operationsPlanningValues(profile); assert.ok(values); const owned = operationsPlanningBinding(operationsWorkspaceId, operationsAssessmentId, 7, values);
  values.usagePlanning.volumes.MONTHLY_ACTIVE_USERS!.value = 123;
  assert.equal(owned.values.usagePlanning.volumes.MONTHLY_ACTIVE_USERS!.value, 0); assert.deepEqual(profile, before);
  for (const version of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1, Infinity, NaN]) assert.throws(() => operationsPlanningBinding(operationsWorkspaceId, operationsAssessmentId, version, values));
  for (const profile of [{}, { operations: { ...before.operations, hosting: "invented" } }, { operations: { ...before.operations, vendor: "claimed" } },
    { operations: { ...before.operations, usagePlanning: null } }, { operations: { ...before.operations, usagePlanning: { scopeDescription: "", assumptions: [], volumes: { UNKNOWN: { basis: "OBSERVED", value: 0 } } } } }]) {
    assert.equal(operationsPlanningValues(profile), null);
  }
  const raw = operationsFixture(); const untouched = structuredClone(raw);
  const result = operationsPlanningFromCore(raw, binding()); result.options[0].tradeoffs[0] = "Local mutation";
  assert.deepEqual(raw, untouched); assert.notEqual(operationsPlanningFromCore(raw, binding()).options[0].tradeoffs[0], "Local mutation");
});

test("Operations consumer rejects scope, inventory, typed status, narrative and authority substitutions", () => {
  type Raw = ReturnType<typeof operationsFixture>;
  const changes: ((r: Raw) => void)[] = [r => r.workspaceId = "70000000-0000-4000-8000-000000000002", r => r.assessmentId = "80000000-0000-4000-8000-000000000002",
    r => r.assessmentVersion++, r => r.inputs.hosting = "SELF_HOSTED", r => r.inputs.deploymentTarget = "AWS", r => r.status = "INPUTS_RECORDED",
    r => r.usageInputs.status = "INPUTS_RECORDED", r => r.usageInputs.recordedMetrics = [], r => r.missingPaths.reverse(),
    r => r.options.reverse(), r => r.options.pop(), r => r.options[1] = r.options[0],
    r => r.options[0].hostingAlignment = "PREFERENCE_DIFFERS", r => r.options[1].supportPlanning = "RESPONSIBILITY_PLAN_NEEDED",
    r => r.options[0].budgetPlanning = "BUDGET_SCOPE_UNDEFINED", r => r.options[0].advantages[0] = "Always free",
    r => r.options[1].responsibilities[0] = "No backups needed", r => r.references[0] = "javascript:alert(1)",
    r => r.checkedPaths.pop(), r => r.sharedResponsibilities.reverse(), r => r.deferredBoundaries.pop(), r => r.policyVersion = "other-policy",
    r => r.hostingExplanation = "Winner selected", r => r.evaluatedAt = "2026-02-30T12:00:00Z", r => r.evaluatedAt = "2026-10-06T12:00:00+00:00"];
  for (const mutate of changes) { const raw = operationsFixture(); mutate(raw); assert.throws(() => operationsPlanningFromCore(raw, binding()), /Invalid operations/); }
  for (const [flag, value] of Object.entries(operationsFixture())) if (value === false) {
    assert.throws(() => operationsPlanningFromCore({ ...operationsFixture(), [flag]: true }, binding()));
  }
  for (const field of ["winner", "price", "scopeDescription", "volumes", "providerId"]) assert.throws(() => operationsPlanningFromCore({ ...operationsFixture(), [field]: "forged" }, binding()));
  const reordered = Object.fromEntries(Object.entries(operationsFixture()).reverse());
  assert.equal(operationsPlanningFromCore(reordered, binding()).evaluatedAt, operationsAt);
  const nano = operationsFixture(); nano.evaluatedAt = "2026-10-06T12:00:00.123456789Z";
  assert.equal(operationsPlanningFromCore(nano, binding()).evaluatedAt, nano.evaluatedAt);
});

test("Operations BFF uses only the session-owned fixed input-free Core GET and a bounded response", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-operations-service-token-000000000000000000";
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v1/workspaces/${operationsWorkspaceId}/assessments/${operationsAssessmentId}/operations-planning-preflight`);
    assert.equal(init?.method, "GET"); assert.equal(init?.body, undefined); assert.equal(init?.redirect, "error"); assert.equal(init?.cache, "no-store");
    assert.ok(init?.signal instanceof AbortSignal);
    assert.deepEqual(init?.headers, { Authorization: `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`,
      "X-AuthWeave-Oidc-Issuer": session.issuer, "X-AuthWeave-Oidc-Subject": session.subject });
    return Response.json(operationsFixture());
  };
  try {
    const result = await readPersonalOperationsPlanning(session, operationsAssessmentId, 7, operationsValues());
    assert.equal(result.assessmentVersion, 7); assert.equal(calls, 1);
    assert.equal(JSON.stringify(result).includes("Synthetic private"), false); assert.equal("pricingEvaluated" in result, false);
    for (const id of ["../other", "not-an-id"]) await assert.rejects(readPersonalOperationsPlanning(session, id, 7, operationsValues()));
    await assert.rejects(readPersonalOperationsPlanning({ ...session, workspaceId: "../other" }, operationsAssessmentId, 7, operationsValues()));
    await assert.rejects(readPersonalOperationsPlanning({ ...session, subject: "" }, operationsAssessmentId, 7, operationsValues()));
    delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    await assert.rejects(readPersonalOperationsPlanning(session, operationsAssessmentId, 7, operationsValues())); assert.equal(calls, 1);
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});

test("Operations BFF refuses stale, rejected, redirected, malformed and oversized replies without leaking upstream details", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-operations-service-token-000000000000000000";
  try {
    for (const response of [Response.json(operationsFixture(operationsValues(), 8)), new Response("Private upstream detail", { status: 403 }),
      new Response("Private upstream detail", { status: 503 }), new Response(null, { status: 302, headers: { Location: "https://external.invalid" } }),
      new Response("not-json", { headers: { "Content-Type": "application/json" } }),
      new Response("x".repeat(operationsPlanningByteLimit + 1)), Response.json(operationsFixture(), { headers: { "Content-Length": String(operationsPlanningByteLimit + 1) } })]) {
      globalThis.fetch = async () => response;
      await assert.rejects(readPersonalOperationsPlanning(session, operationsAssessmentId, 7, operationsValues()), error => error instanceof Error && !error.message.includes("Private upstream"));
    }
    let cancelled = false;
    globalThis.fetch = async () => new Response(new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(operationsPlanningByteLimit + 1)); }, cancel() { cancelled = true; } }));
    await assert.rejects(readPersonalOperationsPlanning(session, operationsAssessmentId, 7, operationsValues())); assert.equal(cancelled, true);
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});

test("Operations UI explains both models, input gaps and responsibilities without controls, raw private text or cost promises", async () => {
  const { OperationsPlanning, OperationsPlanningUnavailable } = await assessmentUiComponents();
  const html = renderToStaticMarkup(createElement(OperationsPlanning, { preview: operationsPlanningFromCore(operationsFixture(), binding()) }));
  for (const text of ["Managed identity service", "Self-hosted identity service", "not excluded", "Potential advantages", "Support planning", "Cost planning",
    "not a spending cap", "does not establish a free tier", "not IdP location", "Missing saved inputs", "Shared responsibility guidance", "currently read-only"]) assert.ok(html.includes(text), text);
  for (const text of ["Synthetic private", "Bearer", "<form", "<select", "Estimated monthly cost", operationsWorkspaceId, operationsAssessmentId]) assert.equal(html.includes(text), false, text);
  assert.equal((html.match(/<article/g) ?? []).length, 2); assert.ok(html.includes("lg:grid-cols-2"));
  const unavailable = renderToStaticMarkup(createElement(OperationsPlanningUnavailable));
  assert.ok(unavailable.includes("Operations planning comparison unavailable")); assert.ok(!unavailable.includes("Managed identity service"));
});
