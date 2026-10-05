import { lifecycleConditions, lifecycleDescriptions, lifecyclePatterns, type LifecycleInput,
  type ProvisioningRequirements } from "../../src/lib/assessment/provisioning-lifecycle.ts";

export const lifecycleWorkspaceId = "70000000-0000-4000-8000-000000000001";
export const lifecycleAssessmentId = "80000000-0000-4000-8000-000000000001";
export const lifecycleInput: LifecycleInput = { expectedVersion: 2, patternId: "SCIM_PUSH", declarations: {} };
export const lifecycleRequirements: ProvisioningRequirements = { scim: "REQUIRED", justInTimeProvisioning: "NOT_REQUIRED", groupSynchronization: "NOT_REQUIRED" };

// Synthetic reply builder, not production evaluation or proof of provider support.
export function lifecycleFixture(input = lifecycleInput, requirements = lifecycleRequirements) {
  const requirementChecks = Object.entries(requirements).map(([key, criticality]) => {
    const planned = key === "scim" ? input.patternId !== "JIT_LOGIN" : input.patternId !== "SCIM_PUSH";
    const reasonCode = criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT" : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED" : criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" :
      key === "groupSynchronization" ? criticality === "REQUIRED" ? "GROUP_LIFECYCLE_UNASSESSED" : "GROUP_PROHIBITION_UNASSESSED" :
        criticality === "REQUIRED" ? planned ? "REQUIRED_MECHANISM_PLANNED" : "REQUIRED_MECHANISM_ABSENT" : planned ? "FORBIDDEN_MECHANISM_PLANNED" : "FORBIDDEN_MECHANISM_ABSENT";
    const outcome = ["REQUIRED_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT"].includes(reasonCode) ? "CONDITIONALLY_SATISFIED" :
      ["REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED"].includes(reasonCode) ? "CONDITIONALLY_NOT_SATISFIED" :
        ["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED"].includes(reasonCode) ? "NOT_APPLIED" : "UNKNOWN";
    return { profilePath: `provisioning.${key}`, criticality, outcome, reasonCode };
  });
  const conditionChecks = lifecycleConditions[input.patternId].map(conditionId => ({ conditionId,
    outcome: input.declarations[conditionId] === "SATISFIED" ? "CONDITIONALLY_SATISFIED" : input.declarations[conditionId] === "NOT_SATISFIED" ? "CONDITIONALLY_NOT_SATISFIED" : "UNKNOWN",
    reasonCode: input.declarations[conditionId] === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : input.declarations[conditionId] === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN" }));
  const checks = [...requirementChecks, ...conditionChecks];
  return { workspaceId: lifecycleWorkspaceId, assessmentId: lifecycleAssessmentId, assessmentVersion: input.expectedVersion,
    evaluatedAt: "2026-10-05T12:00:00Z", analysis: { patternId: input.patternId, requirements: { ...requirements }, declarations: { ...input.declarations }, requirementChecks, conditionChecks,
      status: checks.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" : checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES" },
    policyVersion: "provisioning-lifecycle-design-1", scope: "PROVISIONING_LIFECYCLE_DESIGN_PREVIEW", analysisBasis: "SAVED_REQUIREMENTS_AND_UNVERIFIED_DESIGN_DECLARATIONS",
    patterns: structuredClone(lifecyclePatterns), conditionDefinitions: lifecycleConditions.SCIM_AND_JIT.map(conditionId => ({ conditionId, description: lifecycleDescriptions[conditionId] })),
    checkedProfilePaths: ["provisioning.scim", "provisioning.justInTimeProvisioning", "provisioning.groupSynchronization"],
    deferredBoundaries: ["providerOperationsAndEntitlements", "observedProvisioningDelivery", "groupMembershipAndAuthorization", "sessionAndTokenRevocation", "reconciliationAndFailureRecovery"],
    configurationVerified: false, providerCompatibilityVerified: false, lifecycleVerified: false, groupSynchronizationVerified: false,
    accessRevocationVerified: false, writesPerformed: false, publicationReady: false, recommendationReady: false };
}
