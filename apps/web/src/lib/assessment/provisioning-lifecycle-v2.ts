import type { DesignDeclaration } from "./architecture-prerequisites.ts";
import { provisioningRequirements, type LifecyclePattern, type ProvisioningRequirements } from "./provisioning-lifecycle.ts";

// Client-safe design library. These declarations never prove observed provider behavior.
const common = ["TENANT_AND_SUBJECT_CORRELATION", "ATTRIBUTE_OWNERSHIP_AND_MAPPING", "ACCOUNT_DISABLE_AND_LOGIN_BLOCK",
  "APPLICATION_SESSION_INVALIDATION", "TOKEN_REVOCATION_OR_BOUNDED_EXPIRY", "FAILURE_RECOVERY_AND_RECONCILIATION"] as const;
export const lifecycleV2PatternConditions = {
  SCIM_PUSH: [...common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS"],
  JIT_LOGIN: [...common, "JIT_TRUSTED_LOGIN_AND_LINKING"],
  SCIM_AND_JIT: [...common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS", "JIT_TRUSTED_LOGIN_AND_LINKING", "SCIM_JIT_COLLISION_POLICY"],
} as const;
const groupCommon = ["GROUP_SOURCE_AND_MEMBERSHIP_MAPPING", "GROUP_CHANGE_DELIVERY_AND_RECONCILIATION",
  "GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT", "GROUP_REMOVAL_AND_ACCESS_RECHECK"] as const;
export const lifecycleGroupConditions = { UNKNOWN: [], NONE: [], SCIM_GROUPS: [...groupCommon, "SCIM_GROUP_OPERATIONS"],
  APPLICATION_BRIDGE: [...groupCommon, "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY"] } as const;
export type LifecycleGroupStrategy = keyof typeof lifecycleGroupConditions;
export type LifecycleV2Condition = (typeof lifecycleV2PatternConditions)[LifecyclePattern][number] |
  (typeof lifecycleGroupConditions)[LifecycleGroupStrategy][number];
export type LifecycleV2Input = { expectedVersion: number; patternId: LifecyclePattern; groupStrategy: LifecycleGroupStrategy;
  declarations: Partial<Record<LifecycleV2Condition, DesignDeclaration>> };
export const lifecycleV2Descriptions: Record<LifecycleV2Condition, string> = {
  TENANT_AND_SUBJECT_CORRELATION: "Define tenant boundaries and stable subject correlation; an email match alone is not proof of identity.",
  ATTRIBUTE_OWNERSHIP_AND_MAPPING: "Define which source owns each attribute and how changes map to the application.",
  ACCOUNT_DISABLE_AND_LOGIN_BLOCK: "Define delivery of account disablement and prevention of new application logins, including users who never log in again; JIT alone is not an offboarding channel.",
  APPLICATION_SESSION_INVALIDATION: "Define how existing application sessions lose access after offboarding; directory disablement or IdP logout alone does not prove application session invalidation.",
  TOKEN_REVOCATION_OR_BOUNDED_EXPIRY: "Define revocation or resource-server enforcement and the accepted residual-access window for access and refresh tokens; revoking a refresh token does not by itself prove immediate rejection of every issued access token.",
  FAILURE_RECOVERY_AND_RECONCILIATION: "Define recovery, retries, duplicate handling and reconciliation when updates fail or arrive late.",
  SCIM_CLIENT_SERVER_DIRECTION: "Identify the SCIM client, server, tenant boundary and authorized provisioning credentials in the intended direction.",
  SCIM_USER_OPERATIONS: "Define and test intended User create, update and disable or delete operations; a SCIM label does not prove this operation set.",
  JIT_TRUSTED_LOGIN_AND_LINKING: "Define trusted login-time account creation, attribute mapping and safe linking to an existing subject.",
  SCIM_JIT_COLLISION_POLICY: "Define ownership and collision handling when SCIM and login-time creation act on the same account.",
  GROUP_SOURCE_AND_MEMBERSHIP_MAPPING: "Define authoritative group sources and tenant-scoped group/member identifiers, including nested-group policy.",
  GROUP_CHANGE_DELIVERY_AND_RECONCILIATION: "Define membership-change delivery independent of a user's next login, reconciliation and handling of missing or reordered changes.",
  GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT: "Define application-owned group-to-role mapping and authorization enforcement; received group names are not access grants by themselves.",
  GROUP_REMOVAL_AND_ACCESS_RECHECK: "Define how membership removal or group deletion removes mapped grants and invalidates cached authorization for existing sessions and tokens.",
  SCIM_GROUP_OPERATIONS: "Define and test Group create, membership update, removal and deletion in the intended SCIM direction; User support does not establish Group support.",
  APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY: "Define an authenticated tenant-scoped bridge, least-privilege writers, duplicate handling and idempotent application of membership changes.",
};
export const lifecycleGroupStrategies = [
  { groupStrategy: "UNKNOWN", displayName: "Group strategy not selected", advantages: [],
    tradeoffs: ["An unknown strategy is not proof of either group synchronization or its absence."], conditions: lifecycleGroupConditions.UNKNOWN, references: [] },
  { groupStrategy: "NONE", displayName: "No group synchronization", advantages: ["Avoids an additional group delivery integration."],
    tradeoffs: ["Cannot satisfy required group synchronization; application authorization still needs its own design."], conditions: lifecycleGroupConditions.NONE, references: [] },
  { groupStrategy: "SCIM_GROUPS", displayName: "SCIM Group resources", advantages: ["Plans group and membership delivery through the selected SCIM transport."],
    tradeoffs: ["Requires a SCIM user-lifecycle pattern in this preview; a User-only SCIM interface is insufficient.",
      "Group delivery does not define application roles, enforcement or revocation."], conditions: lifecycleGroupConditions.SCIM_GROUPS,
    references: ["https://www.rfc-editor.org/rfc/rfc7643.html#section-4.2", "https://www.rfc-editor.org/rfc/rfc7644.html"] },
  { groupStrategy: "APPLICATION_BRIDGE", displayName: "Application-owned group synchronization bridge",
    advantages: ["Separates application group delivery from the user provisioning mechanism."],
    tradeoffs: ["The application must implement and test authorized delivery, reconciliation and idempotency.",
      "A bridge does not supply required SCIM user provisioning or prove vendor interoperability."],
    conditions: lifecycleGroupConditions.APPLICATION_BRIDGE, references: [] },
] as const;
export const lifecycleOffboardingReferences = ["https://www.rfc-editor.org/rfc/rfc7643.html#section-4.1.1", "https://www.rfc-editor.org/rfc/rfc7009.html#section-3"];
const requirementKeys = ["scim", "justInTimeProvisioning", "groupSynchronization"] as const;
const falseFlags = ["configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified",
  "accessRevocationVerified", "writesPerformed", "publicationReady", "recommendationReady"];
const deferred = ["providerOperationsAndEntitlements", "observedProvisioningDelivery", "groupMembershipAndAuthorization", "sessionAndTokenRevocation", "reconciliationAndFailureRecovery"];
export const lifecycleV2ByteLimit = 32_768;
export class InvalidLifecycleV2Form extends Error { }
const invalid = () => new Error("Invalid group and offboarding design preview");
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid();
  return value as Record<string, unknown>;
}
function exact(value: unknown, keys: readonly string[]) {
  const raw = record(value);
  if (Object.keys(raw).length !== keys.length || keys.some(key => !Object.hasOwn(raw, key))) throw invalid();
  return raw;
}
function equal(actual: unknown, expected: unknown): void {
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual) || actual.length !== expected.length) throw invalid();
    expected.forEach((v, i) => equal(actual[i], v));
  } else if (expected && typeof expected === "object") {
    const raw = exact(actual, Object.keys(expected)); for (const [k, v] of Object.entries(expected)) equal(raw[k], v);
  } else if (actual !== expected) throw invalid();
}
function validInstant(value: unknown): boolean {
  if (typeof value !== "string") return false;
  const parts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d{1,9})?Z$/.exec(value);
  if (!parts) return false;
  const millis = Date.parse(`${parts[1]}Z`);
  return Number.isFinite(millis) && new Date(millis).toISOString() === `${parts[1]}.000Z`;
}
export function lifecycleV2Conditions(pattern: LifecyclePattern, groups: LifecycleGroupStrategy): readonly LifecycleV2Condition[] {
  return [...lifecycleV2PatternConditions[pattern], ...lifecycleGroupConditions[groups]];
}
export function parseLifecycleV2Form(params: URLSearchParams): LifecycleV2Input {
  const versions = params.getAll("expectedVersion"), patterns = params.getAll("patternId"), groups = params.getAll("groupStrategy");
  if (versions.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versions[0]) || !Number.isSafeInteger(Number(versions[0])) ||
      patterns.length !== 1 || !Object.hasOwn(lifecycleV2PatternConditions, patterns[0]) ||
      groups.length !== 1 || !Object.hasOwn(lifecycleGroupConditions, groups[0])) throw new InvalidLifecycleV2Form();
  const patternId = patterns[0] as LifecyclePattern, groupStrategy = groups[0] as LifecycleGroupStrategy;
  const ids: readonly string[] = lifecycleV2Conditions(patternId, groupStrategy), declarations: LifecycleV2Input["declarations"] = {};
  for (const [key, value] of params) {
    if (["expectedVersion", "patternId", "groupStrategy"].includes(key)) continue;
    if (!ids.includes(key) || params.getAll(key).length !== 1 || !["SATISFIED", "NOT_SATISFIED", "UNKNOWN"].includes(value)) throw new InvalidLifecycleV2Form();
    declarations[key as LifecycleV2Condition] = value as DesignDeclaration;
  }
  return { expectedVersion: Number(versions[0]), patternId, groupStrategy, declarations };
}
export function validateLifecycleV2Input(input: LifecycleV2Input): void {
  exact(input, ["expectedVersion", "patternId", "groupStrategy", "declarations"]); record(input.declarations);
  const params = new URLSearchParams({ expectedVersion: String(input.expectedVersion), patternId: input.patternId, groupStrategy: input.groupStrategy });
  for (const [key, value] of Object.entries(input.declarations)) params.append(key, value);
  equal(input, parseLifecycleV2Form(params));
}
type Outcome = "CONDITIONALLY_SATISFIED" | "CONDITIONALLY_NOT_SATISFIED" | "UNKNOWN" | "NOT_APPLIED";
function outcome(reason: string): Outcome {
  if (["REQUIRED_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT", "DECLARED_CONDITION_SATISFIED", "GROUP_TRANSPORT_PLANNED"].includes(reason)) return "CONDITIONALLY_SATISFIED";
  if (["REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED", "DECLARED_CONDITION_NOT_SATISFIED", "SCIM_GROUPS_REQUIRE_SCIM_PATTERN"].includes(reason)) return "CONDITIONALLY_NOT_SATISFIED";
  if (["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED", "NO_GROUP_TRANSPORT_PLANNED"].includes(reason)) return "NOT_APPLIED";
  return "UNKNOWN";
}
function expectedAnalysis(input: LifecycleV2Input, requirements: ProvisioningRequirements) {
  const requirementChecks = requirementKeys.map(key => {
    const criticality = requirements[key], planned = key === "scim" ? input.patternId !== "JIT_LOGIN" : key === "justInTimeProvisioning"
      ? input.patternId !== "SCIM_PUSH" : input.groupStrategy === "UNKNOWN" ? null : input.groupStrategy !== "NONE";
    const reasonCode = criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED"
      : criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT" : planned === null ? "GROUP_STRATEGY_UNKNOWN"
        : criticality === "REQUIRED" ? planned ? "REQUIRED_MECHANISM_PLANNED" : "REQUIRED_MECHANISM_ABSENT"
          : planned ? "FORBIDDEN_MECHANISM_PLANNED" : "FORBIDDEN_MECHANISM_ABSENT";
    return { profilePath: `provisioning.${key}`, criticality, outcome: outcome(reasonCode), reasonCode };
  });
  const reasonCode = input.groupStrategy === "UNKNOWN" ? "GROUP_STRATEGY_UNKNOWN" : input.groupStrategy === "NONE" ? "NO_GROUP_TRANSPORT_PLANNED"
    : input.groupStrategy === "SCIM_GROUPS" && input.patternId === "JIT_LOGIN" ? "SCIM_GROUPS_REQUIRE_SCIM_PATTERN" : "GROUP_TRANSPORT_PLANNED";
  const designChecks = [{ boundary: "groupTransport", outcome: outcome(reasonCode), reasonCode }];
  const conditionChecks = lifecycleV2Conditions(input.patternId, input.groupStrategy).map(conditionId => {
    const declared = input.declarations[conditionId] ?? "UNKNOWN";
    const reasonCode = declared === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : declared === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN";
    return { conditionId, outcome: outcome(reasonCode), reasonCode };
  });
  const all = [...requirementChecks, ...designChecks, ...conditionChecks];
  const status: "CONDITIONALLY_DOES_NOT_MATCH" | "NEEDS_INFORMATION" | "CONDITIONALLY_MATCHES" =
    all.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" : all.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES";
  return { patternId: input.patternId, groupStrategy: input.groupStrategy, requirements: { ...requirements }, declarations: { ...input.declarations }, requirementChecks, designChecks, conditionChecks, status };
}
export type LifecycleV2Analysis = ReturnType<typeof expectedAnalysis>;
export type LifecycleV2Preview = { assessmentVersion: number; analysis: LifecycleV2Analysis };
export function lifecycleV2Analysis(value: unknown, input: LifecycleV2Input, requirements: ProvisioningRequirements): LifecycleV2Analysis {
  validateLifecycleV2Input(input); provisioningRequirements({ provisioning: requirements });
  const expected = expectedAnalysis(input, requirements); equal(value, expected); return expected;
}
/** Bind exact saved requirements and request, then independently replay every conditional check. */
export function lifecycleV2PreviewFromCore(value: unknown, binding: { workspaceId: string; assessmentId: string; input: LifecycleV2Input; requirements: ProvisioningRequirements }): LifecycleV2Preview {
  const raw = exact(value, ["workspaceId", "assessmentId", "assessmentVersion", "evaluatedAt", "analysis", "policyVersion", "scope", "analysisBasis",
    "groupStrategies", "conditionDefinitions", "offboardingReferences", "checkedProfilePaths", "deferredBoundaries", ...falseFlags]);
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  if (!uuid.test(binding.workspaceId) || !uuid.test(binding.assessmentId) || raw.workspaceId !== binding.workspaceId || raw.assessmentId !== binding.assessmentId ||
      raw.assessmentVersion !== binding.input.expectedVersion || raw.policyVersion !== "provisioning-lifecycle-design-2" ||
      raw.scope !== "PROVISIONING_LIFECYCLE_DESIGN_PREVIEW" || raw.analysisBasis !== "SAVED_REQUIREMENTS_AND_UNVERIFIED_DESIGN_DECLARATIONS" ||
      falseFlags.some(flag => raw[flag] !== false) || !validInstant(raw.evaluatedAt)) throw invalid();
  equal(raw.groupStrategies, lifecycleGroupStrategies);
  equal(raw.conditionDefinitions, [...lifecycleV2PatternConditions.SCIM_AND_JIT, ...groupCommon, "SCIM_GROUP_OPERATIONS", "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY"].map(id =>
    ({ conditionId: id, description: lifecycleV2Descriptions[id as LifecycleV2Condition] })));
  equal(raw.offboardingReferences, lifecycleOffboardingReferences); equal(raw.checkedProfilePaths, requirementKeys.map(key => `provisioning.${key}`)); equal(raw.deferredBoundaries, deferred);
  return { assessmentVersion: binding.input.expectedVersion, analysis: lifecycleV2Analysis(raw.analysis, binding.input, binding.requirements) };
}
