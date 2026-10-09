import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { lifecycleRegressionInputs } from "./lifecycle-regression-spec.mjs";
import { planningVerificationGaps } from "./profile-planning-coverage-spec.mjs";

// Independent source/binding checks, not a Java evaluator or a publication authorization token.
const resource = new URL("../../../../services/core-api/src/main/resources/catalog/", import.meta.url);
export const decisionPolicy = JSON.parse(readFileSync(new URL("../../decision-core/policy.v1.json", import.meta.url), "utf8"));
const base = JSON.parse(readFileSync(new URL("scoped-impact-scenarios.v1.json", resource), "utf8"));
const audit = JSON.parse(readFileSync(new URL("scoped-auditability-scenarios.v1.json", resource), "utf8"));
export const profileSchemaSha256 = "3d384daf2d98851ebcbe00f90a9c6dabeedbae8361f8a41f58d00b9742c95fbb";
export const coverageVersion = "publication-decision-coverage-1", coverageScope = "DECLARED_DECISION_RULES_ONLY";
export const canonicalization = "SHA256_UTF8_COMPACT_JSON_SORTED_OBJECT_KEYS_PRESERVED_ARRAY_ORDER";
export const componentVersions = {
  hardChecks: "decision-hard-check-2", scoring: "decision-preference-scoring-2", composition: "decision-candidate-composition-2",
  architecture: "decision-conditional-architecture-1", impact: "decision-candidate-impact-1", auditability: "decision-auditability-input-1",
  patterns: "architecture-pattern-preflight-1", prerequisites: "architecture-prerequisites-1",
  provisioning: "provisioning-lifecycle-design-1", provisioningConditions: "provisioning-lifecycle-design-2",
  claimRules: "assertion-claim-rules-1", capabilityRules: "capability-preflight-1", contextRules: "eligibility-preflight-1",
  residencyRules: "residency-preflight-1", authenticationRules: "authentication-controls-preflight-1",
  complianceRules: "compliance-scope-preflight-1", auditabilityRules: "auditability-capability-preflight-1",
  evidenceMaxAgeDays: "90", storedReviewLoading: "decision-stored-review-loading-1",
};
export const withheldFlags = ["coverageComplete", "currentCuratorAuthorityVerified", "sourceVerificationPerformed", "configurationVerified",
  "complianceVerified", "actualGoldenAcceptancePerformed", "historicalReportPromotionPerformed", "approvalGranted", "publicationReady", "writesPerformed"];
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(k => [k, canonical(value[k])]));
  return value;
}
export const orderedHash = value => createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex");
const limitationOnly = new Set(["security.assurance", "security.complianceTargets", "operations.hosting", "operations.deploymentTarget",
  "operations.identityExpertise", "operations.budgetSensitivity", "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes"]);
export const routes = decisionPolicy.inputRoutes.map(route => {
  const outputProfilePath = route.profilePath.startsWith("security.dataResidencyDetails.") ? "security.dataResidency"
    : route.profilePath.startsWith("security.auditabilityRequirements.") ? "security.auditability" : route.profilePath;
  const handling = outputProfilePath === "security.auditability" ? "AUDITABILITY_FINDINGS"
    : route.profilePath === "security.browserTokenExposureMinimization" ? "CONDITIONAL_ARCHITECTURE"
    : limitationOnly.has(route.profilePath) ? "EXPLICIT_LIMITATION" : "CANDIDATE_FINDINGS";
  return { ...route, handling, outputProfilePath };
});
export const scenarios = base.toSorted((a,b) => a.id < b.id ? -1 : 1).map(source => {
  const profile = structuredClone(source.profile), requirement = audit.scenarios.find(s => s.scenarioId === source.id);
  profile.security.auditabilityRequirements = { selectedCriteria: requirement.selectedCriteria, minimumRetentionDays: requirement.minimumRetentionDays };
  const values = source.id === "partner-portal-scoped" ? [{ capability: "JIT", weight: 100 }]
    : source.id === "internal-workforce-scoped" ? [{ capability: "SAML", weight: 100 }] : [];
  return { id: source.id, profile, weights: { mode: values.length ? "EXPLICIT" : "NONE", values } };
});
export const verificationGaps = planningVerificationGaps();
const { baseSha, auditSha } = lifecycleRegressionInputs();
export const decisionPolicySha256 = orderedHash(decisionPolicy), scenarioSetSha256 = orderedHash(scenarios);
export const manifestSha256 = orderedHash([coverageVersion, coverageScope, decisionPolicySha256,
  "catalog-scoped-profile-scenarios-1", baseSha, "catalog-scoped-auditability-scenarios-1", auditSha,
  profileSchemaSha256, routes, scenarios, componentVersions, verificationGaps]);

export function assertCoverage(check, expectedBinding) {
  assert.equal(check.scope, coverageScope); assert.equal(check.policyVersion, coverageVersion); assert.equal(check.canonicalization, canonicalization);
  assert.equal(check.profileSchemaVersion, 6); assert.equal(check.profileSchemaSha256, profileSchemaSha256);
  assert.equal(check.decisionPolicySha256, decisionPolicySha256); assert.equal(check.scenarioSetSha256, scenarioSetSha256); assert.equal(check.manifestSha256, manifestSha256);
  assert.deepEqual(check.componentVersions, componentVersions); assert.deepEqual(check.verificationGaps, verificationGaps);
  for (const flag of withheldFlags) assert.equal(check[flag], false, flag);
  assert.deepEqual(check.scenarios.map(s => s.scenarioId), scenarios.map(s => s.id));
  if (expectedBinding) for (const key of ["before", "after", "evaluatedAt"]) assert.deepEqual(check[key], expectedBinding[key], `Exact originating ${key}`);
  for (const key of ["beforeInputSha256", "afterInputSha256", "beforeOptions", "afterOptions"]) assert.equal(new Set(check.scenarios.map(s => s[key])).size, 1, key);
  for (const [i, scenario] of check.scenarios.entries()) {
    assert.equal(scenario.profileSha256, orderedHash(scenarios[i].profile)); assert.equal(scenario.weightsSha256, orderedHash(scenarios[i].weights));
    assert.deepEqual(scenario.routes.map(r => r.profilePath), routes.map(r => r.profilePath));
    for (const [j, route] of scenario.routes.entries()) {
      assert.equal(route.handling, routes[j].handling); assert.equal(route.accounted, route.beforeOutputs > 0 && route.afterOutputs > 0);
      if (route.handling === "EXPLICIT_LIMITATION") { assert.equal(route.beforeOutputs, 1); assert.equal(route.afterOutputs, 1); }
      if (route.handling === "CONDITIONAL_ARCHITECTURE") { assert.equal(route.beforeOutputs, 5); assert.equal(route.afterOutputs, 5); }
      if (route.handling.endsWith("FINDINGS") && route.accounted) {
        assert(route.beforeOutputs >= scenario.beforeOptions); assert(route.afterOutputs >= scenario.afterOptions);
      }
    }
  }
  const complete = check.uncoveredFacts.length === 0 && check.scenarios.every(s => s.routes.every(r => r.accounted));
  assert.equal(check.decisionScopeCoverageComplete, complete);
  assert.equal(check.status, complete ? "COMPLETE_DECLARED_SCOPE" : "INCOMPLETE");
  // Read provenance is a distinct statement. Unknown outcome counts can coexist with complete rule execution.
  assert.equal(typeof check.storedSourceReviewsVerified, "boolean");
}
