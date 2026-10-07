import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { operationsRegressionHash as hash } from "./operations-planning-regression-spec.mjs";
import { assurancePlanningExpectation, assuranceQuestions, assuranceCheckedPaths, assuranceDeferred } from "./assurance-compliance-planning-spec.mjs";

// Independent frozen-source and wire expectations; no production evaluator or summary is imported.
export const assuranceVariants = ["BASE_PROFILE", "UNRECORDED_SCOPE", "HUMAN_BASELINE", "HUMAN_ELEVATED", "HUMAN_HIGH_UNRECORDED_CONTROLS",
  "HUMAN_HIGH_FORBIDDEN_CONTROL", "MACHINE_ONLY", "MIXED_PARTIAL_SCOPE", "HUMAN_NONE_IDENTIFIED"];
export const assuranceRegressionFlags = ["candidateChangesEvaluated", "coverageComplete", "assuranceVerified", "complianceVerified", "legalApplicabilityDetermined",
  "providerEligibilityEvaluated", "configurationVerified", "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed"];
const root = new URL("../../../../services/core-api/src/main/resources/catalog/", import.meta.url);
const version = "catalog-assurance-compliance-scenarios-1", baseVersion = "catalog-scoped-profile-scenarios-1", auditVersion = "catalog-scoped-auditability-scenarios-1";
const schemaSha = "3d384daf2d98851ebcbe00f90a9c6dabeedbae8361f8a41f58d00b9742c95fbb";
const workspaceId = "60000000-0000-4000-8000-000000000001";
const id = index => `80000000-0000-4000-8000-${(index + 1).toString(16).padStart(12, "0")}`;
const controls = value => Object.fromEntries(["multiFactorAuthentication", "phishingResistance", "nonExportableKeys", "stepUpAuthentication"].map(key => [key, value]));
function input(profile) {
  const s = profile.security;
  return { clients: [...profile.application.clients].sort(), populations: [...profile.audience.populations].sort(), assuranceExpectation: s.assurance,
    controls: { multiFactorAuthentication: s.multiFactorAuthentication, ...s.authenticationControls }, complianceScopeStatus: s.complianceScopeStatus, complianceTargets: [...s.complianceTargets].sort() };
}
function overlay(variant) {
  const defaults = { clients: ["BROWSER"], populations: ["EMPLOYEES"], assuranceExpectation: "HIGH", controls: controls("NOT_REQUIRED"), complianceScopeStatus: "NONE_IDENTIFIED", complianceTargets: [] };
  switch (variant) {
    case "UNRECORDED_SCOPE": return { ...defaults, clients: [], populations: [], assuranceExpectation: "UNKNOWN", controls: controls("UNKNOWN"), complianceScopeStatus: "UNKNOWN" };
    case "HUMAN_BASELINE": return { ...defaults, assuranceExpectation: "BASELINE", complianceScopeStatus: "TARGETS_IDENTIFIED", complianceTargets: ["GDPR"] };
    case "HUMAN_ELEVATED": return { ...defaults, assuranceExpectation: "ELEVATED", controls: controls("PREFERRED"), complianceScopeStatus: "TARGETS_IDENTIFIED", complianceTargets: ["FEDRAMP", "GDPR", "HIPAA", "ISO_27001", "OTHER", "SOC_2"] };
    case "HUMAN_HIGH_UNRECORDED_CONTROLS": return { ...defaults, controls: controls("UNKNOWN"), complianceScopeStatus: "TARGETS_IDENTIFIED", complianceTargets: ["OTHER"] };
    case "HUMAN_HIGH_FORBIDDEN_CONTROL": return { ...defaults, controls: { multiFactorAuthentication: "REQUIRED", phishingResistance: "FORBIDDEN", nonExportableKeys: "NOT_REQUIRED", stepUpAuthentication: "PREFERRED" }, complianceScopeStatus: "TARGETS_IDENTIFIED", complianceTargets: ["SOC_2"] };
    case "MACHINE_ONLY": return { ...defaults, clients: ["MACHINE_TO_MACHINE"], populations: [], assuranceExpectation: "ELEVATED", controls: controls("REQUIRED") };
    case "MIXED_PARTIAL_SCOPE": return { ...defaults, clients: ["BROWSER", "MACHINE_TO_MACHINE"], populations: ["CONTRACTORS"], controls: { multiFactorAuthentication: "PREFERRED", phishingResistance: "REQUIRED", nonExportableKeys: "NOT_REQUIRED", stepUpAuthentication: "UNKNOWN" }, complianceScopeStatus: "UNKNOWN", complianceTargets: ["OTHER", "SOC_2"] };
    case "HUMAN_NONE_IDENTIFIED": return defaults;
    default: throw new Error("Use the exact frozen base input, not inferred defaults");
  }
}
export function assuranceRegressionInputs() {
  const sources = JSON.parse(readFileSync(new URL("scoped-impact-scenarios.v1.json", root), "utf8"));
  const supplement = JSON.parse(readFileSync(new URL("scoped-auditability-scenarios.v1.json", root), "utf8"));
  const baseSha = hash(sources); assert.equal(baseSha, "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1");
  assert.equal(supplement.baseScenarioSetSha256, baseSha); assert.equal(supplement.baseScenarioSetVersion, baseVersion); assert.equal(sources.length, 4);
  const profiles = sources.toSorted((a,b) => a.id < b.id ? -1 : 1).map(source => {
    assert.equal(source.profileSchemaVersion, 5); assert.equal(Object.hasOwn(source.profile.security, "auditabilityRequirements"), false);
    const requirements = supplement.scenarios.find(s => s.scenarioId === source.id); assert.ok(requirements);
    const profile = structuredClone(source.profile);
    profile.security.auditabilityRequirements = { selectedCriteria: requirements.selectedCriteria, minimumRetentionDays: requirements.minimumRetentionDays };
    return { scenarioId: source.id, criticality: profile.security.auditability, requirements: profile.security.auditabilityRequirements, profileSha256: hash(profile), profile };
  });
  const auditDefinitions = profiles.map(({ profile, ...definition }) => { void profile; return definition; });
  const auditSha = hash([auditVersion, 6, schemaSha, baseSha, supplement, auditDefinitions]);
  const cases = profiles.flatMap(source => assuranceVariants.map(variant => {
    const inputs = variant === "BASE_PROFILE" ? input(source.profile) : overlay(variant), profile = structuredClone(source.profile);
    if (variant !== "BASE_PROFILE") {
      profile.application.clients = [...inputs.clients]; profile.audience.populations = [...inputs.populations];
      const { multiFactorAuthentication, ...authenticationControls } = inputs.controls;
      Object.assign(profile.security, { assurance: inputs.assuranceExpectation, multiFactorAuthentication, authenticationControls,
        complianceScopeStatus: inputs.complianceScopeStatus, complianceTargets: [...inputs.complianceTargets] });
    }
    return { definition: { scenarioId: source.scenarioId, variant, sourceProfileSha256: source.profileSha256, profileSha256: hash(profile), inputs }, profile };
  }));
  assert.equal(cases.length, 36);
  const definitions = cases.map(c => c.definition);
  return { cases, definitions, baseSha, auditSha, scenarioSha: hash([version, auditVersion, auditSha, schemaSha, baseSha, definitions]) };
}
export function assuranceRegressionFixture(evaluatedAt = "2026-09-12T12:00:00Z") {
  const { cases, baseSha, auditSha, scenarioSha } = assuranceRegressionInputs();
  const rows = cases.map(({ definition: input, profile }, index) => ({ input, analysis: assurancePlanningExpectation({ workspaceId, id: id(index), version: 0, profile }, evaluatedAt) }));
  const counts = items => ({ inputClarificationNeeded: items.filter(i => i.status === "INPUT_CLARIFICATION_NEEDED").length, evidenceNeeded: items.filter(i => i.status === "EVIDENCE_NEEDED").length, notApplied: items.filter(i => i.status === "NOT_APPLIED").length });
  const unknown = { application: { clients: [] }, audience: { populations: [] }, security: { assurance: "UNKNOWN", multiFactorAuthentication: "UNKNOWN", authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" }, complianceScopeStatus: "UNKNOWN", complianceTargets: [] } };
  return { evaluatedAt, scenarioSetSha256: scenarioSha, auditabilityScenarioSetSha256: auditSha,
    analysisSha256: hash({ evaluatedAt, scenarioSetSha256: scenarioSha, rows }), assurance: counts(rows.flatMap(r => r.analysis.assuranceItems)), compliance: counts(rows.flatMap(r => r.analysis.complianceItems)),
    humanScopes: { machineOnly: rows.filter(r => r.analysis.humanScope === "MACHINE_ONLY").length, humanScopeRecorded: rows.filter(r => r.analysis.humanScope === "HUMAN_SCOPE_RECORDED").length, scopeUnresolved: rows.filter(r => r.analysis.humanScope === "SCOPE_UNRESOLVED").length },
    checkedComplianceItems: rows.reduce((sum,r) => sum + r.analysis.complianceItems.length, 0), complianceScopeNotApplied: rows.filter(r => r.analysis.complianceScopeCheck.outcome === "NOT_APPLIED").length,
    scope: "SYNTHETIC_ASSURANCE_COMPLIANCE_REGRESSION", policyVersion: "catalog-assurance-compliance-regression-1", planningPolicyVersion: "assurance-compliance-planning-1",
    definitionsSha256: hash(assurancePlanningExpectation({ workspaceId, id: id(0), version: 0, profile: unknown }, "1970-01-01T00:00:00Z")),
    scenarioSetVersion: version, baseScenarioSetVersion: baseVersion, baseScenarioSetSha256: baseSha, auditabilityScenarioSetVersion: auditVersion, profileSchemaVersion: 6, profileSchemaSha256: schemaSha,
    analysisBasis: "SYNTHETIC_INPUTS_AND_UNVERIFIED_INVESTIGATION_PROMPTS", declaredProfiles: 4, checkedCases: 36, checkedAssuranceItems: 252, needsInformationCases: 36,
    variants: [...assuranceVariants], itemIds: Object.keys(assuranceQuestions), checkedPaths: [...assuranceCheckedPaths], deferredBoundaries: [...assuranceDeferred], ...Object.fromEntries(assuranceRegressionFlags.map(flag => [flag, false])) };
}
export function validateAssuranceRegression(payload) { assert.deepEqual(payload, assuranceRegressionFixture(), "Fixed-clock investigation counts and digests must independently replay complete frozen-source inputs, not assert source verification."); }
