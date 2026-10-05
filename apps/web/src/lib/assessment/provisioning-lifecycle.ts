import { criticalities, type Criticality } from "./capabilities.ts";
import type { DesignDeclaration } from "./architecture-prerequisites.ts";

// Client-safe educational copy. No provider facts, observed settings or saved design choice.
const common = ["TENANT_AND_SUBJECT_CORRELATION", "ATTRIBUTE_OWNERSHIP_AND_MAPPING",
  "OFFBOARDING_AND_ACCESS_REVOCATION", "FAILURE_RECOVERY_AND_RECONCILIATION"] as const;
export const lifecycleConditions = {
  SCIM_PUSH: [...common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS"],
  JIT_LOGIN: [...common, "JIT_TRUSTED_LOGIN_AND_LINKING"],
  SCIM_AND_JIT: [...common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS",
    "JIT_TRUSTED_LOGIN_AND_LINKING", "SCIM_JIT_COLLISION_POLICY"],
} as const;
export type LifecyclePattern = keyof typeof lifecycleConditions;
export type LifecycleCondition = (typeof lifecycleConditions)[LifecyclePattern][number];
export type LifecycleInput = { expectedVersion: number; patternId: LifecyclePattern;
  declarations: Partial<Record<LifecycleCondition, DesignDeclaration>> };
export type ProvisioningRequirements = { scim: Criticality; justInTimeProvisioning: Criticality; groupSynchronization: Criticality };
const requirementKeys = ["scim", "justInTimeProvisioning", "groupSynchronization"] as const;
export const lifecycleDescriptions: Record<LifecycleCondition, string> = {
  TENANT_AND_SUBJECT_CORRELATION: "Define tenant boundaries and stable subject correlation; an email match alone is not proof of identity.",
  ATTRIBUTE_OWNERSHIP_AND_MAPPING: "Define which source owns each attribute and how changes map to the application.",
  OFFBOARDING_AND_ACCESS_REVOCATION: "Design account offboarding and removal of application access, including existing sessions and tokens; a directory update alone is not proof of revocation.",
  FAILURE_RECOVERY_AND_RECONCILIATION: "Define recovery, retries, duplicate handling and reconciliation when updates fail or arrive late.",
  SCIM_CLIENT_SERVER_DIRECTION: "Identify the SCIM client, server, tenant boundary and authorized provisioning credentials in the intended direction.",
  SCIM_USER_OPERATIONS: "Define and test the intended User create, update and disable or delete operations; a SCIM label does not prove this operation set.",
  JIT_TRUSTED_LOGIN_AND_LINKING: "Define trusted login-time account creation, attribute mapping and safe linking to an existing subject.",
  SCIM_JIT_COLLISION_POLICY: "Define ownership and collision handling when SCIM and login-time creation act on the same account.",
};
const scimReferences = ["https://www.rfc-editor.org/rfc/rfc7644.html", "https://www.rfc-editor.org/rfc/rfc7643.html"];
const jitReference = "https://zitadel.com/docs/guides/integrate/identity-providers/introduction";
export const lifecyclePatterns = [
  { patternId: "SCIM_PUSH", displayName: "SCIM provisioning", scimPlanned: true, jitPlanned: false,
    advantages: ["Lifecycle changes can be delivered independently of an interactive user login."],
    tradeoffs: ["The intended SCIM direction, operations and recovery behavior need explicit implementation and testing.",
      "User provisioning does not establish group-to-role mapping or application session revocation."],
    conditions: lifecycleConditions.SCIM_PUSH, references: scimReferences },
  { patternId: "JIT_LOGIN", displayName: "Login-time JIT provisioning", scimPlanned: false, jitPlanned: true,
    advantages: ["An account can be created or updated as part of a trusted interactive login."],
    tradeoffs: ["Login-time creation alone does not supply SCIM or out-of-band offboarding.",
      "Users who do not log in need a separate update and offboarding path."],
    conditions: lifecycleConditions.JIT_LOGIN, references: [jitReference] },
  { patternId: "SCIM_AND_JIT", displayName: "SCIM with login-time JIT", scimPlanned: true, jitPlanned: true,
    advantages: ["Separates out-of-band lifecycle delivery from login-time account onboarding."],
    tradeoffs: ["Two writers require explicit correlation, attribute ownership and collision policy.",
      "Combining mechanisms does not prove provider interoperability or access revocation."],
    conditions: lifecycleConditions.SCIM_AND_JIT, references: [...scimReferences, jitReference] },
] as const;
const falseFlags = ["configurationVerified", "providerCompatibilityVerified", "lifecycleVerified",
  "groupSynchronizationVerified", "accessRevocationVerified", "writesPerformed", "publicationReady", "recommendationReady"];
const deferred = ["providerOperationsAndEntitlements", "observedProvisioningDelivery", "groupMembershipAndAuthorization",
  "sessionAndTokenRevocation", "reconciliationAndFailureRecovery"];
export const lifecycleByteLimit = 32_768;
export class InvalidLifecycleForm extends Error { }
const invalid = () => new Error("Invalid provisioning design preview");
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid();
  return value as Record<string, unknown>;
}
function exact(value: unknown, keys: readonly string[]) {
  const raw = record(value);
  if (Object.keys(raw).length !== keys.length || keys.some(key => !Object.hasOwn(raw, key))) throw invalid();
  return raw;
}
// Order matters for arrays, not JSON object properties. Also rejects extra fields recursively.
function equal(actual: unknown, expected: unknown): void {
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual) || actual.length !== expected.length) throw invalid();
    expected.forEach((value, index) => equal(actual[index], value));
  } else if (expected && typeof expected === "object") {
    const raw = exact(actual, Object.keys(expected));
    for (const [key, value] of Object.entries(expected)) equal(raw[key], value);
  } else if (actual !== expected) throw invalid();
}
function validInstant(value: unknown): boolean {
  if (typeof value !== "string") return false;
  const parts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d{1,9})?Z$/.exec(value);
  if (!parts) return false;
  const millis = Date.parse(`${parts[1]}Z`);
  return Number.isFinite(millis) && new Date(millis).toISOString() === `${parts[1]}.000Z`;
}
export function provisioningRequirements(profile: Record<string, unknown>): ProvisioningRequirements {
  const raw = exact(profile.provisioning, requirementKeys);
  if (requirementKeys.some(key => !(criticalities as readonly unknown[]).includes(raw[key]))) throw invalid();
  return Object.fromEntries(requirementKeys.map(key => [key, raw[key]])) as ProvisioningRequirements;
}
export function parseLifecycleForm(params: URLSearchParams): LifecycleInput {
  const versions = params.getAll("expectedVersion"), patterns = params.getAll("patternId");
  if (versions.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versions[0]) || !Number.isSafeInteger(Number(versions[0])) ||
      patterns.length !== 1 || !Object.hasOwn(lifecycleConditions, patterns[0])) throw new InvalidLifecycleForm();
  const patternId = patterns[0] as LifecyclePattern, ids: readonly string[] = lifecycleConditions[patternId];
  const declarations: LifecycleInput["declarations"] = {};
  for (const [key, value] of params) {
    if (key === "expectedVersion" || key === "patternId") continue;
    if (!ids.includes(key) || params.getAll(key).length !== 1 || !["SATISFIED", "NOT_SATISFIED", "UNKNOWN"].includes(value)) {
      throw new InvalidLifecycleForm();
    }
    declarations[key as LifecycleCondition] = value as DesignDeclaration;
  }
  return { expectedVersion: Number(versions[0]), patternId, declarations };
}
type Outcome = "CONDITIONALLY_SATISFIED" | "CONDITIONALLY_NOT_SATISFIED" | "UNKNOWN" | "NOT_APPLIED";
function outcome(reason: string): Outcome {
  if (["REQUIRED_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT", "DECLARED_CONDITION_SATISFIED"].includes(reason)) return "CONDITIONALLY_SATISFIED";
  if (["REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED", "DECLARED_CONDITION_NOT_SATISFIED"].includes(reason)) return "CONDITIONALLY_NOT_SATISFIED";
  if (["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED"].includes(reason)) return "NOT_APPLIED";
  return "UNKNOWN";
}
function expectedAnalysis(input: LifecycleInput, requirements: ProvisioningRequirements) {
  const requirementChecks = requirementKeys.map(key => {
    const criticality = requirements[key], planned = key === "scim" ? input.patternId !== "JIT_LOGIN" : input.patternId !== "SCIM_PUSH";
    const reasonCode = criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED" :
      criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT" : key === "groupSynchronization" ?
        criticality === "REQUIRED" ? "GROUP_LIFECYCLE_UNASSESSED" : "GROUP_PROHIBITION_UNASSESSED" :
        criticality === "REQUIRED" ? planned ? "REQUIRED_MECHANISM_PLANNED" : "REQUIRED_MECHANISM_ABSENT" :
          planned ? "FORBIDDEN_MECHANISM_PLANNED" : "FORBIDDEN_MECHANISM_ABSENT";
    return { profilePath: `provisioning.${key}`, criticality, outcome: outcome(reasonCode), reasonCode };
  });
  const conditionChecks = lifecycleConditions[input.patternId].map(conditionId => {
    const declared = input.declarations[conditionId] ?? "UNKNOWN";
    const reasonCode = declared === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : declared === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN";
    return { conditionId, outcome: outcome(reasonCode), reasonCode };
  });
  const all = [...requirementChecks, ...conditionChecks];
  const status: "CONDITIONALLY_DOES_NOT_MATCH" | "NEEDS_INFORMATION" | "CONDITIONALLY_MATCHES" =
    all.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" :
      all.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES";
  return { patternId: input.patternId, requirements: { ...requirements }, declarations: { ...input.declarations }, requirementChecks, conditionChecks, status };
}
export type LifecycleAnalysis = ReturnType<typeof expectedAnalysis>;
export type LifecyclePreview = { assessmentVersion: number; analysis: LifecycleAnalysis };
export function validateLifecycleInput(input: LifecycleInput): void {
  exact(input, ["expectedVersion", "patternId", "declarations"]);
  record(input.declarations);
  const params = new URLSearchParams({ expectedVersion: String(input.expectedVersion), patternId: input.patternId });
  for (const [key, value] of Object.entries(input.declarations)) params.append(key, value);
  equal(input, parseLifecycleForm(params));
}
export function lifecycleAnalysis(value: unknown, input: LifecycleInput, requirements: ProvisioningRequirements): LifecycleAnalysis {
  validateLifecycleInput(input);
  provisioningRequirements({ provisioning: requirements });
  const analysis = expectedAnalysis(input, requirements);
  equal(value, analysis);
  return analysis;
}
/** Independent consumer replay: bind exact saved profile and request, not echoed caller requirements. */
export function lifecyclePreviewFromCore(value: unknown, binding: {
  workspaceId: string; assessmentId: string; input: LifecycleInput; requirements: ProvisioningRequirements;
}): LifecyclePreview {
  const raw = exact(value, ["workspaceId", "assessmentId", "assessmentVersion", "evaluatedAt", "analysis",
    "policyVersion", "scope", "analysisBasis", "patterns", "conditionDefinitions", "checkedProfilePaths", "deferredBoundaries", ...falseFlags]);
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  if (!uuid.test(binding.workspaceId) || !uuid.test(binding.assessmentId) || raw.workspaceId !== binding.workspaceId ||
      raw.assessmentId !== binding.assessmentId || raw.assessmentVersion !== binding.input.expectedVersion ||
      raw.policyVersion !== "provisioning-lifecycle-design-1" || raw.scope !== "PROVISIONING_LIFECYCLE_DESIGN_PREVIEW" ||
      raw.analysisBasis !== "SAVED_REQUIREMENTS_AND_UNVERIFIED_DESIGN_DECLARATIONS" || falseFlags.some(flag => raw[flag] !== false) ||
      !validInstant(raw.evaluatedAt)) throw invalid();
  equal(raw.patterns, lifecyclePatterns);
  equal(raw.conditionDefinitions, lifecycleConditions.SCIM_AND_JIT.map(conditionId => ({ conditionId, description: lifecycleDescriptions[conditionId] })));
  equal(raw.checkedProfilePaths, requirementKeys.map(key => `provisioning.${key}`));
  equal(raw.deferredBoundaries, deferred);
  return { assessmentVersion: binding.input.expectedVersion, analysis: lifecycleAnalysis(raw.analysis, binding.input, binding.requirements) };
}
