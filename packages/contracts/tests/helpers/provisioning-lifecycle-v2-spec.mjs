// Independent contract expectation; no production evaluator or BFF consumer imports.
export const lifecycleV2Common = ["TENANT_AND_SUBJECT_CORRELATION", "ATTRIBUTE_OWNERSHIP_AND_MAPPING", "ACCOUNT_DISABLE_AND_LOGIN_BLOCK",
  "APPLICATION_SESSION_INVALIDATION", "TOKEN_REVOCATION_OR_BOUNDED_EXPIRY", "FAILURE_RECOVERY_AND_RECONCILIATION"];
export const lifecycleV2Patterns = { SCIM_PUSH: [...lifecycleV2Common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS"],
  JIT_LOGIN: [...lifecycleV2Common, "JIT_TRUSTED_LOGIN_AND_LINKING"],
  SCIM_AND_JIT: [...lifecycleV2Common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS", "JIT_TRUSTED_LOGIN_AND_LINKING", "SCIM_JIT_COLLISION_POLICY"] };
export const lifecycleV2GroupCommon = ["GROUP_SOURCE_AND_MEMBERSHIP_MAPPING", "GROUP_CHANGE_DELIVERY_AND_RECONCILIATION", "GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT", "GROUP_REMOVAL_AND_ACCESS_RECHECK"];
export const lifecycleV2Groups = { UNKNOWN: [], NONE: [], SCIM_GROUPS: [...lifecycleV2GroupCommon, "SCIM_GROUP_OPERATIONS"],
  APPLICATION_BRIDGE: [...lifecycleV2GroupCommon, "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY"] };
export const lifecycleV2Paths = ["provisioning.scim", "provisioning.justInTimeProvisioning", "provisioning.groupSynchronization"];
export const lifecycleV2Deferred = ["providerOperationsAndEntitlements", "observedProvisioningDelivery", "groupMembershipAndAuthorization", "sessionAndTokenRevocation", "reconciliationAndFailureRecovery"];
export const lifecycleV2OffboardingReferences = ["https://www.rfc-editor.org/rfc/rfc7643.html#section-4.1.1", "https://www.rfc-editor.org/rfc/rfc7009.html#section-3"];
export const lifecycleV2Reasons = ["REQUIRED_MECHANISM_PLANNED", "REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT",
  "REQUIREMENT_UNKNOWN", "PREFERENCE_NOT_SCORED", "NO_REQUIREMENT", "GROUP_STRATEGY_UNKNOWN", "GROUP_TRANSPORT_PLANNED", "NO_GROUP_TRANSPORT_PLANNED", "SCIM_GROUPS_REQUIRE_SCIM_PATTERN",
  "DECLARED_CONDITION_SATISFIED", "DECLARED_CONDITION_NOT_SATISFIED", "CONDITION_UNKNOWN"];
export const lifecycleV2Ids = [...lifecycleV2Patterns.SCIM_AND_JIT, ...lifecycleV2GroupCommon, "SCIM_GROUP_OPERATIONS", "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY"];
export const lifecycleV2Scope = (pattern, group) => [...lifecycleV2Patterns[pattern], ...lifecycleV2Groups[group]];
const satisfied = ["REQUIRED_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT", "DECLARED_CONDITION_SATISFIED", "GROUP_TRANSPORT_PLANNED"];
const failed = ["REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED", "DECLARED_CONDITION_NOT_SATISFIED", "SCIM_GROUPS_REQUIRE_SCIM_PATTERN"];
const outcome = reason => satisfied.includes(reason) ? "CONDITIONALLY_SATISFIED" : failed.includes(reason) ? "CONDITIONALLY_NOT_SATISFIED"
  : ["PREFERENCE_NOT_SCORED", "NO_REQUIREMENT", "NO_GROUP_TRANSPORT_PLANNED"].includes(reason) ? "NOT_APPLIED" : "UNKNOWN";
export function lifecycleV2Expectation(requirements, patternId, groupStrategy, declarations) {
  const requirementChecks = ["scim", "justInTimeProvisioning", "groupSynchronization"].map(key => {
    const criticality = requirements[key], planned = key === "scim" ? patternId !== "JIT_LOGIN" : key === "justInTimeProvisioning" ? patternId !== "SCIM_PUSH"
      : groupStrategy === "UNKNOWN" ? null : groupStrategy !== "NONE";
    const reasonCode = criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED" : criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT"
      : planned === null ? "GROUP_STRATEGY_UNKNOWN" : criticality === "REQUIRED" ? planned ? "REQUIRED_MECHANISM_PLANNED" : "REQUIRED_MECHANISM_ABSENT"
        : planned ? "FORBIDDEN_MECHANISM_PLANNED" : "FORBIDDEN_MECHANISM_ABSENT";
    return { profilePath: `provisioning.${key}`, criticality, outcome: outcome(reasonCode), reasonCode };
  });
  const reasonCode = groupStrategy === "UNKNOWN" ? "GROUP_STRATEGY_UNKNOWN" : groupStrategy === "NONE" ? "NO_GROUP_TRANSPORT_PLANNED"
    : groupStrategy === "SCIM_GROUPS" && patternId === "JIT_LOGIN" ? "SCIM_GROUPS_REQUIRE_SCIM_PATTERN" : "GROUP_TRANSPORT_PLANNED";
  const designChecks = [{ boundary: "groupTransport", outcome: outcome(reasonCode), reasonCode }];
  const conditionChecks = lifecycleV2Scope(patternId, groupStrategy).map(conditionId => {
    const value = declarations[conditionId] ?? "UNKNOWN";
    const reasonCode = value === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : value === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN";
    return { conditionId, outcome: outcome(reasonCode), reasonCode };
  });
  const all = [...requirementChecks, ...designChecks, ...conditionChecks].map(c => c.outcome);
  return { patternId, groupStrategy, requirements, declarations, requirementChecks, designChecks, conditionChecks,
    status: all.includes("CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" : all.includes("UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES" };
}
