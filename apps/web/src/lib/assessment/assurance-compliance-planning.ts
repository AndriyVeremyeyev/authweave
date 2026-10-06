import { criticalities, type Criticality } from "./capabilities.ts";
import { clientTypes, populations, complianceScopeStatuses, complianceTargets } from "./evaluation-context.ts";

export const assuranceExpectations = ["BASELINE", "ELEVATED", "HIGH", "UNKNOWN"] as const;
export type AssurancePlanningValues = {
  clients: typeof clientTypes[number][]; populations: typeof populations[number][];
  assuranceExpectation: typeof assuranceExpectations[number];
  controls: { multiFactorAuthentication: Criticality; phishingResistance: Criticality; nonExportableKeys: Criticality; stepUpAuthentication: Criticality };
  complianceScopeStatus: typeof complianceScopeStatuses[number]; complianceTargets: typeof complianceTargets[number][];
};
type ItemStatus = "INPUT_CLARIFICATION_NEEDED" | "EVIDENCE_NEEDED" | "NOT_APPLIED";
type HumanScope = "MACHINE_ONLY" | "HUMAN_SCOPE_RECORDED" | "SCOPE_UNRESOLVED";
export const assuranceItemDefinitions = {
  ASSURANCE_OBJECTIVE: { title: "Define the assurance objective", question: "Define the assurance objective, covered identities and evaluation criteria; BASELINE, ELEVATED and HIGH have no automatic standards mapping." },
  HUMAN_AUTHENTICATION_SCOPE: { title: "Human clients and populations", question: "Confirm the human clients and populations in scope; declared scope does not verify their deployed flows." },
  AUTHENTICATION_CONTROLS: { title: "Independent authentication controls", question: "Clarify MFA and each independent control, then investigate scoped enforcement and weaker fallback paths; labels do not set these requirements." },
  ENROLLMENT_AND_RECOVERY: { title: "Enrollment and recovery", question: "Investigate enrollment, account recovery and authenticator replacement for the selected human flows." },
  SESSIONS_AND_REAUTHENTICATION: { title: "Sessions and reauthentication", question: "Investigate session handling, reauthentication and sensitive-action wiring for the selected human flows." },
  WORKLOAD_IDENTITY: { title: "Workload identity", question: "Investigate workload credential issuance, storage, rotation and revocation when machine clients are in scope." },
  FEDERATION_AND_TRUST_BOUNDARIES: { title: "Federation and trust boundaries", question: "Identify federation parties, trust boundaries and application validation responsibilities; no deployed protocol behavior is checked here." },
};
type ItemId = keyof typeof assuranceItemDefinitions;
export type AssurancePlanningPreview = {
  assessmentVersion: number; evaluatedAt: string; status: "NEEDS_INFORMATION"; inputs: AssurancePlanningValues; humanScope: HumanScope;
  complianceScopeCheck: { outcome: "UNKNOWN" | "NOT_APPLIED"; reasonCode: string; explanation: string };
  assuranceItems: { itemId: ItemId; status: ItemStatus; reasonCode: string; question: string }[];
  complianceItems: { target: typeof complianceTargets[number]; status: Exclude<ItemStatus, "NOT_APPLIED">; reasonCode: string }[];
  complianceQuestions: string[]; deferredBoundaries: string[];
};
export type AssurancePlanningBinding = { workspaceId: string; assessmentId: string; expectedVersion: number; values: AssurancePlanningValues };
export const assurancePlanningByteLimit = 32_768;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const checkedPaths = ["application.clients", "audience.populations", "security.assurance", "security.multiFactorAuthentication",
  "security.authenticationControls.phishingResistance", "security.authenticationControls.nonExportableKeys", "security.authenticationControls.stepUpAuthentication",
  "security.complianceScopeStatus", "security.complianceTargets"];
const complianceQuestions = ["Define the concrete obligation or criterion, its applicability basis and the responsible reviewer; a target label is not a determination.",
  "Identify the exact application and product/service scope, plan, region, version, responsibility split and exclusions to investigate.",
  "Collect dated, scoped source material with its review status and limitations, and separately investigate deployed controls; no evidence is verified here."];
const deferredBoundaries = ["Formal assurance framework and level mapping", "Observed authentication, enrollment, recovery, session and workload flows",
  "Exact provider and service evidence verification", "Legal applicability and target-specific compliance assessment", "Candidate-change coverage and publication authority"];
