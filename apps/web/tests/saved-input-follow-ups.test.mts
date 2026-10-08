import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { savedInputRowId, savedRequirementGroups, type SavedRequirementGroup } from "../src/lib/assessment/saved-requirements.ts";
import { evaluationContextValues, withEvaluationContextValues } from "../src/lib/assessment/evaluation-context.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { guidedScenarios } from "./fixtures/guided-scenarios.mts";

const components = await assessmentUiComponents();
function summary(groups: SavedRequirementGroup[]) {
  return renderToStaticMarkup(createElement(components.SavedInputFollowUps, { groups }));
}
function overview(profile: Record<string, unknown>, editable = true, version = 7) {
  return renderToStaticMarkup(createElement(components.SavedRequirementsOverview, { profile, version, editable }));
}
function targets(html: string) { return [...html.matchAll(/href="#([^"]+)"/g)].map(match => match[1]); }

test("saved questions are only the existing eight display gaps in fixed group/row order", () => {
  const profile = savedRequirementsFixture(), before = structuredClone(profile), groups = savedRequirementGroups(profile);
  const html = summary(groups);
  assert.deepEqual(targets(html), ["saved-security-input-4", "saved-security-input-7", "saved-security-input-8",
    "saved-capabilities-input-2", "saved-capabilities-input-6", "saved-capabilities-input-7", "saved-usage-input-2", "saved-usage-input-4"]);
  for (const expected of ["Security and compliance scope · 3 fields", "Identity capabilities · 3 fields", "Usage assumptions · 2 fields"]) assert.ok(html.includes(expected));
  for (const excluded of ["Application and audience ·", "Identity-provider auditability ·", "OpenID Connect (OIDC)", "SCIM provisioning", "Monthly active users", "Monthly M2M token issuances"]) assert.equal(html.includes(excluded), false, excluded);
  assert.equal((html.match(/>Not recorded<\/span>/g) ?? []).length, 8);
  assert.ok(html.includes("not validation errors")); assert.ok(html.includes("Some inputs may not apply"));
  assert.ok(html.includes("not severity or assessment completeness")); assert.equal(html.includes("100%"), false);
  assert.deepEqual(profile, before);
});

test("Other remains Needs definition and explicit not-required, forbidden and observed zero are never gaps", () => {
  const profile = savedRequirementsFixture();
  profile.application.type = "OTHER"; profile.security.complianceScopeStatus = "TARGETS_IDENTIFIED";
  profile.security.complianceTargets = ["OTHER"];
  const groups = savedRequirementGroups(profile), html = summary(groups);
  assert.ok(targets(html).includes("saved-application-input-0"));
  assert.ok(targets(html).includes("saved-security-input-8")); assert.equal(targets(html).includes("saved-security-input-7"), false);
  assert.equal((html.match(/>Needs definition<\/span>/g) ?? []).length, 2);
  assert.equal(targets(html).includes("saved-capabilities-input-3"), false);
  assert.equal(targets(html).includes("saved-capabilities-input-4"), false);
  assert.equal(targets(html).includes("saved-usage-input-3"), false);
  assert.ok(html.includes("team discussion rather than another editor value"));
  profile.security.complianceScopeStatus = "NONE_IDENTIFIED"; profile.security.complianceTargets = [];
  const none = summary(savedRequirementGroups(profile));
  assert.equal(targets(none).includes("saved-security-input-7"), false);
  assert.ok(targets(none).includes("saved-security-input-8"));
  assert.ok(none.includes("Some inputs may not apply"));
});

test("unreadable sections are separate, have no inferred row count and cannot leak raw malformed values", () => {
  const profile = savedRequirementsFixture() as Record<string, unknown>;
  profile.operations = { usagePlanning: { scopeDescription: "synthetic-private-malformed", assumptions: [],
    volumes: { MONTHLY_ACTIVE_USERS: { basis: "OBSERVED", value: -1 } } } };
  const html = summary(savedRequirementGroups(profile));
  assert.deepEqual(targets(html), ["saved-security-input-4", "saved-security-input-7", "saved-security-input-8",
    "saved-capabilities-input-2", "saved-capabilities-input-6", "saved-capabilities-input-7", "saved-usage-heading", "saved-operations-heading"]);
  assert.ok(html.includes("Cannot inspect these saved sections")); assert.ok(html.includes("No field count or missing answer is inferred"));
  assert.equal(html.includes("Usage assumptions ·"), false); assert.equal(html.includes("synthetic-private"), false);
});

test("all-unreadable and all-recorded views remain honest rather than declaring completion", () => {
  const unreadable = summary(savedRequirementGroups({}));
  assert.ok(unreadable.includes("No saved cards can be read safely"));
  assert.equal(unreadable.includes("No Not recorded or Needs definition labels"), false);
  assert.equal((unreadable.match(/<details/g) ?? []).length, 0);
  assert.deepEqual(targets(unreadable), ["saved-application-heading", "saved-security-heading", "saved-capabilities-heading", "saved-auditability-heading", "saved-usage-heading", "saved-operations-heading"]);
  const groups = savedRequirementGroups(savedRequirementsFixture()).map(group => ({ ...group,
    rows: group.rows!.map(row => ({ ...row, state: "recorded" as const })),
  }));
  const html = summary(groups);
  assert.deepEqual(targets(html), []); assert.ok(html.includes("No Not recorded or Needs definition labels in the readable cards"));
  assert.ok(html.includes("not a readiness result")); assert.equal(html.includes("Cannot inspect these saved sections"), false);
  assert.equal(html.includes("100%"), false); assert.equal(html.includes("ready for deployment"), false);
});

test("summary escapes labels but never repeats user-provided saved values", () => {
  const groups = savedRequirementGroups(savedRequirementsFixture());
  groups[0].title = '<script>synthetic-title()</script>';
  groups[0].rows![0] = { label: '<script>synthetic-label()</script>', value: "synthetic-private-value", state: "needs-definition" };
  const html = summary(groups);
  assert.ok(html.includes("&lt;script&gt;synthetic-title()&lt;/script&gt;"));
  assert.ok(html.includes("&lt;script&gt;synthetic-label()&lt;/script&gt;"));
  assert.equal(html.includes("<script>"), false); assert.equal(html.includes("synthetic-private-value"), false);
});

for (const scenario of guidedScenarios) test(`${scenario.key} saved questions link to unique focusable rows without changing any saved input`, () => {
  const base = savedRequirementsFixture(), context = evaluationContextValues(base)!;
  const profile = withEvaluationContextValues(base, { ...context, applicationType: scenario.applicationType,
    clients: [...scenario.clients], selectedPopulations: [...scenario.populations], tenancy: scenario.tenancy, membership: scenario.membership });
  const before = structuredClone(profile), html = overview(profile), groups = savedRequirementGroups(profile);
  const links = targets(html);
  assert.equal(new Set(links).size, links.length);
  for (const id of links) {
    assert.equal((html.match(new RegExp(`id="${id}"`, "g")) ?? []).length, 1);
    assert.ok(html.includes(`id="${id}" tabindex="-1"`));
  }
  assert.equal((html.match(/id="saved-[a-z]+-input-[0-9]+"/g) ?? []).length, 36);
  groups.forEach(group => group.rows!.forEach((row, index) => assert.equal(links.includes(savedInputRowId(group, index)), row.state !== "recorded")));
  assert.equal(html.includes("<form"), false); assert.deepEqual(profile, before);
});

test("read-only Review keeps native links and saved version changes replace rather than retain old questions", () => {
  const profile = savedRequirementsFixture(), original = overview(profile, false);
  assert.ok(original.includes("Saved version 7")); assert.ok(original.includes('href="#saved-capabilities-input-2"'));
  assert.equal(original.includes("Edit identity requirements"), false);
  profile.protocols.oauth2ProtectedApis = "NOT_REQUIRED";
  const updated = overview(profile, false, 8);
  assert.ok(updated.includes("Saved version 8")); assert.equal(updated.includes('href="#saved-capabilities-input-2"'), false);
  assert.ok(updated.includes('id="saved-capabilities-input-2"')); assert.ok(updated.includes("Not required"));
  assert.ok(updated.includes("Saved inputs to discuss"));
});
