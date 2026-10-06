import { usageMetrics, usagePlanningValues, type UsageMetric, type UsagePlanningValues } from "./usage-planning.ts";

export const hostingPreferences = ["MANAGED", "SELF_HOSTED", "NO_PREFERENCE", "UNKNOWN"] as const;
export const deploymentTargets = ["AZURE", "AWS", "GOOGLE_CLOUD", "ON_PREMISES", "MULTI_CLOUD", "UNDECIDED"] as const;
export const identityExpertiseLevels = ["LIMITED", "MODERATE", "ADVANCED", "UNKNOWN"] as const;
export const budgetSensitivities = ["HIGH", "MODERATE", "LOW", "UNKNOWN"] as const;
export type OperationsInputs = {
  hosting: typeof hostingPreferences[number]; deploymentTarget: typeof deploymentTargets[number];
  identityExpertise: typeof identityExpertiseLevels[number]; budgetSensitivity: typeof budgetSensitivities[number];
};
export type OperationsPlanningValues = { inputs: OperationsInputs; usagePlanning: UsagePlanningValues };
export type OperationsMissingPath = `operations.${keyof OperationsInputs}` |
  "operations.usagePlanning.scopeDescription" | "operations.usagePlanning.assumptions" |
  `operations.usagePlanning.volumes.${UsageMetric}`;
type InputStatus = "NEEDS_INFORMATION" | "INPUTS_RECORDED";
export type OperationsOption = {
  optionId: "MANAGED_IDENTITY_SERVICE" | "SELF_HOSTED_IDENTITY_SERVICE";
  hostingAlignment: "PREFERENCE_ALIGNED" | "PREFERENCE_DIFFERS" | "NO_PREFERENCE" | "PREFERENCE_UNKNOWN";
  supportPlanning: "SUPPORT_CAPACITY_UNDEFINED" | "RESPONSIBILITY_PLAN_NEEDED" |
    "INTEGRATION_SUPPORT_PLAN_NEEDED" | "OPERATOR_SUPPORT_PLAN_NEEDED";
  budgetPlanning: "BUDGET_SCOPE_UNDEFINED" | "COST_MODEL_NEEDED";
  advantages: string[]; tradeoffs: string[]; responsibilities: string[];
};
export type OperationsPlanningPreview = {
  assessmentVersion: number; evaluatedAt: string; inputs: OperationsInputs; status: InputStatus;
  missingPaths: OperationsMissingPath[];
  usageInputs: { status: InputStatus; recordedMetrics: UsageMetric[]; missingPaths: OperationsMissingPath[] };
  options: OperationsOption[]; sharedResponsibilities: string[]; deferredBoundaries: string[]; references: string[];
};
export type OperationsPlanningBinding = {
  workspaceId: string; assessmentId: string; expectedVersion: number; values: OperationsPlanningValues;
};
export const operationsPlanningByteLimit = 32_768;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const checkedPaths = ["operations.hosting", "operations.deploymentTarget", "operations.identityExpertise", "operations.budgetSensitivity",
  "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes"];
const sharedResponsibilities = ["Own application integration, tenant/client configuration and application authorization.",
  "Assign lifecycle, incident response and evidence-review owners; document escalation and recovery responsibilities.",
  "Verify the operator contract, limits and responsibility split for the exact deployment."];
const deferredBoundaries = ["Exact provider, plan, region and application-to-identity deployment compatibility",
  "Observed operator capacity, responsibilities, support and recovery exercises", "Dated prices, billing-unit mapping, paid features and additional environments",
  "Infrastructure, support, migration and team operational costs", "Protocol, lifecycle, auditability, assurance and compliance verification", "Provider eligibility, scoring, ranking and final recommendation"];
