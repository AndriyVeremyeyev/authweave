import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { operationalPreferencesValues, operationsPlanningValues } from "../src/lib/assessment/operations-planning.ts";
import { operationalPreferenceFields, operationalPreferenceLabels } from "../src/lib/assessment/operational-preferences.ts";
import { savedInputRowId, savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { requirementsBriefFormat, savedRequirementsMarkdown } from "../src/lib/assessment/requirements-brief.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

const components = await assessmentUiComponents();
const keys = ["hosting", "deploymentTarget", "identityExpertise", "budgetSensitivity"] as const;
const id = "80000000-0000-4000-8000-000000000001";
const readable = (text: string) => text.replace(/\\([!-~])/g, "$1");

test("all 384 operational choice combinations have the exact saved rows and v2 brief without defaults or input mutation", () => {
  let cases = 0;
  for (const hosting of operationalPreferenceFields.hosting.choices)
    for (const deploymentTarget of operationalPreferenceFields.deploymentTarget.choices)
      for (const identityExpertise of operationalPreferenceFields.identityExpertise.choices)
        for (const budgetSensitivity of operationalPreferenceFields.budgetSensitivity.choices) {
          const profile = savedRequirementsFixture();
          Object.assign(profile.operations, { hosting, deploymentTarget, identityExpertise, budgetSensitivity });
          const before = structuredClone(profile), groups = savedRequirementGroups(profile), group = groups[5];
          assert.deepEqual(operationalPreferencesValues(profile), { hosting, deploymentTarget, identityExpertise, budgetSensitivity });
          assert.equal(group.id, "operations"); assert.equal(group.step, "usage"); assert.equal(group.rows?.length, 4);
          const markdown = readable(savedRequirementsMarkdown({ id, version: 7, status: "DRAFT", profile }));
          assert.equal(requirementsBriefFormat, "authweave-saved-requirements-brief-v2");
          keys.forEach((key, index) => {
            const value = profile.operations[key], row = group.rows![index];
            assert.deepEqual(row, { label: operationalPreferenceFields[key].label,
              value: operationalPreferenceLabels[value], state: value === "UNKNOWN" || value === "UNDECIDED" ? "not-recorded" : "recorded" });
            assert.equal(savedInputRowId(group, index), `saved-operations-input-${index}`);
            assert.ok(markdown.includes(`**${row.label}:** ${row.value}`));
            assert.equal(markdown.includes(`Operational preferences / ${row.label}: Not recorded.`), row.state === "not-recorded");
          });
          assert.deepEqual(profile, before); cases++;
        }
  assert.equal(cases, 384);
});

test("explicit no preference, low sensitivity and self-reported expertise are answers, not a free service or readiness verdict", () => {
  const profile = savedRequirementsFixture();
  Object.assign(profile.operations, { hosting: "NO_PREFERENCE", deploymentTarget: "UNDECIDED", identityExpertise: "UNKNOWN", budgetSensitivity: "LOW" });
  const group = savedRequirementGroups(profile)[5];
  assert.deepEqual(group.rows!.map(row => row.state), ["recorded", "not-recorded", "not-recorded", "recorded"]);
  assert.equal(group.rows![0].value, "No hosting preference");
  assert.ok(group.note.includes("not provider eligibility")); assert.ok(group.note.includes("spending cap"));
  const html = renderToStaticMarkup(createElement(components.SavedInputFollowUps, { groups: [group] }));
  assert.ok(html.includes('href="#saved-operations-input-1"')); assert.ok(html.includes('href="#saved-operations-input-2"'));
  assert.equal(html.includes('href="#saved-operations-input-0"'), false); assert.equal(html.includes('href="#saved-operations-input-3"'), false);
  assert.equal(html.includes("100%"), false); assert.equal(html.includes("free service"), false);
});

test("usage and operational preference projections fail independently without copying malformed or extra fields", () => {
  const baseline = savedRequirementsFixture();
  const brokenUsage = { ...baseline, operations: { ...baseline.operations,
    usagePlanning: { scopeDescription: "synthetic-private-usage", assumptions: [], volumes: { MONTHLY_ACTIVE_USERS: { basis: "OBSERVED", value: -1 } } } } };
  assert.equal(savedRequirementGroups(brokenUsage)[4].rows, null);
  assert.deepEqual(savedRequirementGroups(brokenUsage)[5].rows, savedRequirementGroups(baseline)[5].rows);
  assert.equal(operationsPlanningValues(brokenUsage), null); // A planning preview still needs both valid inputs.
  assert.equal(JSON.stringify(savedRequirementGroups(brokenUsage)).includes("synthetic-private-usage"), false);
  for (const key of keys) for (const invalid of [undefined, null, {}, [], 1, "synthetic-private-choice"]) {
    const profile = { ...baseline, operations: { ...baseline.operations, [key]: invalid } };
    assert.equal(operationalPreferencesValues(profile), null);
    const groups = savedRequirementGroups(profile);
    assert.equal(groups[5].rows, null); assert.deepEqual(groups[4].rows, savedRequirementGroups(baseline)[4].rows);
    assert.equal(JSON.stringify(groups).includes("synthetic-private-choice"), false);
  }
  for (const operations of [null, [], {}, { ...baseline.operations, privateValue: "synthetic-secret" },
    { hosting: "MANAGED", deploymentTarget: "AZURE", identityExpertise: "MODERATE", budgetSensitivity: "HIGH", privateValue: "synthetic-secret" }]) {
    const groups = savedRequirementGroups({ ...baseline, operations });
    assert.equal(groups[5].rows, null); assert.equal(JSON.stringify(groups).includes("synthetic-secret"), false);
  }
});

test("the new operational card preserves every old saved row and routes editing only to Usage", () => {
  const profile = savedRequirementsFixture(), before = structuredClone(profile), groups = savedRequirementGroups(profile);
  const html = renderToStaticMarkup(createElement(components.SavedRequirementsOverview, { profile, version: 7, editable: true }));
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  groups.forEach(group => group.rows?.forEach((_, index) => assert.ok(ids.includes(savedInputRowId(group, index)))));
  assert.equal(groups[5].action, "Edit operational preferences"); assert.equal(groups[5].step, "usage");
  assert.ok(html.includes("Edit operational preferences →")); assert.equal(html.includes("<form"), false);
  const readOnly = renderToStaticMarkup(createElement(components.SavedRequirementsOverview, { profile, version: 7, editable: false }));
  assert.ok(readOnly.includes("Operational preferences")); assert.equal(readOnly.includes("Edit operational preferences"), false);
  assert.deepEqual(profile, before);
});
