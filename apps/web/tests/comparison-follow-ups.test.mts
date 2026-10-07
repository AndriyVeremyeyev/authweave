import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { comparisonFollowUps } from "../src/lib/assessment/comparison-follow-ups.ts";
import { savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { assessmentUiComponents, comparisonUiFixture, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

const finding = (dimension: string, reasonCode: string, profilePath = "provisioning.scim", explanation = "Exact Core explanation") =>
  ({ dimension, profilePath, reasonCode, explanation });

test("known Core input and evidence reasons have distinct display guidance, not an inferred decision", () => {
  const comparison = comparisonUiFixture();
  const input = {
    CAPABILITY: ["REQUIREMENT_UNKNOWN"], CONTEXT: ["PROFILE_CONTEXT_UNKNOWN"],
    RESIDENCY: ["REQUIREMENT_UNKNOWN", "DATA_SCOPE_UNKNOWN", "ALLOWED_COUNTRIES_UNKNOWN", "RESIDENCY_INTENT_UNCLEAR"],
    AUTHENTICATION_CONTROL: ["REQUIREMENT_UNKNOWN", "CLIENT_SCOPE_UNKNOWN", "POPULATION_SCOPE_UNKNOWN", "CONTROL_INTENT_UNCLEAR"],
    COMPLIANCE_SCOPE: ["COMPLIANCE_SCOPE_UNKNOWN", "COMPLIANCE_SCOPE_INCONSISTENT"],
    AUDITABILITY: ["REQUIREMENT_UNKNOWN", "AUDIT_INTENT_UNCLEAR", "AUDIT_SCOPE_UNKNOWN"],
  };
  const evidence = {
    CAPABILITY: ["CAPABILITY_UNKNOWN"], CONTEXT: ["CONTEXT_SUPPORT_UNKNOWN"],
    RESIDENCY: ["STORAGE_LOCATIONS_INCOMPLETE", "STORAGE_LOCATIONS_UNKNOWN"],
    AUTHENTICATION_CONTROL: ["CONTROL_AVAILABILITY_UNKNOWN", "ENFORCEMENT_UNKNOWN"],
    AUDITABILITY: ["CAPABILITY_UNKNOWN", "RETENTION_DURATION_UNKNOWN"],
  };
  for (const [expected, reasons] of [["REQUIREMENT", input], ["EVIDENCE", evidence]] as const) {
    for (const [dimension, codes] of Object.entries(reasons)) for (const code of codes) {
      comparison.candidates[0].informationGaps = [finding(dimension, code)];
      assert.equal(comparisonFollowUps(comparison)[0].kind, expected, `${dimension}/${code}`);
    }
  }
  for (const dimension of Object.keys(evidence)) for (const code of ["EVIDENCE_MISSING", "EVIDENCE_UNREVIEWED", "EVIDENCE_STALE", "EVIDENCE_FROM_FUTURE"]) {
    comparison.candidates[0].informationGaps = [finding(dimension, code)];
    assert.equal(comparisonFollowUps(comparison)[0].kind, "EVIDENCE", `${dimension}/${code}`);
  }
});

test("unknown, lookalike and wrong-dimension reasons remain neutral boundaries without a guessed resolution", () => {
  const comparison = comparisonUiFixture();
  for (const [dimension, code] of [["COVERAGE", "NO_AFFIRMATIVE_CHECKS"], ["COMPLIANCE_SCOPE", "COMPLIANCE_TARGETS_NOT_EVALUATED"],
    ["CONTEXT", "REQUIREMENT_UNKNOWN"], ["COVERAGE", "EVIDENCE_MISSING"], ["AUDITABILITY", "EVIDENCE_NEW"],
    ["CAPABILITY", "requirement_unknown"], ["__proto__", "REQUIREMENT_UNKNOWN"], ["constructor", "EVIDENCE_STALE"]]) {
    comparison.candidates[0].informationGaps = [finding(dimension, code)];
    assert.equal(comparisonFollowUps(comparison)[0].kind, "BOUNDARY", `${dimension}/${code}`);
  }
});

test("grouping preserves every criterion/scope explanation and affected option identity in original Core order", () => {
  const comparison = comparisonUiFixture();
  comparison.candidates.forEach(c => { c.informationGaps = []; c.capabilityPreferences = []; });
  comparison.candidates[0].informationGaps = [finding("AUDITABILITY", "EVIDENCE_MISSING", "security.auditabilityRequirements", "Authentication events: absent"),
    finding("AUDITABILITY", "EVIDENCE_MISSING", "security.auditabilityRequirements", "Retention: absent")];
  comparison.candidates[1].informationGaps = [finding("AUDITABILITY", "EVIDENCE_MISSING", "security.auditabilityRequirements", "Export: absent")];
  const before = structuredClone(comparison), groups = comparisonFollowUps(comparison);
  assert.equal(groups.length, 1);
  assert.deepEqual(groups[0].occurrences, [
    { optionIndex: 0, explanation: "Authentication events: absent" }, { optionIndex: 0, explanation: "Retention: absent" },
    { optionIndex: 1, explanation: "Export: absent" },
  ]);
  assert.equal(new Set(groups[0].occurrences.map(o => o.optionIndex)).size, 2, "Two options, not three checks.");
  groups[0].occurrences[0].explanation = "Display changed";
  assert.deepEqual(comparison, before, "No mutable alias or input mutation.");
});

test("hard gaps and unknown preferences never merge; excluded options keep gaps and known failures/preferences are not invented as questions", () => {
  const comparison = comparisonUiFixture();
  comparison.candidates[0].informationGaps = [finding("CAPABILITY", "EVIDENCE_STALE")];
  comparison.candidates[1].capabilityPreferences[0] = { capability: "SCIM", profilePath: "provisioning.scim", outcome: "UNKNOWN",
    reasonCode: "EVIDENCE_STALE", explanation: "SCIM preference evidence stale" };
  const groups = comparisonFollowUps(comparison);
  assert.equal(groups.length, 2);
  assert.deepEqual(groups.map(g => g.source), ["HARD_CHECK", "PREFERENCE"]);
  assert.deepEqual(groups[0].occurrences.map(o => o.optionIndex), [0, 1]);
  assert.deepEqual(groups[1].occurrences.map(o => o.optionIndex), [1]);
  assert.equal(JSON.stringify(groups).includes("REQUIRED_CAPABILITY_UNAVAILABLE"), false);
  assert.equal(JSON.stringify(groups).includes("PREFERRED_CAPABILITY_UNAVAILABLE"), false);
  assert.equal(comparison.candidates[0].hardVerdict, "EXCLUDED");
  assert.equal(comparison.candidates[2].hardVerdict, "PASSES_CHECKED_REQUIREMENTS");
});

test("group identity includes exact dimension/path/reason, never label similarity or a prefix match", () => {
  const comparison = comparisonUiFixture();
  comparison.candidates.forEach(c => { c.informationGaps = []; c.capabilityPreferences = []; });
  comparison.candidates[0].informationGaps = [finding("CAPABILITY", "EVIDENCE_MISSING"), finding("CAPABILITY", "EVIDENCE_STALE"),
    finding("AUDITABILITY", "EVIDENCE_MISSING"), finding("CAPABILITY", "EVIDENCE_MISSING", "provisioning.scim.extra")];
  assert.equal(comparisonFollowUps(comparison).length, 4);
});

async function rendered(editable = true, mutate?: (comparison: ReturnType<typeof comparisonUiFixture>, profile: ReturnType<typeof savedRequirementsFixture>) => void) {
  const components = await assessmentUiComponents(), comparison = comparisonUiFixture(), profile = savedRequirementsFixture();
  mutate?.(comparison, profile);
  const before = structuredClone({ comparison, profile });
  const html = renderToStaticMarkup(createElement(components.ComparisonFollowUps, { comparison, groups: savedRequirementGroups(profile), editable }));
  assert.deepEqual({ comparison, profile }, before);
  return html;
}

test("actual server UI separates requirements, evidence and boundaries with scoped option links and safe editor buttons", async () => {
  const html = await rendered(true, c => {
    c.candidates[0].informationGaps = [finding("CAPABILITY", "REQUIREMENT_UNKNOWN", "protocols.oauth2ProtectedApis"),
      finding("COMPLIANCE_SCOPE", "COMPLIANCE_TARGETS_NOT_EVALUATED", "security.complianceScopeStatus")];
  });
  for (const copy of ["What still needs clarification?", "Clarify your saved requirements", "Review catalog evidence", "Review remaining check boundaries",
    "saved version 7", "not severity, priority or completeness", "cannot reverse an independent exclusion", "not a weaker requirement",
    "Hard-check information gap", "Unknown preference — not a hard failure", "Inspect related saved input", "No new question, verdict or score"]) assert.ok(html.includes(copy), copy);
  assert.ok(html.includes('href="#comparison-option-0"')); assert.ok(html.includes('href="#comparison-option-1"'));
  assert.equal(html.includes("<form"), false); assert.equal(html.includes("<input"), false);
  assert.ok(html.includes("OAuth 2.0 protected APIs"));
});

test("read-only, unreadable and unknown paths never offer an invented editor or expose raw saved fields", async () => {
  const readOnly = await rendered(false);
  assert.ok(readOnly.includes("Review saved requirements")); assert.equal(readOnly.includes("Inspect related saved input"), false);
  const html = await rendered(true, (c, p) => {
    c.candidates.forEach(option => { option.informationGaps = []; option.capabilityPreferences = []; });
    c.candidates[0].informationGaps = [finding("CONTEXT", "PROFILE_CONTEXT_UNKNOWN", "application.type"),
      finding("CAPABILITY", "REQUIREMENT_UNKNOWN", "provisioning.scim.extra"), finding("COVERAGE", "__proto__", "constructor")];
    p.application = { type: "private-invalid-value" } as typeof p.application;
  });
  assert.ok(html.includes("Related saved inputs cannot be read safely")); assert.ok(html.includes("Checked scope"));
  assert.equal(html.includes("Inspect related saved input"), false); assert.equal(html.includes("private-invalid-value"), false);
});

test("no returned gaps is an explicit limited statement, not readiness; multiple checks count distinct options", async () => {
  const empty = await rendered(true, c => c.candidates.forEach(option => {
    option.informationGaps = []; option.capabilityPreferences = [];
  }));
  assert.ok(empty.includes("No unknowns were returned within these checks")); assert.ok(empty.includes("Known exclusions and unchecked boundaries may still remain"));
  assert.equal(empty.includes("Review catalog evidence"), false);
  const html = await rendered(true, c => {
    c.candidates.forEach(option => { option.informationGaps = []; option.capabilityPreferences = []; });
    c.candidates[0].informationGaps = [finding("AUDITABILITY", "EVIDENCE_MISSING", "security.auditabilityRequirements", "Event checks unknown"),
      finding("AUDITABILITY", "EVIDENCE_MISSING", "security.auditabilityRequirements", "Retention check unknown")];
  });
  assert.ok(html.includes("Auditability criteria and retention · 1 option")); assert.equal(html.includes("· 2 options"), false);
  assert.ok(html.includes("Event checks unknown")); assert.ok(html.includes("Retention check unknown"));
});

test("literal Core text is escaped; arbitrary paths and identifiers never become links or executable source URLs", async () => {
  const html = await rendered(true, c => {
    c.candidates.forEach(option => { option.informationGaps = []; option.capabilityPreferences = []; });
    c.candidates[0].displayName = '<script>fictional()</script>';
    c.candidates[0].optionId = 'javascript:fictional()';
    c.candidates[0].informationGaps = [finding("COVERAGE", '<img src=x onerror="fictional()">', "https://catalog.invalid/raw", '<script>exact()</script>')];
  });
  assert.ok(html.includes("&lt;script&gt;fictional()&lt;/script&gt;")); assert.ok(html.includes("&lt;script&gt;exact()&lt;/script&gt;"));
  assert.ok(html.includes("&lt;img src=x")); assert.equal(html.includes("<script>"), false);
  assert.equal(html.includes('href="https://'), false); assert.equal(html.includes("javascript:"), false);
  assert.ok(html.includes('href="#comparison-option-0"')); assert.equal(html.includes("Inspect related saved input"), false);
});
