import { lifecycleV2Conditions, lifecycleV2Descriptions, lifecycleV2PatternConditions, lifecycleGroupConditions,
  lifecycleGroupStrategies, lifecycleOffboardingReferences, type LifecycleV2Input } from "../../src/lib/assessment/provisioning-lifecycle-v2.ts";
import { lifecycleWorkspaceId, lifecycleAssessmentId, lifecycleRequirements } from "./provisioning-lifecycle.mts";
export { lifecycleWorkspaceId, lifecycleAssessmentId, lifecycleRequirements };
export const lifecycleV2Input: LifecycleV2Input = { expectedVersion: 2, patternId: "SCIM_PUSH", groupStrategy: "UNKNOWN", declarations: {} };

// Independent synthetic evaluation. Actual Core HTTP responses separately guard library drift.
export function lifecycleV2Fixture(input = lifecycleV2Input, requirements = lifecycleRequirements) {
  const outcome = (reason: string) => ["REQUIRED_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT", "DECLARED_CONDITION_SATISFIED", "GROUP_TRANSPORT_PLANNED"].includes(reason)
    ? "CONDITIONALLY_SATISFIED" : ["REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED", "DECLARED_CONDITION_NOT_SATISFIED", "SCIM_GROUPS_REQUIRE_SCIM_PATTERN"].includes(reason)
      ? "CONDITIONALLY_NOT_SATISFIED" : ["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED", "NO_GROUP_TRANSPORT_PLANNED"].includes(reason) ? "NOT_APPLIED" : "UNKNOWN";
  const requirementChecks = Object.entries(requirements).map(([key, criticality]) => {
    const planned = key === "scim" ? input.patternId !== "JIT_LOGIN" : key === "justInTimeProvisioning" ? input.patternId !== "SCIM_PUSH"
      : input.groupStrategy === "UNKNOWN" ? undefined : input.groupStrategy !== "NONE";
    const reasonCode = criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT"
      : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED" : planned === undefined ? "GROUP_STRATEGY_UNKNOWN"
        : criticality === "REQUIRED" ? planned ? "REQUIRED_MECHANISM_PLANNED" : "REQUIRED_MECHANISM_ABSENT"
          : planned ? "FORBIDDEN_MECHANISM_PLANNED" : "FORBIDDEN_MECHANISM_ABSENT";
    return { profilePath: `provisioning.${key}`, criticality, outcome: outcome(reasonCode), reasonCode };
  });
  const reason = input.groupStrategy === "UNKNOWN" ? "GROUP_STRATEGY_UNKNOWN" : input.groupStrategy === "NONE" ? "NO_GROUP_TRANSPORT_PLANNED"
    : input.groupStrategy === "SCIM_GROUPS" && input.patternId === "JIT_LOGIN" ? "SCIM_GROUPS_REQUIRE_SCIM_PATTERN" : "GROUP_TRANSPORT_PLANNED";
  const designChecks = [{ boundary: "groupTransport", outcome: outcome(reason), reasonCode: reason }];
  const conditionChecks = lifecycleV2Conditions(input.patternId, input.groupStrategy).map(conditionId => {
    const declaration = input.declarations[conditionId];
    const reasonCode = declaration === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : declaration === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN";
    return { conditionId, outcome: outcome(reasonCode), reasonCode };
  });
  const checks = [...requirementChecks, ...designChecks, ...conditionChecks];
  return { workspaceId: lifecycleWorkspaceId, assessmentId: lifecycleAssessmentId, assessmentVersion: input.expectedVersion,
    evaluatedAt: "2026-10-05T12:00:00Z", analysis: { patternId: input.patternId, groupStrategy: input.groupStrategy,
      requirements: { ...requirements }, declarations: { ...input.declarations }, requirementChecks, designChecks, conditionChecks,
      status: checks.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" : checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES" },
    policyVersion: "provisioning-lifecycle-design-2", scope: "PROVISIONING_LIFECYCLE_DESIGN_PREVIEW", analysisBasis: "SAVED_REQUIREMENTS_AND_UNVERIFIED_DESIGN_DECLARATIONS",
    groupStrategies: structuredClone(lifecycleGroupStrategies),
    conditionDefinitions: [...lifecycleV2PatternConditions.SCIM_AND_JIT, ...lifecycleGroupConditions.SCIM_GROUPS, "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY" as const]
      .map(conditionId => ({ conditionId, description: lifecycleV2Descriptions[conditionId] })),
    offboardingReferences: [...lifecycleOffboardingReferences],
    checkedProfilePaths: ["provisioning.scim", "provisioning.justInTimeProvisioning", "provisioning.groupSynchronization"],
    deferredBoundaries: ["providerOperationsAndEntitlements", "observedProvisioningDelivery", "groupMembershipAndAuthorization", "sessionAndTokenRevocation", "reconciliationAndFailureRecovery"],
    configurationVerified: false, providerCompatibilityVerified: false, lifecycleVerified: false, groupSynchronizationVerified: false,
    accessRevocationVerified: false, writesPerformed: false, publicationReady: false, recommendationReady: false };
}
