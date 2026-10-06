import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { parseOperationalPreferencesForm, operationalPreferencesFormIssues, withOperationalPreferences,
  operationalPreferencesSaveMatches } from "../src/lib/assessment/operational-preferences.ts";
import { hostingPreferences, deploymentTargets, identityExpertiseLevels, budgetSensitivities } from "../src/lib/assessment/operations-planning.ts";
import { updatePersonalOperationalPreferences } from "../src/lib/auth/core-client.ts";
import { operationsProfile, operationsValues, operationsWorkspaceId as workspaceId, operationsAssessmentId as id } from "./fixtures/operations-planning.mts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { profileFormFixture } from "./fixtures/profile-save.mts";

test("operational preferences accept all 384 explicit choices without defaults or normalization", () => {
  let combinations = 0;
  for (const hosting of hostingPreferences) for (const deploymentTarget of deploymentTargets)
    for (const identityExpertise of identityExpertiseLevels) for (const budgetSensitivity of budgetSensitivities) {
      const values = { hosting, deploymentTarget, identityExpertise, budgetSensitivity };
      const form = new URLSearchParams({ expectedVersion: "7", ...values }), before = form.toString();
      assert.deepEqual(parseOperationalPreferencesForm(form), { expectedVersion: 7, values });
      assert.deepEqual(operationalPreferencesFormIssues(form), []); assert.equal(form.toString(), before); combinations++;
    }
  assert.equal(combinations, 384);
});

test("operational forms reject duplicate, missing, forged and noncanonical inputs unchanged", () => {
  const mutations = [(p: URLSearchParams) => p.append("workspaceId", workspaceId),
    (p: URLSearchParams) => p.append("usagePlanning", "forged"), (p: URLSearchParams) => p.append("budgetCap", "0"),
    (p: URLSearchParams) => p.append("expectedVersion", "0"), (p: URLSearchParams) => p.delete("expectedVersion"),
    ...["-1", "01", "1.0", "1e0", " 0", "9007199254740992"].map(value => (p: URLSearchParams) => p.set("expectedVersion", value))];
  for (const key of ["hosting", "deploymentTarget", "identityExpertise", "budgetSensitivity"]) {
    mutations.push(p => p.delete(key), p => p.append(key, p.get(key)!), p => p.set(key, "unknown"), p => p.set(key, ""));
  }
  for (const mutation of mutations) {
    const form = profileFormFixture("operations"); mutation(form); const before = form.toString();
    assert.throws(() => parseOperationalPreferencesForm(form));
    assert.ok(operationalPreferencesFormIssues(form).length); assert.equal(form.toString(), before);
  }
  const form = profileFormFixture("operations"); form.set("hosting", "unsupported");
  assert.equal(operationalPreferencesFormIssues(form)[0].fieldId, "operations-hosting");
});

test("operations patch preserves all saved v6 fields and explicitly clears preferences without losing usage", () => {
  const profile = { ...savedRequirementsFixture(), ...operationsProfile() }, before = structuredClone(profile);
  const values = parseOperationalPreferencesForm(profileFormFixture("operations")).values;
  const changed = withOperationalPreferences(profile, values);
  assert.deepEqual(changed, { ...before, operations: { ...before.operations, ...values } });
  assert.deepEqual(profile, before); assert.notEqual(changed, profile);
  ((changed.operations as Record<string, unknown>).usagePlanning as { assumptions: string[] }).assumptions.push("Changed copy");
  assert.deepEqual(profile, before);
  assert.throws(() => withOperationalPreferences({ ...profile, operations: { hosting: "UNKNOWN" } }, values));
  const forged = { ...values, budgetCap: "0" };
  assert.throws(() => withOperationalPreferences(profile, forged));
});

test("save matching preserves object values and array order but does not depend on object key order", () => {
  assert.equal(operationalPreferencesSaveMatches({ a: 0, b: [1, null] }, { b: [1, null], a: 0 }), true);
  for (const other of [{ a: 0, b: [null, 1] }, { a: 0, b: [1] }, { a: 0, b: [1, null], c: true },
    { a: "0", b: [1, null] }, { a: 0, b: {} }, null]) {
    assert.equal(operationalPreferencesSaveMatches({ a: 0, b: [1, null] }, other), false);
  }
});

