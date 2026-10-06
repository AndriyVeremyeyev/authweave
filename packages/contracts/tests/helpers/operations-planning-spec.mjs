import assert from "node:assert/strict";

// Independent contract expectation from the saved owner inputs, not Core output.
export const operationsMetrics = ["MONTHLY_ACTIVE_USERS", "ENTERPRISE_SSO_CONNECTIONS", "MONTHLY_M2M_TOKEN_ISSUANCES", "PEAK_HUMAN_LOGINS_PER_SECOND"];
export const operationsCheckedPaths = ["operations.hosting", "operations.deploymentTarget", "operations.identityExpertise", "operations.budgetSensitivity",
  "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes"];
export const operationsShared = ["Own application integration, tenant/client configuration and application authorization.",
  "Assign lifecycle, incident response and evidence-review owners; document escalation and recovery responsibilities.",
  "Verify the operator contract, limits and responsibility split for the exact deployment."];
export const operationsDeferred = ["Exact provider, plan, region and application-to-identity deployment compatibility",
  "Observed operator capacity, responsibilities, support and recovery exercises", "Dated prices, billing-unit mapping, paid features and additional environments",
  "Infrastructure, support, migration and team operational costs", "Protocol, lifecycle, auditability, assurance and compliance verification", "Provider eligibility, scoring, ranking and final recommendation"];
export const operationsReferences = ["https://learn.microsoft.com/en-us/azure/security/fundamentals/shared-responsibility", "https://www.keycloak.org/server/configuration-production"];
const descriptions = {
  MANAGED_IDENTITY_SERVICE: {
    advantages: ["Can transfer operation of the identity platform to a service operator, subject to the exact contract."],
    tradeoffs: ["Service reliance, plan/region limits and export options need independent evaluation.", "Managed service does not remove application configuration and access-management responsibilities."],
    responsibilities: ["Review vendor-operated responsibilities and the customer's remaining configuration duties.", "Validate support, recovery, export and service commitments for the exact plan; no verified SLA is inferred."] },
  SELF_HOSTED_IDENTITY_SERVICE: {
    advantages: ["Can give your team more direct control over the identity components it operates."],
    tradeoffs: ["More platform operation and recovery work remains in the scope your team must plan.", "Self-hosting and zero recorded usage do not imply zero infrastructure or team costs."],
    responsibilities: ["Plan runtime/database/network/TLS hardening and security updates for components you operate.", "Plan and test monitoring, backups, recovery and availability for the identity deployment."] },
};
export function operationsPlanningExpectation(saved, evaluatedAt) {
  const source = saved.profile.operations;
  const inputs = Object.fromEntries(["hosting", "deploymentTarget", "identityExpertise", "budgetSensitivity"].map(key => [key, source[key]]));
  const planning = source.usagePlanning ?? { scopeDescription: "", assumptions: [], volumes: {} };
  const recordedMetrics = operationsMetrics.filter(metric => Object.hasOwn(planning.volumes, metric));
  const missing = [];
  // Match Java String.isBlank; NBSP and BOM are not whitespace in this saved-input policy.
  if (/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u.test(planning.scopeDescription)) missing.push("operations.usagePlanning.scopeDescription");
  for (const metric of operationsMetrics) if (!recordedMetrics.includes(metric)) missing.push(`operations.usagePlanning.volumes.${metric}`);
  if (!planning.assumptions.length && Object.values(planning.volumes).some(q => q.basis === "ASSUMED")) missing.push("operations.usagePlanning.assumptions");
  const missingPaths = [];
  for (const [key, unknown] of [["hosting", "UNKNOWN"], ["deploymentTarget", "UNDECIDED"], ["identityExpertise", "UNKNOWN"], ["budgetSensitivity", "UNKNOWN"]]) {
    if (inputs[key] === unknown) missingPaths.push(`operations.${key}`);
  }
  missingPaths.push(...missing);
  const options = Object.entries(descriptions).map(([optionId, text]) => {
    const managed = optionId === "MANAGED_IDENTITY_SERVICE";
    return { optionId, hostingAlignment: inputs.hosting === "UNKNOWN" ? "PREFERENCE_UNKNOWN" : inputs.hosting === "NO_PREFERENCE" ? "NO_PREFERENCE"
      : (inputs.hosting === "MANAGED") === managed ? "PREFERENCE_ALIGNED" : "PREFERENCE_DIFFERS",
    supportPlanning: inputs.identityExpertise === "UNKNOWN" ? "SUPPORT_CAPACITY_UNDEFINED" : inputs.identityExpertise !== "LIMITED" ? "RESPONSIBILITY_PLAN_NEEDED"
      : managed ? "INTEGRATION_SUPPORT_PLAN_NEEDED" : "OPERATOR_SUPPORT_PLAN_NEEDED",
    budgetPlanning: inputs.budgetSensitivity === "UNKNOWN" ? "BUDGET_SCOPE_UNDEFINED" : "COST_MODEL_NEEDED", ...structuredClone(text) };
  });
  return { workspaceId: saved.workspaceId, assessmentId: saved.id, assessmentVersion: saved.version, evaluatedAt, inputs,
    usageInputs: { status: missing.length ? "NEEDS_INFORMATION" : "INPUTS_RECORDED", recordedMetrics, missingPaths: missing }, options,
    policyVersion: "operations-planning-preflight-1", scope: "UNVERIFIED_OPERATIONS_PLANNING", analysisBasis: "SAVED_OWNER_INPUTS_AND_GENERIC_RESPONSIBILITIES",
    status: missingPaths.length ? "NEEDS_INFORMATION" : "INPUTS_RECORDED", missingPaths, checkedPaths: operationsCheckedPaths, sharedResponsibilities: operationsShared,
    deferredBoundaries: operationsDeferred, references: operationsReferences,
    hostingExplanation: "Hosting alignment is a preference comparison, not provider eligibility or a hard requirement.",
    deploymentExplanation: "The saved deployment target describes the assessed application, not a verified identity-service location or supported hosting combination.",
    budgetExplanation: "Budget sensitivity is a preference, not a spending cap. Recorded usage, including zero, cannot establish a free tier or total cost.",
    providerEligibilityEvaluated: false, deploymentCompatibilityVerified: false, operationalReadinessVerified: false, pricingEvaluated: false, costModelEvaluated: false,
    budgetFitVerified: false, configurationVerified: false, recommendationReady: false, publicationReady: false, writesPerformed: false };
}
export function validateOperationsPlanning(payload, saved, evaluatedAt) {
  assert.ok(saved, "An exact saved assessment binding is required.");
  assert.deepEqual(payload, operationsPlanningExpectation(saved, evaluatedAt), "Operations alternatives must replay saved preferences, usage and unverified boundaries exactly.");
}
