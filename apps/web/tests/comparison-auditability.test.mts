import assert from "node:assert/strict";
import { test } from "node:test";
import { comparisonFromCore, type ComparisonFinding } from "../src/lib/assessment/comparison.ts";
import type { AuditabilityValues } from "../src/lib/assessment/auditability.ts";
import { auditabilityBinding, auditabilityInput } from "./fixtures/auditability-preview.mts";
import { comparisonAuditFixture, noAuditRequirement } from "./fixtures/comparison-auditability.mts";

const fallback = { dimension: "COVERAGE", profilePath: "assessment", reasonCode: "NO_AFFIRMATIVE_CHECKS", explanation: "No affirmative checks." };
const seed = {
  workspaceId: auditabilityBinding.workspaceId, assessmentId: auditabilityBinding.assessmentId,
  assessmentVersion: 2, catalogVersion: "synthetic-test", catalogKind: "SYNTHETIC",
  preferencePolicyVersion: "capability-preference-1", evaluatedAt: "2026-10-02T12:00:00Z",
  scope: "SYNTHETIC_UNRANKED_COMPARISON", recommendationReady: false, rankingPerformed: false,
  deferredPaths: ["security.auditability", "operations"],
  candidates: [{ optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo", region: "Synthetic region",
    hardVerdict: "UNRESOLVED", exclusionReasons: [] as ComparisonFinding[], informationGaps: [fallback], capabilityPreferences: [] }],
};
const binding = (values: AuditabilityValues) => ({ ...auditabilityBinding, values });
const failed: AuditabilityValues = { criticality: "REQUIRED", selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: 180 };

for (const criticality of ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "UNKNOWN", "FORBIDDEN"] as const) {
  for (const selected of [false, true]) test(`combined ${criticality} with ${selected ? "selected" : "empty"} audit scope`, () => {
    const values: AuditabilityValues = { ...auditabilityInput, criticality,
      selectedCriteria: selected ? [...auditabilityInput.selectedCriteria] : [], minimumRetentionDays: selected ? 30 : null };
    const raw = comparisonAuditFixture(seed, values), before = structuredClone(raw);
    const result = comparisonFromCore(raw, binding(values));
    const pass = criticality === "REQUIRED" && selected;
    assert.equal(result.candidates[0].hardVerdict, pass ? "PASSES_CHECKED_REQUIREMENTS" : "UNRESOLVED");
    assert.equal(result.candidates[0].informationGaps.some(f => f.reasonCode === "NO_AFFIRMATIVE_CHECKS"), !pass);
    assert.equal("auditability" in result, false);
    assert.equal(result.auditabilityEvidenceVersion, raw.auditability.evidenceVersion);
    assert.deepEqual(raw, before, "Do not mutate the Core response or saved answers.");
  });
}

test("retention failure excludes, uses fixed copy and preserves unrelated gaps and failures", () => {
  const raw = comparisonAuditFixture({ ...seed, candidates: [{ ...seed.candidates[0],
    exclusionReasons: [{ dimension: "CAPABILITY", profilePath: "provisioning.scim", reasonCode: "REQUIRED_CAPABILITY_UNAVAILABLE", explanation: "SCIM unavailable." }],
    informationGaps: [{ dimension: "CONTEXT", profilePath: "audience.tenancy", reasonCode: "REQUIREMENT_UNKNOWN", explanation: "Tenancy unknown." }] }] }, failed);
  raw.candidates[0].exclusionReasons[1].explanation = "AUDIT_LOG_RETENTION: Arbitrary compliance claim.";
  const result = comparisonFromCore(raw, binding(failed));
  assert.equal(result.candidates[0].hardVerdict, "EXCLUDED");
  assert.equal(result.candidates[0].exclusionReasons[0].explanation, "SCIM unavailable.");
  assert.equal(result.candidates[0].informationGaps[0].explanation, "Tenancy unknown.");
  assert.equal(result.candidates[0].exclusionReasons[1].explanation,
    "Log retention: The documented provider minimum of 90 days is below your requested 180 days. Deployed retention is not verified.");
  assert.equal(JSON.stringify(result).includes("Arbitrary compliance claim"), false);
  assert.equal(JSON.stringify(result).includes("sourceUrl"), false);
});

