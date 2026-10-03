import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { comparisonVerdicts, deferredComparisonLabel, isComparisonEvidenceGap, relatedComparisonInput } from "../src/lib/assessment/comparison-presentation.ts";
import { savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { capabilityFields } from "../src/lib/assessment/capabilities.ts";
import { assessmentSteps } from "../src/lib/assessment/workflow.ts";
import { assessmentUiComponents, comparisonUiFixture, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

test("verdict copy preserves three Core states and never promotes a partial pass into suitability", () => {
  assert.deepEqual(Object.keys(comparisonVerdicts), ["EXCLUDED", "UNRESOLVED", "PASSES_CHECKED_REQUIREMENTS"]);
  assert.ok(comparisonVerdicts.EXCLUDED.explanation.includes("cannot override"));
  assert.ok(comparisonVerdicts.UNRESOLVED.explanation.includes("not proof"));
  assert.ok(comparisonVerdicts.PASSES_CHECKED_REQUIREMENTS.label.includes("only"));
  assert.ok(comparisonVerdicts.PASSES_CHECKED_REQUIREMENTS.explanation.includes("not a recommendation"));
});

test("all nine capability paths reuse exact saved values and the Requirements target", () => {
  const groups = savedRequirementGroups(savedRequirementsFixture());
  for (const field of capabilityFields) {
    const input = relatedComparisonInput(field.path.join("."), groups)!;
    assert.equal(input.label, field.label); assert.equal(input.step, "capabilities");
    assert.deepEqual(input.rows, groups[2].rows?.filter(row => row.label === field.label));
  }
  assert.equal(relatedComparisonInput("protocols.oauth2ProtectedApis", groups)?.rows?.[0].value, "Unknown / not recorded");
  assert.equal(relatedComparisonInput("protocols.socialLogin", groups)?.rows?.[0].value, "Not required");
  assert.equal(relatedComparisonInput("protocols.enterpriseSingleSignOn", groups)?.rows?.[0].value, "Forbidden");
});

test("context, residency, controls and compliance map only to their saved editor scope", () => {
  const groups = savedRequirementGroups(savedRequirementsFixture());
  for (const path of ["application.type", "application.clients", "audience.populations", "audience.tenancy", "audience.membership",
    "security.dataResidency", "security.authenticationControls.phishingResistance", "security.authenticationControls.nonExportableKeys",
    "security.authenticationControls.stepUpAuthentication", "security.complianceScopeStatus"]) {
    assert.equal(relatedComparisonInput(path, groups)?.step, "context");
  }
  assert.deepEqual(relatedComparisonInput("security.dataResidency", groups)?.rows?.map(row => row.value), ["Required", "CA, US", "User profiles, Backups and recovery copies"]);
  assert.deepEqual(relatedComparisonInput("security.complianceScopeStatus", groups)?.rows?.map(row => row.value), ["Unknown / not recorded", "Nothing recorded"]);
  assert.deepEqual(relatedComparisonInput("assessment", groups), { label: "Assessment coverage", rows: [], step: "review" });
});

test("unknown paths, lookalikes and prototype keys cannot select an editor or traverse raw data", () => {
  const profile = savedRequirementsFixture(); const before = structuredClone(profile);
  const groups = savedRequirementGroups(profile);
  for (const path of ["__proto__", "constructor", "toString", "provisioning.scim.extra", "security.secret", "security.dataResidencyDetails", "operations", "https://example.invalid", "../context"]) {
    assert.equal(relatedComparisonInput(path, groups), null);
  }
  assert.deepEqual(profile, before);
});

test("unreadable related inputs have no edit target and do not hide independent capability inputs", () => {
  const profile = savedRequirementsFixture() as Record<string, unknown>;
  profile.application = { type: "invalid-sensitive-value" };
  const groups = savedRequirementGroups(profile);
  assert.deepEqual(relatedComparisonInput("application.type", groups), { label: "Application type", rows: null, step: null });
  assert.equal(relatedComparisonInput("provisioning.scim", groups)?.rows?.[0].value, "Required");
  assert.equal(JSON.stringify(relatedComparisonInput("application.type", groups)).includes("sensitive"), false);
});

test("catalog evidence notes use only known Core evidence reasons, never invent a verdict", () => {
  for (const reason of ["EVIDENCE_MISSING", "EVIDENCE_UNREVIEWED", "EVIDENCE_STALE", "EVIDENCE_FROM_FUTURE"]) assert.equal(isComparisonEvidenceGap(reason), true);
  for (const reason of ["REQUIREMENT_UNKNOWN", "REQUIRED_CAPABILITY_UNAVAILABLE", "CAPABILITY_UNKNOWN", "EVIDENCE_NEW", "__proto__"]) assert.equal(isComparisonEvidenceGap(reason), false);
  assert.equal(deferredComparisonLabel("security.auditability"), "Identity-provider auditability");
  assert.equal(deferredComparisonLabel("operations"), "Operations, usage, pricing and budget");
  assert.equal(deferredComparisonLabel("unknown.scope"), "Additional unchecked scope");
  assert.equal(deferredComparisonLabel("__proto__"), "Additional unchecked scope");
});

async function rendered(editable = true, mutate?: (data: ReturnType<typeof comparisonUiFixture>, profile: ReturnType<typeof savedRequirementsFixture>) => void) {
  const components = await assessmentUiComponents();
  const comparison = comparisonUiFixture(); const profile = savedRequirementsFixture();
  mutate?.(comparison, profile);
  const before = structuredClone({ comparison, profile });
  const panels = Object.fromEntries(assessmentSteps.map(step => [step.id, step.id === "comparison"
    ? createElement(components.ComparisonSection, { comparison, profile, editable, preferencePreview: createElement("p", {}, "Existing weight preview slot") })
    : createElement("p", {}, `Synthetic ${step.id}`)]));
  const html = renderToStaticMarkup(createElement(components.AssessmentWorkflow, { initialStep: "comparison", panels, editable }));
  assert.deepEqual({ comparison, profile }, before);
  return html;
}

test("actual comparison renders all three statuses in Core order, saved inputs, exact explanations and bounded scope", async () => {
  const html = await rendered();
  for (const label of ["Excluded by a checked requirement", "Needs more information", "Passes checked requirements only", "Fictional catalog · Unranked preview",
    "Saved assessment version 7", "Saved SCIM provisioning:", "Required", "Preferred", "What this comparison does not check", "Existing weight preview slot"]) assert.ok(html.includes(label), label);
  assert.ok(html.indexOf("Fictional Limited Plan") < html.indexOf("Fictional Uncertain Plan"));
  assert.ok(html.indexOf("Fictional Uncertain Plan") < html.indexOf("Fictional Broad Plan"));
  for (const candidate of comparisonUiFixture().candidates) for (const finding of [...candidate.exclusionReasons, ...candidate.informationGaps, ...candidate.capabilityPreferences]) {
    assert.ok(html.includes(finding.explanation)); assert.ok(html.includes(finding.reasonCode)); assert.ok(html.includes(finding.profilePath));
  }
  assert.equal((html.match(/<form/g) ?? []).length, 0);
  assert.equal((html.match(/<dd class="mt-1 text-2xl font-semibold">1</g) ?? []).length, 3);
  assert.equal(html.includes("Best provider"), false); assert.equal(html.includes("100% complete"), false);
});

test("evidence gaps stay distinct from exclusions and preferences cannot revive an excluded option", async () => {
  const html = await rendered();
  assert.ok(html.includes("Preferences, separate from hard requirements"));
  assert.ok(html.includes("Preferred capability available")); assert.ok(html.includes("Preferred capability unavailable"));
  assert.ok(html.includes("Availability not established"));
  assert.equal((html.match(/This gap concerns catalog evidence/g) ?? []).length, 2);
  assert.ok(html.includes("Do not weaken a requirement")); assert.ok(html.includes("Changing its observation date alone would not verify"));
});

test("read-only comparison sends related actions to saved Review, not editable forms", async () => {
  const html = await rendered(false);
  assert.equal(html.includes("Review related input"), false);
  assert.ok(html.includes("Review saved requirements")); assert.ok(html.includes("Saved SCIM provisioning:"));
});

test("unknown and unsafe inputs preserve Core reasons without a guessed editor or leaked raw value", async () => {
  const html = await rendered(true, (data, profile) => {
    data.candidates[0].exclusionReasons[0].profilePath = "security.secret";
    data.candidates[1].informationGaps[0].profilePath = "application.type";
    profile.application.type = "invalid-sensitive-input";
  });
  assert.ok(html.includes("Checked requirement")); assert.ok(html.includes("security.secret"));
  assert.ok(html.includes("Related saved inputs cannot be read safely"));
  assert.equal(html.includes("invalid-sensitive-input"), false);
  assert.ok(html.includes("REQUIRED_CAPABILITY_UNAVAILABLE"));
});

test("Core text is escaped, future deferred scope remains visible and no-preference guidance does not invent preferences", async () => {
  const html = await rendered(true, data => {
    data.candidates[0].displayName = '<script>synthetic()</script>';
    data.candidates[0].exclusionReasons[0].explanation = '<img src=x onerror="synthetic()">';
    data.deferredPaths.push("future.unknown");
    for (const candidate of data.candidates) candidate.capabilityPreferences = [];
  });
  assert.ok(html.includes("&lt;script&gt;synthetic()&lt;/script&gt;")); assert.equal(html.includes("<script>synthetic"), false);
  assert.ok(html.includes("&lt;img src=x")); assert.equal(html.includes("<img src=x"), false);
  assert.ok(html.includes("Additional unchecked scope")); assert.ok(html.includes("future.unknown"));
  assert.ok(html.includes("No capability preferences are recorded"));
  assert.equal(html.includes("Choose Preferred"), false); assert.equal(html.includes("Preferred capability available"), false);
});