const references = ["https://learn.microsoft.com/en-us/azure/security/fundamentals/shared-responsibility", "https://www.keycloak.org/server/configuration-production"];
const optionDescriptions = {
  MANAGED_IDENTITY_SERVICE: {
    advantages: ["Can transfer operation of the identity platform to a service operator, subject to the exact contract."],
    tradeoffs: ["Service reliance, plan/region limits and export options need independent evaluation.", "Managed service does not remove application configuration and access-management responsibilities."],
    responsibilities: ["Review vendor-operated responsibilities and the customer's remaining configuration duties.", "Validate support, recovery, export and service commitments for the exact plan; no verified SLA is inferred."] },
  SELF_HOSTED_IDENTITY_SERVICE: {
    advantages: ["Can give your team more direct control over the identity components it operates."],
    tradeoffs: ["More platform operation and recovery work remains in the scope your team must plan.", "Self-hosting and zero recorded usage do not imply zero infrastructure or team costs."],
    responsibilities: ["Plan runtime/database/network/TLS hardening and security updates for components you operate.", "Plan and test monitoring, backups, recovery and availability for the identity deployment."] },
};

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function choice(value: unknown, choices: readonly string[]): boolean { return typeof value === "string" && choices.includes(value); }
function invalid(): never { throw new Error("Invalid operations planning preview"); }

/** Read saved inputs only. No defaults for missing fields or invented usage quantities. */
export function operationsPlanningValues(profile: Record<string, unknown>): OperationsPlanningValues | null {
  const ops = object(profile.operations), planning = usagePlanningValues(profile);
  if (!ops || !planning || Object.keys(ops).length !== 5 ||
      !choice(ops.hosting, hostingPreferences) || !choice(ops.deploymentTarget, deploymentTargets) ||
      !choice(ops.identityExpertise, identityExpertiseLevels) || !choice(ops.budgetSensitivity, budgetSensitivities)) return null;
  return { inputs: { hosting: ops.hosting, deploymentTarget: ops.deploymentTarget,
    identityExpertise: ops.identityExpertise, budgetSensitivity: ops.budgetSensitivity } as OperationsInputs, usagePlanning: planning };
}

export function operationsPlanningBinding(workspaceId: string, assessmentId: string, expectedVersion: number,
  values: OperationsPlanningValues): OperationsPlanningBinding {
  const raw = object(values), inputs = object(raw?.inputs);
  if (!uuid.test(workspaceId) || !uuid.test(assessmentId) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0 ||
      !raw || Object.keys(raw).length !== 2 || !inputs || Object.keys(inputs).length !== 4) invalid();
  const checked = operationsPlanningValues({ operations: { ...inputs, usagePlanning: raw.usagePlanning } });
  if (!checked) invalid();
  return { workspaceId, assessmentId, expectedVersion, values: checked };
}