test("an affirmative audit check removes only the no-affirmative-checks placeholder", () => {
  const raw = comparisonAuditFixture({ ...seed, candidates: [{ ...seed.candidates[0], informationGaps: [fallback,
    { dimension: "CONTEXT", profilePath: "audience.tenancy", reasonCode: "REQUIREMENT_UNKNOWN", explanation: "Tenancy unknown." }] }] }, auditabilityInput);
  const result = comparisonFromCore(raw, binding(auditabilityInput));
  assert.equal(result.candidates[0].hardVerdict, "UNRESOLVED");
  assert.deepEqual(result.candidates[0].informationGaps.map(f => f.reasonCode), ["REQUIREMENT_UNKNOWN"]);
});

const valid = comparisonAuditFixture(seed, failed);
for (const [name, mutate] of [
  ["nested workspace", (r: typeof valid) => { r.auditability.workspaceId = "70000000-0000-4000-8000-000000000002"; }],
  ["nested assessment", (r: typeof valid) => { r.auditability.assessmentId = "80000000-0000-4000-8000-000000000002"; }],
  ["nested version", (r: typeof valid) => { r.auditability.assessmentVersion++; }],
  ["base catalog", (r: typeof valid) => { r.auditability.baseCatalogVersion = "synthetic-other"; }],
  ["evaluation instant", (r: typeof valid) => { r.auditability.evaluatedAt = "2026-10-02T12:00:01Z"; r.auditability.candidates[0].analysis.evaluatedAt = r.auditability.evaluatedAt; }],
  ["foreign option", (r: typeof valid) => { r.candidates[0].optionId = "foreign-option"; }],
  ["foreign plan", (r: typeof valid) => { r.candidates[0].plan = "Other plan"; }],
  ["foreign region", (r: typeof valid) => { r.candidates[0].region = "Other region"; }],
  ["foreign display name", (r: typeof valid) => { r.candidates[0].displayName = "Other name"; }],
  ["extra audit scope", (r: typeof valid) => { r.auditability.candidates.push(structuredClone(r.auditability.candidates[0])); }],
  ["hidden audit failure", (r: typeof valid) => { r.candidates[0].exclusionReasons = []; r.candidates[0].hardVerdict = "UNRESOLVED"; }],
  ["extra audit failure", (r: typeof valid) => { r.candidates[0].exclusionReasons.push(structuredClone(r.candidates[0].exclusionReasons[0])); }],
  ["wrong failure reason", (r: typeof valid) => { r.candidates[0].exclusionReasons[0].reasonCode = "CAPABILITY_UNAVAILABLE"; }],
  ["wrong finding path", (r: typeof valid) => { r.candidates[0].exclusionReasons[0].profilePath = "operations"; }],
  ["wrong criterion", (r: typeof valid) => { r.candidates[0].exclusionReasons[0].explanation = "AUDIT_LOG_EXPORT: Incorrect criterion."; }],
  ["forged check", (r: typeof valid) => { r.auditability.candidates[0].analysis.checks[5].outcome = "PASS"; }],
  ["missing failure precedence", (r: typeof valid) => { r.candidates[0].hardVerdict = "UNRESOLVED"; }],
  ["legacy policy", (r: typeof valid) => { r.policyVersion = "synthetic-comparison-1"; }],
] as const) test(`combined guard rejects ${name}`, () => {
  const raw = structuredClone(valid); mutate(raw);
  assert.throws(() => comparisonFromCore(raw, binding(failed)));
});

test("unknowns cannot be hidden, reordered, turned into failures or bound to different saved inputs", () => {
  const values: AuditabilityValues = { ...noAuditRequirement, criticality: "UNKNOWN" };
  const raw = comparisonAuditFixture(seed, values);
  for (const mutate of [
    (r: typeof raw) => { r.candidates[0].informationGaps.pop(); },
    (r: typeof raw) => { r.candidates[0].informationGaps.reverse(); },
    (r: typeof raw) => { r.candidates[0].exclusionReasons.push(r.candidates[0].informationGaps.pop()!); r.candidates[0].hardVerdict = "EXCLUDED"; },
  ]) { const changed = structuredClone(raw); mutate(changed); assert.throws(() => comparisonFromCore(changed, binding(values))); }
  assert.throws(() => comparisonFromCore(raw, binding(noAuditRequirement)));
  assert.throws(() => comparisonFromCore(valid, binding({ ...failed, minimumRetentionDays: 181 })));
});

test("a positive audit check cannot coexist with a no-affirmative-checks gap", () => {
  const raw = comparisonAuditFixture(seed, auditabilityInput);
  raw.candidates[0].informationGaps.push(fallback); raw.candidates[0].hardVerdict = "UNRESOLVED";
  assert.throws(() => comparisonFromCore(raw, binding(auditabilityInput)));
});
