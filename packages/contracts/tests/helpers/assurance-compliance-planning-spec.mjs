import assert from "node:assert/strict";

// Independent saved-input expectations, not production Java output or provider evidence.
export const assuranceQuestions = {
  ASSURANCE_OBJECTIVE: "Define the assurance objective, covered identities and evaluation criteria; BASELINE, ELEVATED and HIGH have no automatic standards mapping.",
  HUMAN_AUTHENTICATION_SCOPE: "Confirm the human clients and populations in scope; declared scope does not verify their deployed flows.",
  AUTHENTICATION_CONTROLS: "Clarify MFA and each independent control, then investigate scoped enforcement and weaker fallback paths; labels do not set these requirements.",
  ENROLLMENT_AND_RECOVERY: "Investigate enrollment, account recovery and authenticator replacement for the selected human flows.",
  SESSIONS_AND_REAUTHENTICATION: "Investigate session handling, reauthentication and sensitive-action wiring for the selected human flows.",
  WORKLOAD_IDENTITY: "Investigate workload credential issuance, storage, rotation and revocation when machine clients are in scope.",
  FEDERATION_AND_TRUST_BOUNDARIES: "Identify federation parties, trust boundaries and application validation responsibilities; no deployed protocol behavior is checked here.",
};
export const assuranceFlags = ["assuranceVerified", "complianceVerified", "legalApplicabilityDetermined", "providerEligibilityEvaluated", "configurationVerified", "recommendationReady", "publicationReady", "writesPerformed"];
export const assuranceCheckedPaths = ["application.clients", "audience.populations", "security.assurance", "security.multiFactorAuthentication",
  "security.authenticationControls.phishingResistance", "security.authenticationControls.nonExportableKeys", "security.authenticationControls.stepUpAuthentication",
  "security.complianceScopeStatus", "security.complianceTargets"];
export const complianceQuestions = ["Define the concrete obligation or criterion, its applicability basis and the responsible reviewer; a target label is not a determination.",
  "Identify the exact application and product/service scope, plan, region, version, responsibility split and exclusions to investigate.",
  "Collect dated, scoped source material with its review status and limitations, and separately investigate deployed controls; no evidence is verified here."];
export const assuranceDeferred = ["Formal assurance framework and level mapping", "Observed authentication, enrollment, recovery, session and workload flows",
  "Exact provider and service evidence verification", "Legal applicability and target-specific compliance assessment", "Candidate-change coverage and publication authority"];
