import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { assessmentSteps } from "../src/lib/assessment/workflow.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { evaluationContextValues, withEvaluationContextValues } from "../src/lib/assessment/evaluation-context.ts";
import { guidedScenarios } from "./fixtures/guided-scenarios.mts";
import { assuranceExpectations, assuranceExpectationLabels } from "../src/lib/assessment/assurance-expectation.ts";

test("saved assurance is appended without renumbering existing Review rows or declaring readiness", () => {
  const legacyRows = savedRequirementGroups(savedRequirementsFixture())[1].rows!;
  for (const assurance of assuranceExpectations) {
    const profile = savedRequirementsFixture(); Object.assign(profile.security, { assurance });
    const rows = savedRequirementGroups(profile)[1].rows!;
    assert.deepEqual(rows.slice(0, 9), legacyRows);
    assert.deepEqual(rows[9], { label: "Assurance expectation (planning label)", value: assuranceExpectationLabels[assurance],
      state: assurance === "UNKNOWN" ? "not-recorded" : "recorded" });
    assert.equal(rows.length, 10); assert.equal(JSON.stringify(rows).includes("ready"), false);
  }
  const invalid = savedRequirementsFixture(); Object.assign(invalid.security, { assurance: "synthetic-sensitive-invalid" });
  assert.equal(savedRequirementGroups(invalid)[1].rows, null);
  assert.equal(JSON.stringify(savedRequirementGroups(invalid)).includes("synthetic-sensitive-invalid"), false);
});

for (const scenario of guidedScenarios) test(`saved context header identifies ${scenario.key} without guessing or changing the profile`, async () => {
  const components = await assessmentUiComponents();
  const profile = savedRequirementsFixture(), values = evaluationContextValues(profile)!;
  const saved = withEvaluationContextValues(profile, { ...values, applicationType: scenario.applicationType,
    clients: [...scenario.clients], selectedPopulations: [...scenario.populations], tenancy: scenario.tenancy, membership: scenario.membership });
  const before = structuredClone(saved);
  const html = renderToStaticMarkup(createElement(components.SavedContextSummary, { values: evaluationContextValues(saved) }));
  for (const expected of [scenario.expected.application, scenario.expected.users, scenario.expected.clients]) assert.ok(html.includes(expected), expected);
  assert.ok(html.includes('aria-label="Saved application context"')); assert.ok(html.includes("not unsaved edits"));
  assert.equal(html.includes("<form"), false); assert.equal(html.includes("complete"), false); assert.deepEqual(saved, before);
});

test("saved context header preserves unknown, empty and Other without leaking malformed raw context", async () => {
  const components = await assessmentUiComponents(), profile = savedRequirementsFixture();
  profile.application.type = "UNKNOWN"; profile.application.clients = []; profile.audience.populations = [];
  const render = () => renderToStaticMarkup(createElement(components.SavedContextSummary, { values: evaluationContextValues(profile) }));
  assert.equal((render().match(/Not recorded/g) ?? []).length, 3);
  profile.application.type = "OTHER"; assert.ok(render().includes("Other (needs definition)"));
  profile.application.type = "synthetic-sensitive-invalid";
  assert.ok(render().includes("cannot be read safely")); assert.equal(render().includes("synthetic-sensitive-invalid"), false);
});

test("saved overview reads only the five bounded existing editor groups without mutating the profile", () => {
  const profile = savedRequirementsFixture();
  const before = structuredClone(profile);
  const groups = savedRequirementGroups(profile);
  assert.deepEqual(groups.map(group => [group.id, group.step]), [["application", "context"], ["security", "context"],
    ["capabilities", "capabilities"], ["auditability", "auditability"], ["usage", "usage"]]);
  assert.deepEqual(groups.map(group => group.rows?.length), [5, 9, 9, 3, 6]);
  assert.deepEqual(profile, before);
  assert.equal(groups[0].rows?.[0].value, "B2B SaaS");
  assert.equal(groups[1].rows?.[1].value, "CA, US");
  assert.equal(JSON.stringify(groups).includes('"ready"'), false);
});

test("criticalities preserve unknown, required, preferred, not-required and forbidden independently", () => {
  const rows = savedRequirementGroups(savedRequirementsFixture())[2].rows!;
  for (const [label, value, state] of [["OpenID Connect (OIDC)", "Required", "recorded"],
    ["SAML federation", "Preferred", "recorded"], ["OAuth 2.0 protected APIs", "Unknown / not recorded", "not-recorded"],
    ["Social login", "Not required", "recorded"], ["Enterprise single sign-on", "Forbidden", "recorded"]]) {
    assert.deepEqual(rows.find(row => row.label === label), { label, value, state });
  }
});