const scopeExplanations = {
  COMPLIANCE_SCOPE_INCONSISTENT: "Resolve the inconsistency between scope status and recorded targets before evaluating requirements.",
  COMPLIANCE_SCOPE_UNKNOWN: "The requirements scope has not been recorded. Existing target labels are preserved but do not establish scope or compliance. Clarify the requirements with the assessment owner.",
  NO_COMPLIANCE_TARGETS_IDENTIFIED: "The owner recorded no identified compliance requirements for this assessment. No target check is applied; this is not a finding of legal exemption or compliance.",
  COMPLIANCE_TARGETS_NOT_EVALUATED: "Target labels are recorded, but applicable obligations, product/service scope and supporting evidence have not been evaluated. OTHER needs a concrete definition. No candidate is verified or rejected from labels alone.",
};
function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function choice<T extends string>(value: unknown, choices: readonly T[]): value is T { return typeof value === "string" && choices.includes(value as T); }
function selected<T extends string>(value: unknown, choices: readonly T[]): T[] | null {
  return Array.isArray(value) && value.every(v => choice(v, choices)) && new Set(value).size === value.length ? [...value].sort() as T[] : null;
}
function parseInputs(value: unknown): AssurancePlanningValues | null {
  const raw = object(value), controls = object(raw?.controls);
  const clients = selected(raw?.clients, clientTypes), users = selected(raw?.populations, populations), targets = selected(raw?.complianceTargets, complianceTargets);
  if (!raw || Object.keys(raw).length !== 6 || !controls || Object.keys(controls).length !== 4 || !clients || !users || !targets ||
      !choice(raw.assuranceExpectation, assuranceExpectations) || !choice(raw.complianceScopeStatus, complianceScopeStatuses) ||
      !choice(controls.multiFactorAuthentication, criticalities) || !choice(controls.phishingResistance, criticalities) ||
      !choice(controls.nonExportableKeys, criticalities) || !choice(controls.stepUpAuthentication, criticalities)) return null;
  return { clients, populations: users, assuranceExpectation: raw.assuranceExpectation, complianceScopeStatus: raw.complianceScopeStatus, complianceTargets: targets,
    controls: { multiFactorAuthentication: controls.multiFactorAuthentication, phishingResistance: controls.phishingResistance, nonExportableKeys: controls.nonExportableKeys, stepUpAuthentication: controls.stepUpAuthentication } };
}
/** The personal page reads projected v6 inputs; absent or malformed fields are not invented UNKNOWNs. */
export function assurancePlanningValues(profile: Record<string, unknown>): AssurancePlanningValues | null {
  const app = object(profile.application), audience = object(profile.audience), security = object(profile.security), controls = object(security?.authenticationControls);
  if (!app || !audience || !security || !controls || Object.keys(controls).length !== 3) return null;
  return parseInputs({ clients: app.clients, populations: audience.populations, assuranceExpectation: security.assurance,
    controls: { multiFactorAuthentication: security.multiFactorAuthentication, ...controls }, complianceScopeStatus: security.complianceScopeStatus, complianceTargets: security.complianceTargets });
}
function invalid(): never { throw new Error("Invalid assurance/compliance planning preview"); }
export function assurancePlanningBinding(workspaceId: string, assessmentId: string, expectedVersion: number, values: AssurancePlanningValues): AssurancePlanningBinding {
  const checked = parseInputs(values);
  if (!uuid.test(workspaceId) || !uuid.test(assessmentId) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0 || !checked) invalid();
  return { workspaceId, assessmentId, expectedVersion, values: checked };
}
// Independent consumer policy. Neither Core output nor contract-test helpers supply expectations.
function expected(binding: AssurancePlanningBinding, at: string) {
  const input = binding.values, machineOnly = input.clients.length === 1 && input.clients[0] === "MACHINE_TO_MACHINE";
  const humanScope: HumanScope = machineOnly ? "MACHINE_ONLY" : input.clients.length && input.populations.length ? "HUMAN_SCOPE_RECORDED" : "SCOPE_UNRESOLVED";
  const unclear = Object.values(input.controls).some(v => v === "UNKNOWN" || v === "FORBIDDEN");
  const assuranceItems: AssurancePlanningPreview["assuranceItems"] = (Object.keys(assuranceItemDefinitions) as ItemId[]).map(itemId => {
    let status: ItemStatus = "EVIDENCE_NEEDED", reasonCode = "FLOW_EVIDENCE_NOT_EVALUATED";
    if (itemId === "ASSURANCE_OBJECTIVE") { status = "INPUT_CLARIFICATION_NEEDED"; reasonCode = input.assuranceExpectation === "UNKNOWN" ? "EXPECTATION_UNRECORDED" : "LABEL_NEEDS_DEFINITION"; }
    else if (["HUMAN_AUTHENTICATION_SCOPE", "AUTHENTICATION_CONTROLS", "ENROLLMENT_AND_RECOVERY", "SESSIONS_AND_REAUTHENTICATION"].includes(itemId) && machineOnly) {
      status = "NOT_APPLIED"; reasonCode = "HUMAN_FLOW_NOT_SELECTED";
    } else if (itemId === "HUMAN_AUTHENTICATION_SCOPE") {
      status = humanScope === "SCOPE_UNRESOLVED" ? "INPUT_CLARIFICATION_NEEDED" : "EVIDENCE_NEEDED";
      reasonCode = humanScope === "SCOPE_UNRESOLVED" ? "HUMAN_SCOPE_UNRESOLVED" : "DECLARED_SCOPE_NOT_VERIFIED";
    } else if (itemId === "AUTHENTICATION_CONTROLS") { status = unclear ? "INPUT_CLARIFICATION_NEEDED" : "EVIDENCE_NEEDED"; reasonCode = unclear ? "CONTROL_INTENT_UNRESOLVED" : "CONTROLS_NOT_VERIFIED"; }
    else if (itemId === "WORKLOAD_IDENTITY" && !input.clients.includes("MACHINE_TO_MACHINE")) {
      status = input.clients.length ? "NOT_APPLIED" : "INPUT_CLARIFICATION_NEEDED";
      reasonCode = input.clients.length ? "WORKLOAD_FLOW_NOT_SELECTED" : "CLIENT_SCOPE_UNRESOLVED";
    }
    return { itemId, status, reasonCode, question: assuranceItemDefinitions[itemId].question };
  });
  const scope = input.complianceScopeStatus, targets = input.complianceTargets;
  const inconsistent = scope === "NONE_IDENTIFIED" && targets.length > 0 || scope === "TARGETS_IDENTIFIED" && !targets.length;
  const scopeReason = inconsistent ? "COMPLIANCE_SCOPE_INCONSISTENT" : scope === "UNKNOWN" ? "COMPLIANCE_SCOPE_UNKNOWN"
    : scope === "NONE_IDENTIFIED" ? "NO_COMPLIANCE_TARGETS_IDENTIFIED" : "COMPLIANCE_TARGETS_NOT_EVALUATED";
  const complianceScopeCheck = { profilePath: "security.complianceScopeStatus", scopeStatus: scope, recordedTargets: targets,
    outcome: (!inconsistent && scope === "NONE_IDENTIFIED" ? "NOT_APPLIED" : "UNKNOWN") as "NOT_APPLIED" | "UNKNOWN",
    reasonCode: scopeReason, explanation: scopeExplanations[scopeReason], verificationPerformed: false };
  const complianceItems: AssurancePlanningPreview["complianceItems"] = targets.map(target => ({ target,
    status: scope !== "TARGETS_IDENTIFIED" || target === "OTHER" ? "INPUT_CLARIFICATION_NEEDED" : "EVIDENCE_NEEDED",
    reasonCode: scope !== "TARGETS_IDENTIFIED" ? "TARGET_SCOPE_NOT_ESTABLISHED" : target === "OTHER" ? "OTHER_TARGET_NEEDS_DEFINITION" : "TARGET_EVIDENCE_NOT_EVALUATED" }));
  return { workspaceId: binding.workspaceId, assessmentId: binding.assessmentId, assessmentVersion: binding.expectedVersion, evaluatedAt: at,
    inputs: input, complianceScopeCheck, assuranceItems, complianceItems, humanScope,
    policyVersion: "assurance-compliance-planning-1", scope: "UNVERIFIED_ASSURANCE_COMPLIANCE_PLANNING", analysisBasis: "SAVED_INPUTS_AND_GENERIC_INVESTIGATION_PROMPTS",
    status: "NEEDS_INFORMATION" as const, checkedPaths, complianceQuestions, deferredBoundaries,
    assuranceVerified: false, complianceVerified: false, legalApplicabilityDetermined: false, providerEligibilityEvaluated: false,
    configurationVerified: false, recommendationReady: false, publicationReady: false, writesPerformed: false };
}
function equalShape(actual: unknown, wanted: unknown): boolean {
  if (Array.isArray(wanted)) return Array.isArray(actual) && actual.length === wanted.length && wanted.every((v, i) => equalShape(actual[i], v));
  const target = object(wanted);
  if (target) { const source = object(actual), keys = Object.keys(target); return source !== null && Object.keys(source).length === keys.length && keys.every(k => Object.hasOwn(source, k) && equalShape(source[k], target[k])); }
  return actual === wanted;
}
/** Strict saved-input replay, then a client-safe projection without workspace identity or verification flags. */
export function assurancePlanningFromCore(value: unknown, input: AssurancePlanningBinding): AssurancePlanningPreview {
  const binding = assurancePlanningBinding(input.workspaceId, input.assessmentId, input.expectedVersion, input.values), raw = object(value), at = raw?.evaluatedAt;
  if (typeof at !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(at)) invalid();
  const second = at.replace(/\.\d+Z$/, "Z"), millis = Date.parse(second);
  if (!Number.isFinite(millis) || new Date(millis).toISOString() !== second.replace("Z", ".000Z")) invalid();
  const replay = expected(binding, at);
  if (!equalShape(value, replay)) invalid();
  return structuredClone({ assessmentVersion: replay.assessmentVersion, evaluatedAt: at, status: replay.status, inputs: replay.inputs, humanScope: replay.humanScope,
    complianceScopeCheck: { outcome: replay.complianceScopeCheck.outcome, reasonCode: replay.complianceScopeCheck.reasonCode, explanation: replay.complianceScopeCheck.explanation },
    assuranceItems: replay.assuranceItems, complianceItems: replay.complianceItems, complianceQuestions: replay.complianceQuestions, deferredBoundaries: replay.deferredBoundaries });
}