// Independent consumer policy: never import Core output or contract-test helpers as expectations.
function expected(binding: OperationsPlanningBinding, evaluatedAt: string) {
  const { inputs, usagePlanning: planning } = binding.values;
  const recordedMetrics = usageMetrics.filter(m => Object.hasOwn(planning.volumes, m.key)).map(m => m.key);
  const usageMissing: OperationsMissingPath[] = [];
  // Existing Core policy uses Java String.isBlank, which does not include NBSP or BOM.
  if (/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u.test(planning.scopeDescription)) usageMissing.push("operations.usagePlanning.scopeDescription");
  for (const metric of usageMetrics) if (!recordedMetrics.includes(metric.key)) usageMissing.push(`operations.usagePlanning.volumes.${metric.key}`);
  if (!planning.assumptions.length && Object.values(planning.volumes).some(q => q?.basis === "ASSUMED")) usageMissing.push("operations.usagePlanning.assumptions");
  const missingPaths: OperationsMissingPath[] = [];
  for (const [key, unknown] of [["hosting", "UNKNOWN"], ["deploymentTarget", "UNDECIDED"], ["identityExpertise", "UNKNOWN"], ["budgetSensitivity", "UNKNOWN"]] as const) {
    if (inputs[key] === unknown) missingPaths.push(`operations.${key}`);
  }
  missingPaths.push(...usageMissing);
  const options: OperationsOption[] = (Object.keys(optionDescriptions) as OperationsOption["optionId"][]).map(optionId => {
    const managed = optionId === "MANAGED_IDENTITY_SERVICE";
    return { optionId, hostingAlignment: inputs.hosting === "UNKNOWN" ? "PREFERENCE_UNKNOWN" : inputs.hosting === "NO_PREFERENCE" ? "NO_PREFERENCE"
      : (inputs.hosting === "MANAGED") === managed ? "PREFERENCE_ALIGNED" : "PREFERENCE_DIFFERS",
    supportPlanning: inputs.identityExpertise === "UNKNOWN" ? "SUPPORT_CAPACITY_UNDEFINED" : inputs.identityExpertise !== "LIMITED" ? "RESPONSIBILITY_PLAN_NEEDED"
      : managed ? "INTEGRATION_SUPPORT_PLAN_NEEDED" : "OPERATOR_SUPPORT_PLAN_NEEDED",
    budgetPlanning: inputs.budgetSensitivity === "UNKNOWN" ? "BUDGET_SCOPE_UNDEFINED" : "COST_MODEL_NEEDED", ...structuredClone(optionDescriptions[optionId]) };
  });
  return { workspaceId: binding.workspaceId, assessmentId: binding.assessmentId, assessmentVersion: binding.expectedVersion, evaluatedAt,
    inputs, usageInputs: { status: (usageMissing.length ? "NEEDS_INFORMATION" : "INPUTS_RECORDED") as InputStatus, recordedMetrics, missingPaths: usageMissing }, options,
    policyVersion: "operations-planning-preflight-1", scope: "UNVERIFIED_OPERATIONS_PLANNING", analysisBasis: "SAVED_OWNER_INPUTS_AND_GENERIC_RESPONSIBILITIES",
    status: (missingPaths.length ? "NEEDS_INFORMATION" : "INPUTS_RECORDED") as InputStatus, missingPaths, checkedPaths, sharedResponsibilities, deferredBoundaries, references,
    hostingExplanation: "Hosting alignment is a preference comparison, not provider eligibility or a hard requirement.",
    deploymentExplanation: "The saved deployment target describes the assessed application, not a verified identity-service location or supported hosting combination.",
    budgetExplanation: "Budget sensitivity is a preference, not a spending cap. Recorded usage, including zero, cannot establish a free tier or total cost.",
    providerEligibilityEvaluated: false, deploymentCompatibilityVerified: false, operationalReadinessVerified: false, pricingEvaluated: false, costModelEvaluated: false,
    budgetFitVerified: false, configurationVerified: false, recommendationReady: false, publicationReady: false, writesPerformed: false };
}

function equalShape(actual: unknown, wanted: unknown): boolean {
  if (Array.isArray(wanted)) return Array.isArray(actual) && actual.length === wanted.length && wanted.every((v, i) => equalShape(actual[i], v));
  const target = object(wanted);
  if (target) {
    const source = object(actual), keys = Object.keys(target);
    return source !== null && Object.keys(source).length === keys.length && keys.every(k => Object.hasOwn(source, k) && equalShape(source[k], target[k]));
  }
  return actual === wanted;
}

/** Reject semantic, scope, narrative and authority substitutions; return a client-safe projection. */
export function operationsPlanningFromCore(value: unknown, input: OperationsPlanningBinding): OperationsPlanningPreview {
  const binding = operationsPlanningBinding(input.workspaceId, input.assessmentId, input.expectedVersion, input.values);
  const raw = object(value), at = raw?.evaluatedAt;
  if (typeof at !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(at)) invalid();
  const second = at.replace(/\.\d+Z$/, "Z"), millis = Date.parse(second);
  if (!Number.isFinite(millis) || new Date(millis).toISOString() !== second.replace("Z", ".000Z")) invalid();
  const replay = expected(binding, at);
  if (!equalShape(value, replay)) invalid();
  return structuredClone({ assessmentVersion: replay.assessmentVersion, evaluatedAt: at, inputs: replay.inputs, status: replay.status,
    missingPaths: replay.missingPaths, usageInputs: replay.usageInputs, options: replay.options,
    sharedResponsibilities: replay.sharedResponsibilities, deferredBoundaries: replay.deferredBoundaries, references: replay.references });
}
