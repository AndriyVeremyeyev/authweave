import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { configurationRegressionFixture } from "./architecture-configuration-regression-spec.mjs";
import { lifecycleRegressionFixture } from "./lifecycle-regression-spec.mjs";
import { operationsRegressionFixture, operationsRegressionHash as hash } from "./operations-planning-regression-spec.mjs";
import { assuranceRegressionFixture } from "./assurance-compliance-regression-spec.mjs";

export const planningFamilies = ["ARCHITECTURE_CONFIGURATION", "PROVISIONING_LIFECYCLE", "OPERATIONS_PLANNING", "ASSURANCE_COMPLIANCE"];
export const planningCoverageFlags = ["candidateChangesEvaluated", "coverageComplete", "configurationVerified", "lifecycleVerified", "operationalReadinessVerified", "costModelEvaluated", "assuranceVerified", "complianceVerified",
  "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed"];
const policy = "catalog-profile-planning-coverage-1", structuralManifest = "eb96cb4962d7a4f2292e39dad8900e5e40d89c0781275fb7347643a4d27ea95b";
const summaries = () => [configurationRegressionFixture(), lifecycleRegressionFixture(), operationsRegressionFixture(), assuranceRegressionFixture()];
// Independent routing table: profile shape comes from unchanged source fixtures, not Core output.
const paths = [
  ["application.clients", "security.browserTokenExposureMinimization"],
  ["provisioning.scim", "provisioning.justInTimeProvisioning", "provisioning.groupSynchronization"],
  ["operations.hosting", "operations.deploymentTarget", "operations.identityExpertise", "operations.budgetSensitivity", "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes"],
  ["application.clients", "audience.populations", "security.assurance", "security.multiFactorAuthentication", "security.authenticationControls.phishingResistance", "security.authenticationControls.nonExportableKeys", "security.authenticationControls.stepUpAuthentication", "security.complianceScopeStatus", "security.complianceTargets"],
];
function inputs(value, path = "") {
  return value !== null && typeof value === "object" && !Array.isArray(value) && path !== "operations.usagePlanning.volumes"
    ? Object.entries(value).flatMap(([key, child]) => inputs(child, path ? `${path}.${key}` : key)) : [path];
}
export function planningInputRoutes() {
  const sources = JSON.parse(readFileSync(new URL("../../../../services/core-api/src/main/resources/catalog/scoped-impact-scenarios.v1.json", import.meta.url), "utf8"));
  const declared = inputs(sources[0].profile).filter(path => path !== "security.auditabilityRequirements");
  declared.push("security.auditabilityRequirements.selectedCriteria", "security.auditabilityRequirements.minimumRetentionDays");
  assert.equal(declared.length, 34); assert.equal(new Set(declared).size, 34);
  assert.ok(paths.flat().every(path => declared.includes(path)));
  return declared.toSorted().map(profilePath => ({ profilePath, planningRegressions: planningFamilies.filter((_, i) => paths[i].includes(profilePath)) }));
}
export function planningRegressionBindings() {
  return summaries().map((check, i) => ({ family: planningFamilies[i], policyVersion: check.policyVersion, definitionsSha256: check.definitionsSha256,
    scenarioSetVersion: check.scenarioSetVersion, scenarioSetSha256: check.scenarioSetSha256, profileSchemaVersion: check.profileSchemaVersion,
    profileSchemaSha256: check.profileSchemaSha256, analysisSha256: check.analysisSha256, checkSha256: hash(check), checkedCases: check.checkedCases }));
}
export function planningVerificationGaps() { return summaries().flatMap((check, i) => check.deferredBoundaries.map(boundary => ({ family: planningFamilies[i], boundary }))); }
export function planningManifestSha256() {
  return hash([policy, structuralManifest, planningInputRoutes(), planningVerificationGaps(), planningRegressionBindings().map(b => [b.family, b.policyVersion, b.definitionsSha256])]);
}
export function planningCoverageExpectation(structural) {
  const s = assuranceRegressionFixture(), evaluatedAt = s.evaluatedAt;
  assert.equal(structural.status, "INCOMPLETE"); assert.equal(structural.evaluatedAt, evaluatedAt);
  assert.equal(structural.baseScenarioSetSha256, s.baseScenarioSetSha256); assert.equal(structural.scenarioSetSha256, s.auditabilityScenarioSetSha256);
  assert.equal(structural.manifestSha256, structuralManifest); assert.equal(structural.dimensions.length, 136);
  const routes = planningInputRoutes(), regressions = planningRegressionBindings(), verificationGaps = planningVerificationGaps();
  const dimensions = structural.dimensions.map(d => ({ scenarioId: d.scenarioId, profilePath: d.profilePath, structuralState: d.state,
    planningRegressions: routes.find(r => r.profilePath === d.profilePath).planningRegressions }));
  const manifestSha256 = planningManifestSha256(), structuralCoverageSha256 = hash(structural);
  return { evaluatedAt, structuralCoverage: structuredClone(structural), regressions, dimensions, verificationGaps,
    scope: "CATALOG_PROFILE_PLANNING_COVERAGE", status: "INCOMPLETE", analysisBasis: "STRUCTURAL_CATALOG_RULES_AND_SEPARATE_SYNTHETIC_PLANNING_REGRESSIONS", policyVersion: policy, manifestSha256,
    profileSchemaVersion: 6, profileSchemaSha256: s.profileSchemaSha256, baseScenarioSetSha256: structural.baseScenarioSetSha256,
    auditabilityScenarioSetSha256: structural.scenarioSetSha256, structuralCoverageSha256, declaredProfileInputs: 34, declaredScenarios: 4, checkedDimensions: 136, checkedRegressionFamilies: 4,
    planningAddedToDeferredDimensions: dimensions.filter(d => d.structuralState === "DEFERRED_DIMENSION" && d.planningRegressions.length).length,
    unroutedDeferredDimensions: dimensions.filter(d => d.structuralState === "DEFERRED_DIMENSION" && !d.planningRegressions.length).length,
    analysisSha256: hash([policy, evaluatedAt, manifestSha256, structuralCoverageSha256, regressions, dimensions, verificationGaps]),
    ...Object.fromEntries(planningCoverageFlags.map(flag => [flag, false])) };
}
export function validatePlanningCoverage(payload, structural) {
  assert.deepEqual(payload, planningCoverageExpectation(structural), "Composition must preserve the separately obtained structural HTTP result and independently replay all four frozen planning bindings, routes and gaps.");
}