const scopeExplanations = {
  COMPLIANCE_SCOPE_INCONSISTENT: "Resolve the inconsistency between scope status and recorded targets before evaluating requirements.",
  COMPLIANCE_SCOPE_UNKNOWN: "The requirements scope has not been recorded. Existing target labels are preserved but do not establish scope or compliance. Clarify the requirements with the assessment owner.",
  NO_COMPLIANCE_TARGETS_IDENTIFIED: "The owner recorded no identified compliance requirements for this assessment. No target check is applied; this is not a finding of legal exemption or compliance.",
  COMPLIANCE_TARGETS_NOT_EVALUATED: "Target labels are recorded, but applicable obligations, product/service scope and supporting evidence have not been evaluated. OTHER needs a concrete definition. No candidate is verified or rejected from labels alone.",
};
export function assurancePlanningExpectation(saved, at) {
  assert.ok(saved, "Exact saved assessment binding required.");
  const p = saved.profile, s = p.security;
  const controls = { multiFactorAuthentication: s.multiFactorAuthentication,
    ...(s.authenticationControls ?? { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" }) };
  const inputs = { clients: [...p.application.clients].sort(), populations: [...p.audience.populations].sort(),
    assuranceExpectation: s.assurance, controls, complianceScopeStatus: s.complianceScopeStatus ?? "UNKNOWN", complianceTargets: [...s.complianceTargets].sort() };
  const machineOnly = inputs.clients.length === 1 && inputs.clients[0] === "MACHINE_TO_MACHINE";
  const humanScope = machineOnly ? "MACHINE_ONLY" : inputs.clients.length && inputs.populations.length ? "HUMAN_SCOPE_RECORDED" : "SCOPE_UNRESOLVED";
  const controlsUnclear = Object.values(controls).some(v => v === "UNKNOWN" || v === "FORBIDDEN");
  const humanRows = machineOnly ? ["NOT_APPLIED", "HUMAN_FLOW_NOT_SELECTED"] : ["EVIDENCE_NEEDED", "FLOW_EVIDENCE_NOT_EVALUATED"];
  const rows = [
    ["INPUT_CLARIFICATION_NEEDED", s.assurance === "UNKNOWN" ? "EXPECTATION_UNRECORDED" : "LABEL_NEEDS_DEFINITION"],
    machineOnly ? humanRows : humanScope === "SCOPE_UNRESOLVED" ? ["INPUT_CLARIFICATION_NEEDED", "HUMAN_SCOPE_UNRESOLVED"] : ["EVIDENCE_NEEDED", "DECLARED_SCOPE_NOT_VERIFIED"],
    machineOnly ? humanRows : controlsUnclear ? ["INPUT_CLARIFICATION_NEEDED", "CONTROL_INTENT_UNRESOLVED"] : ["EVIDENCE_NEEDED", "CONTROLS_NOT_VERIFIED"],
    humanRows, humanRows,
    inputs.clients.includes("MACHINE_TO_MACHINE") ? ["EVIDENCE_NEEDED", "FLOW_EVIDENCE_NOT_EVALUATED"] : !inputs.clients.length
      ? ["INPUT_CLARIFICATION_NEEDED", "CLIENT_SCOPE_UNRESOLVED"] : ["NOT_APPLIED", "WORKLOAD_FLOW_NOT_SELECTED"],
    ["EVIDENCE_NEEDED", "FLOW_EVIDENCE_NOT_EVALUATED"],
  ];
  const assuranceItems = Object.entries(assuranceQuestions).map(([itemId, question], i) => ({ itemId, status: rows[i][0], reasonCode: rows[i][1], question }));
  const scope = inputs.complianceScopeStatus, targets = inputs.complianceTargets;
  const inconsistent = scope === "NONE_IDENTIFIED" && targets.length > 0 || scope === "TARGETS_IDENTIFIED" && !targets.length;
  const reasonCode = inconsistent ? "COMPLIANCE_SCOPE_INCONSISTENT" : scope === "UNKNOWN" ? "COMPLIANCE_SCOPE_UNKNOWN"
    : scope === "NONE_IDENTIFIED" ? "NO_COMPLIANCE_TARGETS_IDENTIFIED" : "COMPLIANCE_TARGETS_NOT_EVALUATED";
  const complianceScopeCheck = { profilePath: "security.complianceScopeStatus", scopeStatus: scope, recordedTargets: targets,
    outcome: !inconsistent && scope === "NONE_IDENTIFIED" ? "NOT_APPLIED" : "UNKNOWN", reasonCode, explanation: scopeExplanations[reasonCode], verificationPerformed: false };
  const complianceItems = targets.map(target => ({ target,
    status: scope !== "TARGETS_IDENTIFIED" || target === "OTHER" ? "INPUT_CLARIFICATION_NEEDED" : "EVIDENCE_NEEDED",
    reasonCode: scope !== "TARGETS_IDENTIFIED" ? "TARGET_SCOPE_NOT_ESTABLISHED" : target === "OTHER" ? "OTHER_TARGET_NEEDS_DEFINITION" : "TARGET_EVIDENCE_NOT_EVALUATED" }));
  return { workspaceId: saved.workspaceId, assessmentId: saved.id, assessmentVersion: saved.version, evaluatedAt: at,
    inputs, complianceScopeCheck, assuranceItems, complianceItems, humanScope,
    policyVersion: "assurance-compliance-planning-1", scope: "UNVERIFIED_ASSURANCE_COMPLIANCE_PLANNING", analysisBasis: "SAVED_INPUTS_AND_GENERIC_INVESTIGATION_PROMPTS",
    status: "NEEDS_INFORMATION", checkedPaths: assuranceCheckedPaths, complianceQuestions, deferredBoundaries: assuranceDeferred,
    ...Object.fromEntries(assuranceFlags.map(flag => [flag, false])) };
}
export function validateAssurancePlanning(payload, saved, at) {
  assert.deepEqual(payload, assurancePlanningExpectation(saved, at), "Investigation inventory must replay exact saved inputs; labels and recorded scope cannot establish verification.");
}
