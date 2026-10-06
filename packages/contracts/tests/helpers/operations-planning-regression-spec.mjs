import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { operationsPlanningExpectation, operationsMetrics, operationsCheckedPaths, operationsDeferred } from "./operations-planning-spec.mjs";

// Independent source/wire replay. Never read Java output or import a production evaluator.
const sourceRoot = new URL("../../../../services/core-api/src/main/resources/catalog/", import.meta.url);
export const operationsPreferenceVariants = ["BASE_PROFILE", "MANAGED_AZURE", "SELF_HOSTED_ON_PREMISES", "NO_PREFERENCE_AWS", "EXPLICIT_UNKNOWN", "MANAGED_GOOGLE_CLOUD", "SELF_HOSTED_MULTI_CLOUD"];
export const operationsUsageVariants = ["UNRECORDED", "OBSERVED_ZERO", "ASSUMED_WITHOUT_ASSUMPTIONS", "ASSUMED_WITH_ASSUMPTIONS", "SPARSE_OBSERVED"];
const preferences = {
  MANAGED_AZURE: ["MANAGED", "AZURE", "LIMITED", "HIGH"], SELF_HOSTED_ON_PREMISES: ["SELF_HOSTED", "ON_PREMISES", "ADVANCED", "HIGH"],
  NO_PREFERENCE_AWS: ["NO_PREFERENCE", "AWS", "MODERATE", "MODERATE"], EXPLICIT_UNKNOWN: ["UNKNOWN", "UNDECIDED", "UNKNOWN", "UNKNOWN"],
  MANAGED_GOOGLE_CLOUD: ["MANAGED", "GOOGLE_CLOUD", "ADVANCED", "LOW"], SELF_HOSTED_MULTI_CLOUD: ["SELF_HOSTED", "MULTI_CLOUD", "MODERATE", "LOW"],
};
const profileKeys = ["hosting", "deploymentTarget", "identityExpertise", "budgetSensitivity"];
const baseVersion = "catalog-scoped-profile-scenarios-1", auditVersion = "catalog-scoped-auditability-scenarios-1";
const version = "catalog-operations-planning-scenarios-1", schemaSha = "3d384daf2d98851ebcbe00f90a9c6dabeedbae8361f8a41f58d00b9742c95fbb";
const workspaceId = "60000000-0000-4000-8000-000000000001";
const assessmentId = index => `80000000-0000-4000-8000-${(index + 1).toString(16).padStart(12, "0")}`;
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical).sort((a,b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
export function operationsRegressionHash(value) { return createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex"); }
function planning(variant) {
  if (variant === "UNRECORDED") return { scopeDescription: "", assumptions: [], volumes: {} };
  if (variant === "SPARSE_OBSERVED") return { scopeDescription: " \t\n", assumptions: [], volumes: { MONTHLY_ACTIVE_USERS: { basis: "OBSERVED", value: 0 } } };
  return { scopeDescription: "Synthetic planning month", assumptions: variant === "ASSUMED_WITH_ASSUMPTIONS" ? ["Synthetic forecast, not verified usage"] : [],
    volumes: Object.fromEntries(operationsMetrics.map(metric => [metric, { basis: variant === "OBSERVED_ZERO" ? "OBSERVED" : "ASSUMED",
      value: variant === "ASSUMED_WITH_ASSUMPTIONS" ? Number.MAX_SAFE_INTEGER : 0 }])) };
}
export function operationsRegressionInputs() {
  const sources = JSON.parse(readFileSync(new URL("scoped-impact-scenarios.v1.json", sourceRoot), "utf8"));
  const supplement = JSON.parse(readFileSync(new URL("scoped-auditability-scenarios.v1.json", sourceRoot), "utf8"));
  const baseSha = operationsRegressionHash(sources);
  assert.equal(baseSha, "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1");
  assert.equal(supplement.baseScenarioSetSha256, baseSha); assert.equal(supplement.baseScenarioSetVersion, baseVersion); assert.equal(sources.length, 4);
  const profiles = sources.toSorted((a,b) => a.id < b.id ? -1 : 1).map(source => {
    assert.equal(source.profileSchemaVersion, 5); assert.equal(Object.hasOwn(source.profile.security, "auditabilityRequirements"), false);
    const requirements = supplement.scenarios.find(s => s.scenarioId === source.id);
    assert.ok(requirements);
    const profile = structuredClone(source.profile);
    profile.security.auditabilityRequirements = { selectedCriteria: [...requirements.selectedCriteria], minimumRetentionDays: requirements.minimumRetentionDays };
    return { scenarioId: source.id, criticality: profile.security.auditability, requirements: profile.security.auditabilityRequirements,
      profileSha256: operationsRegressionHash(profile), profile };
  });
  const auditDefinitions = profiles.map(({ profile, ...definition }) => { void profile; return definition; });
  const auditSha = operationsRegressionHash([auditVersion, 6, schemaSha, baseSha, supplement, auditDefinitions]);
  const definitions = profiles.flatMap(source => operationsPreferenceVariants.flatMap(preferenceVariant => operationsUsageVariants.map(usageVariant => {
    const inputs = preferenceVariant === "BASE_PROFILE" ? Object.fromEntries(profileKeys.map(key => [key, source.profile.operations[key]])) : Object.fromEntries(profileKeys.map((key,i) => [key, preferences[preferenceVariant][i]]));
    const usagePlanning = planning(usageVariant), profile = structuredClone(source.profile);
    profile.operations = { ...inputs, usagePlanning };
    return { scenarioId: source.scenarioId, preferenceVariant, usageVariant, sourceProfileSha256: source.profileSha256,
      profileSha256: operationsRegressionHash(profile), inputs, usagePlanning };
  })));
  assert.equal(definitions.length, 140);
  return { definitions, baseSha, auditSha, scenarioSha: operationsRegressionHash([version, auditVersion, auditSha, schemaSha, baseSha, definitions]) };
}
export function operationsRegressionFixture(evaluatedAt = "2026-09-12T12:00:00Z") {
  const { definitions, baseSha, auditSha, scenarioSha } = operationsRegressionInputs();
  const rows = definitions.map((input, index) => ({ input, analysis: operationsPlanningExpectation({ workspaceId, id: assessmentId(index), version: 0,
    profile: { operations: { ...input.inputs, usagePlanning: input.usagePlanning } } }, evaluatedAt) }));
  const count = (select, expected) => rows.filter(row => select(row.analysis) === expected).length;
  const optionCount = (key, expected) => rows.flatMap(row => row.analysis.options).filter(option => option[key] === expected).length;
  const unknown = Object.fromEntries(profileKeys.map(key => [key, key === "deploymentTarget" ? "UNDECIDED" : "UNKNOWN"]));
  const generic = operationsPlanningExpectation({ workspaceId, id: assessmentId(0), version: 0, profile: { operations: unknown } }, "1970-01-01T00:00:00Z");
  return { evaluatedAt, scenarioSetSha256: scenarioSha, auditabilityScenarioSetSha256: auditSha,
    analysisSha256: operationsRegressionHash({ evaluatedAt, scenarioSetSha256: scenarioSha, rows }),
    inputResults: { inputsRecorded: count(a => a.status, "INPUTS_RECORDED"), needsInformation: count(a => a.status, "NEEDS_INFORMATION") },
    usageResults: { inputsRecorded: count(a => a.usageInputs.status, "INPUTS_RECORDED"), needsInformation: count(a => a.usageInputs.status, "NEEDS_INFORMATION") },
    hosting: { preferenceAligned: optionCount("hostingAlignment", "PREFERENCE_ALIGNED"), preferenceDiffers: optionCount("hostingAlignment", "PREFERENCE_DIFFERS"), noPreference: optionCount("hostingAlignment", "NO_PREFERENCE"), preferenceUnknown: optionCount("hostingAlignment", "PREFERENCE_UNKNOWN") },
    support: { integrationSupportPlanNeeded: optionCount("supportPlanning", "INTEGRATION_SUPPORT_PLAN_NEEDED"), operatorSupportPlanNeeded: optionCount("supportPlanning", "OPERATOR_SUPPORT_PLAN_NEEDED"), responsibilityPlanNeeded: optionCount("supportPlanning", "RESPONSIBILITY_PLAN_NEEDED"), supportCapacityUndefined: optionCount("supportPlanning", "SUPPORT_CAPACITY_UNDEFINED") },
    budget: { costModelNeeded: optionCount("budgetPlanning", "COST_MODEL_NEEDED"), budgetScopeUndefined: optionCount("budgetPlanning", "BUDGET_SCOPE_UNDEFINED") },
    recordedMetricChecks: rows.reduce((sum,row) => sum + row.analysis.usageInputs.recordedMetrics.length, 0), missingInputChecks: rows.reduce((sum,row) => sum + row.analysis.missingPaths.length, 0),
    scope: "SYNTHETIC_OPERATIONS_PLANNING_REGRESSION", policyVersion: "catalog-operations-planning-regression-1", operationsPolicyVersion: "operations-planning-preflight-1",
    definitionsSha256: operationsRegressionHash(generic), scenarioSetVersion: version, baseScenarioSetVersion: baseVersion, baseScenarioSetSha256: baseSha, auditabilityScenarioSetVersion: auditVersion,
    profileSchemaVersion: 6, profileSchemaSha256: schemaSha, analysisBasis: "UNVERIFIED_SYNTHETIC_INPUTS_AND_GENERIC_RESPONSIBILITIES", declaredProfiles: 4, checkedCases: 140, checkedOptions: 280,
    preferenceVariants: [...operationsPreferenceVariants], usageVariants: [...operationsUsageVariants], optionIds: ["MANAGED_IDENTITY_SERVICE", "SELF_HOSTED_IDENTITY_SERVICE"], usageMetrics: [...operationsMetrics], checkedPaths: [...operationsCheckedPaths], deferredBoundaries: [...operationsDeferred],
    ...Object.fromEntries(["candidateChangesEvaluated", "coverageComplete", "providerEligibilityEvaluated", "deploymentCompatibilityVerified", "operationalReadinessVerified", "pricingEvaluated", "costModelEvaluated", "budgetFitVerified", "configurationVerified", "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed"].map(flag => [flag, false])) };
}
export function validateOperationsRegression(payload) {
  assert.deepEqual(payload, operationsRegressionFixture(), "Actual fixed-clock summary must replay source profiles, every bounded overlay, digests, counts and unverified scope independently.");
}
