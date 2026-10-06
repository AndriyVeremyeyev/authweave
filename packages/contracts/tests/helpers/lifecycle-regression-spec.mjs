import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { lifecycleV2Expectation, lifecycleV2Scope, lifecycleV2Patterns, lifecycleV2Groups, lifecycleV2Ids,
  lifecycleV2Reasons, lifecycleV2Paths, lifecycleV2Deferred, lifecycleV2OffboardingReferences } from "./provisioning-lifecycle-v2-spec.mjs";

// Source/wire replay only. No Java output or production evaluator supplies expectations.
export const lifecycleRequirementVariants = ["BASE_PROFILE", "REQUIRED", "FORBIDDEN", "UNKNOWN", "PREFERRED", "NOT_REQUIRED"];
export const lifecycleDeclarationVariants = ["UNRECORDED", "EXPLICIT_UNKNOWN", "ALL_SATISFIED", "ALL_NOT_SATISFIED", "OFFBOARDING_GAPS", "OFFBOARDING_FAILURE_WITH_GAP", "GROUP_REMOVAL_GAP", "GROUP_REMOVAL_FAILURE_WITH_GAP"];
const sourceRoot = new URL("../../../../services/core-api/src/main/resources/catalog/", import.meta.url);
const keys = ["scim", "justInTimeProvisioning", "groupSynchronization"];
const version = "catalog-provisioning-lifecycle-scenarios-1", baseVersion = "catalog-scoped-profile-scenarios-1", auditVersion = "catalog-scoped-auditability-scenarios-1";
const schemaSha = "3d384daf2d98851ebcbe00f90a9c6dabeedbae8361f8a41f58d00b9742c95fbb";
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical).sort((a,b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
export const lifecycleRegressionHash = value => createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex");
export function lifecycleDeclarations(pattern, group, variant) {
  if (variant.startsWith("GROUP_REMOVAL") && !lifecycleV2Groups[group].length) return null;
  if (variant === "UNRECORDED") return {};
  const value = variant === "EXPLICIT_UNKNOWN" ? "UNKNOWN" : variant === "ALL_NOT_SATISFIED" ? "NOT_SATISFIED" : "SATISFIED";
  const result = Object.fromEntries(lifecycleV2Scope(pattern, group).map(id => [id, value]));
  if (["OFFBOARDING_GAPS", "OFFBOARDING_FAILURE_WITH_GAP"].includes(variant)) {
    delete result.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY;
    if (variant === "OFFBOARDING_GAPS") delete result.APPLICATION_SESSION_INVALIDATION;
    else result.APPLICATION_SESSION_INVALIDATION = "NOT_SATISFIED";
  }
  if (variant === "GROUP_REMOVAL_GAP") delete result.GROUP_REMOVAL_AND_ACCESS_RECHECK;
  if (variant === "GROUP_REMOVAL_FAILURE_WITH_GAP") { result.GROUP_REMOVAL_AND_ACCESS_RECHECK = "NOT_SATISFIED"; delete result.GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT; }
  return result;
}
export function lifecycleRegressionInputs() {
  const sources = JSON.parse(readFileSync(new URL("scoped-impact-scenarios.v1.json", sourceRoot), "utf8"));
  const supplement = JSON.parse(readFileSync(new URL("scoped-auditability-scenarios.v1.json", sourceRoot), "utf8"));
  const baseSha = lifecycleRegressionHash(sources); assert.equal(baseSha, "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1");
  assert.equal(supplement.baseScenarioSetSha256, baseSha); assert.equal(supplement.baseScenarioSetVersion, baseVersion); assert.equal(sources.length, 4);
  const profiles = sources.toSorted((a,b) => a.id < b.id ? -1 : 1).map(source => {
    assert.equal(source.profileSchemaVersion, 5); assert.equal(Object.hasOwn(source.profile.security, "auditabilityRequirements"), false);
    const input = supplement.scenarios.find(s => s.scenarioId === source.id); assert.ok(input);
    const profile = structuredClone(source.profile); profile.security.auditabilityRequirements = { selectedCriteria: input.selectedCriteria, minimumRetentionDays: input.minimumRetentionDays };
    return { scenarioId: source.id, criticality: profile.security.auditability, requirements: profile.security.auditabilityRequirements,
      profileSha256: lifecycleRegressionHash(profile), profile };
  });
  const auditDefinitions = profiles.map(({profile, ...definition}) => { void profile; return definition; });
  const auditSha = lifecycleRegressionHash([auditVersion, 6, schemaSha, baseSha, supplement, auditDefinitions]);
  const definitions = profiles.flatMap(source => lifecycleRequirementVariants.flatMap(requirementVariant => {
    const requirements = requirementVariant === "BASE_PROFILE" ? source.profile.provisioning : Object.fromEntries(keys.map(key => [key, requirementVariant]));
    const profile = structuredClone(source.profile); profile.provisioning = requirements;
    const profileSha256 = lifecycleRegressionHash(profile);
    return Object.keys(lifecycleV2Patterns).flatMap(patternId => Object.keys(lifecycleV2Groups).flatMap(groupStrategy => lifecycleDeclarationVariants.flatMap(declarationVariant => {
      const declarations = lifecycleDeclarations(patternId, groupStrategy, declarationVariant);
      return declarations === null ? [] : [{ scenarioId: source.scenarioId, requirementVariant, patternId, groupStrategy, declarationVariant,
        sourceProfileSha256: source.profileSha256, profileSha256, requirements, declarations }];
    })));
  }));
  assert.equal(definitions.length, 2016);
  return { definitions, baseSha, auditSha, scenarioSha: lifecycleRegressionHash([version, auditVersion, auditSha, schemaSha, baseSha, definitions]) };
}
export const lifecycleRegressionFlags = ["candidateChangesEvaluated", "coverageComplete", "configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified", "accessRevocationVerified",
  "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed"];
export function lifecycleRegressionFixture(evaluatedAt = "2026-09-12T12:00:00Z") {
  const { definitions, baseSha, auditSha, scenarioSha } = lifecycleRegressionInputs();
  const rows = definitions.map(input => ({ input, evaluatedAt, analysis: lifecycleV2Expectation(input.requirements, input.patternId, input.groupStrategy, input.declarations) }));
  const requirements = rows.flatMap(r => r.analysis.requirementChecks), designs = rows.flatMap(r => r.analysis.designChecks), conditions = rows.flatMap(r => r.analysis.conditionChecks);
  const counts = checks => Object.fromEntries(["CONDITIONALLY_SATISFIED", "CONDITIONALLY_NOT_SATISFIED", "UNKNOWN", "NOT_APPLIED"].map((outcome,i) =>
    [["conditionallySatisfied", "conditionallyNotSatisfied", "unknown", "notApplied"][i], checks.filter(c => c.outcome === outcome).length]));
  const scopes = Object.keys(lifecycleV2Patterns).flatMap(patternId => Object.keys(lifecycleV2Groups).map(groupStrategy => ({ patternId, groupStrategy, conditions: lifecycleV2Scope(patternId, groupStrategy) })));
  return { evaluatedAt, scenarioSetSha256: scenarioSha, auditabilityScenarioSetSha256: auditSha, analysisSha256: lifecycleRegressionHash({ evaluatedAt, scenarioSetSha256: scenarioSha, rows }),
    results: Object.fromEntries(["CONDITIONALLY_MATCHES", "CONDITIONALLY_DOES_NOT_MATCH", "NEEDS_INFORMATION"].map((status,i) =>
      [["conditionallyMatches", "conditionallyDoesNotMatch", "needsInformation"][i], rows.filter(r => r.analysis.status === status).length])),
    requirementOutcomes: counts(requirements), designOutcomes: counts(designs), conditionOutcomes: counts(conditions),
    offboardingOutcomes: counts(conditions.filter(c => ["ACCOUNT_DISABLE_AND_LOGIN_BLOCK", "APPLICATION_SESSION_INVALIDATION", "TOKEN_REVOCATION_OR_BOUNDED_EXPIRY"].includes(c.conditionId))),
    groupRemovalOutcomes: counts(conditions.filter(c => c.conditionId === "GROUP_REMOVAL_AND_ACCESS_RECHECK")), checkedConditionChecks: conditions.length,
    reasons: lifecycleV2Reasons.map(reasonCode => ({ reasonCode, checks: [...requirements, ...designs, ...conditions].filter(c => c.reasonCode === reasonCode).length })),
    scope: "SYNTHETIC_PROVISIONING_LIFECYCLE_V2_REGRESSION", policyVersion: "catalog-provisioning-lifecycle-regression-1", lifecyclePolicyVersion: "provisioning-lifecycle-design-2",
    definitionsSha256: lifecycleRegressionHash(["provisioning-lifecycle-design-2", Object.keys(lifecycleV2Patterns), scopes, lifecycleV2Ids, lifecycleV2OffboardingReferences, lifecycleV2Paths, lifecycleV2Deferred]),
    scenarioSetVersion: version, baseScenarioSetVersion: baseVersion, baseScenarioSetSha256: baseSha, auditabilityScenarioSetVersion: auditVersion, profileSchemaVersion: 6, profileSchemaSha256: schemaSha,
    analysisBasis: "SYNTHETIC_REQUIREMENTS_AND_UNVERIFIED_DESIGN_DECLARATIONS", declaredProfiles: 4, checkedCases: 2016, checkedRequirementChecks: 6048, checkedDesignChecks: 2016,
    checkedOffboardingChecks: 6048, checkedGroupRemovalChecks: 1152, requirementVariants: [...lifecycleRequirementVariants], declarationVariants: [...lifecycleDeclarationVariants],
    patternIds: Object.keys(lifecycleV2Patterns), groupStrategies: Object.keys(lifecycleV2Groups), conditionIds: [...lifecycleV2Ids], checkedPaths: [...lifecycleV2Paths], deferredBoundaries: [...lifecycleV2Deferred],
    ...Object.fromEntries(lifecycleRegressionFlags.map(flag => [flag, false])) };
}
export function validateLifecycleRegression(payload) { assert.deepEqual(payload, lifecycleRegressionFixture(), "Actual fixed-clock lifecycle summary must replay every bounded source-controlled input, scoped check, reason, count and digest independently."); }