test("operations BFF writes only four fields to the session-owned v6 draft and checks the full save reply", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-operations-write-token-000000000000000000";
  const session = { workspaceId, issuer: "http://localhost:8081", subject: "synthetic-operations-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  const profile = { ...savedRequirementsFixture(), ...operationsProfile() }, before = structuredClone(profile);
  const values = parseOperationalPreferencesForm(profileFormFixture("operations")).values;
  let calls: string[] = [], mutation: (value: Record<string, unknown>) => void = () => {};
  globalThis.fetch = async (url, init) => {
    calls.push(init!.method!); assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${workspaceId}/assessments/${id}${init?.method === "PUT" ? "/profile" : ""}`);
    assert.equal(init?.redirect, "error"); assert.equal(init?.cache, "no-store"); assert.ok(init?.signal instanceof AbortSignal);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    if (init?.method === "GET") return Response.json({ id, workspaceId, status: "DRAFT", version: 7, profileSchemaVersion: 6, profile });
    const payload = JSON.parse(String(init?.body));
    assert.equal(payload.expectedVersion, 7);
    assert.deepEqual(payload.profile, { ...before, operations: { ...before.operations, ...values } });
    mutation(payload.profile);
    return Response.json({ id, workspaceId, status: "DRAFT", version: 8, profileSchemaVersion: 6, profile: payload.profile });
  };
  try {
    assert.equal(await updatePersonalOperationalPreferences(session, id, 7, values), "saved");
    assert.deepEqual(calls, ["GET", "PUT"]); assert.deepEqual(profile, before);
    calls = []; assert.equal(await updatePersonalOperationalPreferences(session, id, 6, values), "conflict");
    assert.deepEqual(calls, ["GET"]);
    for (const corrupt of [(p: Record<string, unknown>) => { (p.operations as Record<string, unknown>).hosting = "MANAGED"; },
      (p: Record<string, unknown>) => { ((p.operations as Record<string, unknown>).usagePlanning as { assumptions: string[] }).assumptions = []; },
      (p: Record<string, unknown>) => { (p.application as Record<string, unknown>).type = "OTHER"; },
      (p: Record<string, unknown>) => { delete (p.security as Record<string, unknown>).authenticationControls; }]) {
      mutation = corrupt; calls = [];
      await assert.rejects(updatePersonalOperationalPreferences(session, id, 7, values), /response is invalid/);
      assert.deepEqual(calls, ["GET", "PUT"]);
    }
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});

test("real operations editor renders exact saved choices, bounded explanations and one deliberate save", async () => {
  const { OperationalPreferencesEditor } = await assessmentUiComponents();
  const values = operationsValues().inputs, before = structuredClone(values);
  const html = renderToStaticMarkup(createElement(OperationalPreferencesEditor, { assessmentId: id, version: 7, values }));
  assert.equal((html.match(/<select /g) ?? []).length, 4); assert.equal((html.match(/type="submit"/g) ?? []).length, 1);
  assert.ok(html.includes(`action="/api/assessments/${id}/operational-preferences"`));
  assert.ok(html.includes('name="expectedVersion" value="7"'));
  for (const [key, value] of Object.entries(values)) {
    assert.ok(html.includes(`id="operations-${key}" name="${key}"`));
    assert.ok(html.includes(`value="${value}" selected=""`)); assert.ok(html.includes(`aria-describedby="operations-${key}-description"`));
  }
  for (const copy of ["no option is chosen for you", "not a spending cap", "not the IdP", "self-reported", "not unsaved selections", "Unknown / not recorded", "Undecided / not recorded", "Save operational preferences"]) assert.ok(html.includes(copy), copy);
  assert.ok(!html.includes('name="workspaceId"')); assert.ok(!html.includes('name="budgetCap"')); assert.deepEqual(values, before);
});