test("empty selections remain blank, OTHER needs definition and explicit none is not compliance evidence", () => {
  const profile = savedRequirementsFixture();
  profile.application.type = "OTHER"; profile.application.clients = [];
  profile.audience.populations = []; profile.security.dataResidencyDetails.allowedCountries = [];
  profile.security.complianceScopeStatus = "NONE_IDENTIFIED";
  const groups = savedRequirementGroups(profile);
  assert.equal(groups[0].rows?.[0].state, "needs-definition");
  assert.equal(groups[0].rows?.[1].state, "not-recorded");
  assert.equal(groups[0].rows?.[2].state, "not-recorded");
  assert.equal(groups[1].rows?.[1].value, "Nothing recorded");
  assert.equal(groups[1].rows?.[7].value, "No compliance targets identified after review");
  assert.equal(groups[1].rows?.[8].value, "Nothing recorded");
  assert.ok(groups[1].note.includes("not evidence of safety or compliance"));
  profile.security.complianceScopeStatus = "TARGETS_IDENTIFIED";
  profile.security.complianceTargets = ["OTHER"];
  assert.equal(savedRequirementGroups(profile)[1].rows?.[8].state, "needs-definition");
});

test("audit scope and duration are exact; empty criteria do not invent a retention minimum", () => {
  const profile = savedRequirementsFixture();
  const rows = savedRequirementGroups(profile)[3].rows!;
  assert.equal(rows[1].value, "Successful sign-ins, Log retention");
  assert.equal(rows[2].value, "30 days");
  profile.security.auditabilityRequirements = { selectedCriteria: [], minimumRetentionDays: null };
  const empty = savedRequirementGroups(profile)[3];
  assert.equal(empty.rows?.[1].state, "not-recorded");
  assert.equal(empty.rows?.[2].value, "No duration recorded; retention is not selected");
  assert.ok(empty.note.includes("not a logging exemption"));
});

test("missing usage is not zero; observed zero and assumed quantities preserve their basis and literal text", () => {
  const profile = savedRequirementsFixture();
  profile.operations.usagePlanning.assumptions = ["UNKNOWN", "NOT_REQUIRED"];
  profile.operations.usagePlanning.scopeDescription = "NOT_REQUIRED";
  const rows = savedRequirementGroups(profile)[4].rows!;
  assert.equal(rows[0].value, "NOT_REQUIRED");
  assert.equal(rows[1].value, "250 · Assumed");
  assert.equal(rows[2].state, "not-recorded");
  assert.equal(rows[3].value, "0 · Observed");
  assert.equal(rows[3].state, "recorded");
  assert.equal(rows[5].value, "UNKNOWN, NOT_REQUIRED");
});

test("malformed sections are isolated and unrelated raw fields never enter the projection", () => {
  const profile = savedRequirementsFixture() as Record<string, unknown>;
  profile.operations = { usagePlanning: { scopeDescription: "secret-invalid-input", assumptions: [],
    volumes: { MONTHLY_ACTIVE_USERS: { basis: "OBSERVED", value: -1 } } } };
  profile.rawCredential = "synthetic-secret-must-not-appear";
  const groups = savedRequirementGroups(profile);
  assert.equal(groups[4].rows, null); assert.equal(groups[2].rows?.length, 9);
  assert.equal(JSON.stringify(groups).includes("secret"), false);
  const malformed = savedRequirementGroups({});
  assert.ok(malformed.every(group => group.rows === null));
});

test("overview renders saved version, honest labels, safe text and scoped navigation; read-only has no edit actions", async () => {
  const component = await assessmentUiComponents();
  const profile = savedRequirementsFixture();
  profile.operations.usagePlanning.scopeDescription = '<script>synthetic()</script>';
  const panels = Object.fromEntries(assessmentSteps.map(step => [step.id,
    step.id === "review" ? createElement(component.SavedRequirementsOverview, { profile, version: 7, editable: true })
      : createElement("p", {}, `Synthetic ${step.id}`)]));
  const html = renderToStaticMarkup(createElement(component.AssessmentWorkflow, { initialStep: "review", panels, editable: true }));
  assert.ok(html.includes("Saved version 7 · Read-only overview"));
  assert.equal((html.match(/<h3 /g) ?? []).length, 6);
  assert.equal((html.match(/id="saved-(application|security|capabilities|auditability|usage)-heading"/g) ?? []).length, 5);
  assert.equal((html.match(/<form/g) ?? []).length, 0);
  assert.ok(html.includes("Not recorded")); assert.ok(html.includes("not validation errors"));
  assert.ok(html.includes("Edit identity requirements →")); assert.ok(html.includes("Explore comparison →"));
  assert.ok(html.includes('&lt;script&gt;synthetic()&lt;/script&gt;'));
  assert.equal(html.includes('<script>synthetic()'), false);
  assert.equal(html.includes("100% complete"), false); assert.equal(html.includes("Winner"), false);
  const readOnly = renderToStaticMarkup(createElement(component.SavedRequirementsOverview, { profile, version: 7, editable: false }));
  assert.equal(readOnly.includes("Edit identity requirements"), false);
  assert.ok(readOnly.includes("B2B SaaS"));
});
