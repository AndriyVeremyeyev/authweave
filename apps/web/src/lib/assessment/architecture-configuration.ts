import type { ArchitecturePatternId, PrerequisiteAnalysis } from "./architecture-prerequisites.ts";
import { clientTypes, type EvaluationContextValues } from "./evaluation-context.ts";
import { criticalities } from "./capabilities.ts";

// Client-safe reference designs, independently checked against actual Core replies.
export const architectureSettingValues = {
  OAUTH_FLOW: ["AUTHORIZATION_CODE", "CLIENT_CREDENTIALS", "IMPLICIT", "UNKNOWN"],
  OAUTH_CLIENT_TYPE: ["CONFIDENTIAL", "PUBLIC", "UNKNOWN"],
  CLIENT_AUTHENTICATION: ["SERVER_HELD_CREDENTIAL", "WORKLOAD_HELD_CREDENTIAL", "NONE", "DISTRIBUTED_SHARED_SECRET", "UNKNOWN"],
  TOKEN_LOCATION: ["APPLICATION_SERVER", "BROWSER", "NATIVE_APP", "WORKLOAD", "UNKNOWN"],
  PKCE_METHOD: ["S256", "PLAIN", "NONE", "UNKNOWN"],
  REDIRECT_MATCHING: ["EXACT_REGISTERED", "NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION", "WILDCARD", "UNKNOWN"],
  SESSION_COOKIE_SECURE: ["ENABLED", "DISABLED", "UNKNOWN"],
  SESSION_COOKIE_HTTP_ONLY: ["ENABLED", "DISABLED", "UNKNOWN"],
  SESSION_CSRF_DEFENSE: ["DEFENSE_PLANNED", "ABSENT", "UNKNOWN"],
  RESOURCE_ACCESS: ["BFF_PROXY", "SESSION_BACKEND", "DIRECT_BROWSER", "UNKNOWN"],
  BROWSER_TOKEN_ENDPOINT_ACCESS: ["REQUIRED_ORIGINS_PLANNED", "BLOCKED", "UNKNOWN"],
  NATIVE_USER_AGENT: ["EXTERNAL_BROWSER", "EMBEDDED_WEBVIEW", "UNKNOWN"],
  WORKLOAD_AUTHORIZATION: ["WORKLOAD_OWN_OR_PREARRANGED", "USER_DELEGATION", "UNKNOWN"],
} as const;
export type ArchitectureSettingId = keyof typeof architectureSettingValues;
export type ArchitectureSettingValue = (typeof architectureSettingValues)[ArchitectureSettingId][number];
const base = ["OAUTH_FLOW", "OAUTH_CLIENT_TYPE", "CLIENT_AUTHENTICATION", "TOKEN_LOCATION"] as const;
const human = [...base, "PKCE_METHOD", "REDIRECT_MATCHING"] as const;
const session = ["SESSION_COOKIE_SECURE", "SESSION_COOKIE_HTTP_ONLY", "SESSION_CSRF_DEFENSE", "RESOURCE_ACCESS"] as const;
export const architectureConfigurationPatterns = {
  BFF_SESSION: { client: "BROWSER", token: "SERVER_SIDE", ids: [...human, ...session],
    compatible: ["AUTHORIZATION_CODE", "CONFIDENTIAL", "SERVER_HELD_CREDENTIAL", "APPLICATION_SERVER", "S256", "EXACT_REGISTERED", "ENABLED", "ENABLED", "DEFENSE_PLANNED", "BFF_PROXY"] },
  SERVER_SIDE_SESSION: { client: "BROWSER", token: "SERVER_SIDE", ids: [...human, ...session],
    compatible: ["AUTHORIZATION_CODE", "CONFIDENTIAL", "SERVER_HELD_CREDENTIAL", "APPLICATION_SERVER", "S256", "EXACT_REGISTERED", "ENABLED", "ENABLED", "DEFENSE_PLANNED", "SESSION_BACKEND"] },
  SPA_CODE_PKCE: { client: "BROWSER", token: "BROWSER", ids: [...human, "RESOURCE_ACCESS", "BROWSER_TOKEN_ENDPOINT_ACCESS"],
    compatible: ["AUTHORIZATION_CODE", "PUBLIC", "NONE", "BROWSER", "S256", "EXACT_REGISTERED", "DIRECT_BROWSER", "REQUIRED_ORIGINS_PLANNED"] },
  NATIVE_CODE_PKCE: { client: "NATIVE_MOBILE", token: "NATIVE_APP", ids: [...human, "NATIVE_USER_AGENT"],
    compatible: ["AUTHORIZATION_CODE", "PUBLIC", "NONE", "NATIVE_APP", "S256", ["EXACT_REGISTERED", "NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION"], "EXTERNAL_BROWSER"] },
  M2M_CLIENT_CREDENTIALS: { client: "MACHINE_TO_MACHINE", token: "WORKLOAD", ids: [...base, "WORKLOAD_AUTHORIZATION"],
    compatible: ["CLIENT_CREDENTIALS", "CONFIDENTIAL", "WORKLOAD_HELD_CREDENTIAL", "WORKLOAD", "WORKLOAD_OWN_OR_PREARRANGED"] },
} as const;
export const architectureSettingDescriptions: Record<ArchitectureSettingId, string> = {
  OAUTH_FLOW: "Grant of the primary reference flow; not proof of runtime protocol validation.",
  OAUTH_CLIENT_TYPE: "Whether this client can protect its authentication credentials.",
  CLIENT_AUTHENTICATION: "Credential custody category only; never submit a secret. A distributed shared secret does not make a public client confidential.",
  TOKEN_LOCATION: "Custody of OAuth tokens in the primary reference design; storage security remains unverified.",
  PKCE_METHOD: "AuthWeave conservatively requires S256 in all human reference designs. This project policy is not a universal normative MUST for every confidential OIDC client.",
  REDIRECT_MATCHING: "Exact registered redirect matching; the port exception is limited to native loopback IP-literal redirects, not wildcard hosts or paths. Actual URI registration is deferred.",
  SESSION_COOKIE_SECURE: "Secure cookie planned for the application session; no cookie was inspected.",
  SESSION_COOKIE_HTTP_ONLY: "HttpOnly cookie planned for the application session; no cookie was inspected.",
  SESSION_CSRF_DEFENSE: "A session CSRF defense is planned; effectiveness, SameSite policy and other defenses remain deferred.",
  RESOURCE_ACCESS: "Primary resource path of this reference pattern only; additional direct browser APIs require independent assessment.",
  BROWSER_TOKEN_ENDPOINT_ACCESS: "Required browser origins are planned at the token endpoint; actual CORS and interoperability are unverified.",
  NATIVE_USER_AGENT: "Authorization uses an external browser rather than an embedded webview.",
  WORKLOAD_AUTHORIZATION: "Client credentials cover the workload's own or prearranged resources, not user-delegated authorization.",
};
export const architectureConfigurationDeferred = [
  "Observed client registration and actual issuer/redirect values", "Runtime protocol validation, token storage and session defenses",
  "API authorization, scopes, audience and grant permissions", "Provider interoperability, browser CORS and native redirect ownership",
  "Provisioning, offboarding, assurance, compliance and operations", "Independent assessment of additional resource-access paths",
];
const security = "https://www.rfc-editor.org/rfc/rfc9700.html#section-2.1", native = "https://www.rfc-editor.org/rfc/rfc8252.html#section-8";
const browser = "https://www.rfc-editor.org/rfc/rfc10017.html#section-6.1", workload = "https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4";
export function architectureSettingDefinitions(patternId: ArchitecturePatternId) {
  const pattern = architectureConfigurationPatterns[patternId];
  return pattern.ids.map((settingId, index) => {
    const references = base.includes(settingId as typeof base[number]) ? [patternId === "M2M_CLIENT_CREDENTIALS" ? workload : patternId === "NATIVE_CODE_PKCE" ? native : security]
      : settingId === "REDIRECT_MATCHING" ? patternId === "NATIVE_CODE_PKCE" ? [security, native] : [security]
      : settingId === "PKCE_METHOD" ? [security] : settingId === "NATIVE_USER_AGENT" ? [native]
      : settingId === "WORKLOAD_AUTHORIZATION" ? [workload] : settingId === "BROWSER_TOKEN_ENDPOINT_ACCESS" ? ["https://www.rfc-editor.org/rfc/rfc10017.html#section-6.3"] : [browser];
    return { settingId, description: architectureSettingDescriptions[settingId], allowedValues: [...architectureSettingValues[settingId]],
      compatibleValues: [pattern.compatible[index]].flat() as ArchitectureSettingValue[], references };
  });
}
export type ArchitectureConfigurationInput = { expectedVersion: number; patternId: ArchitecturePatternId;
  settings: Partial<Record<ArchitectureSettingId, ArchitectureSettingValue>> };
export type ArchitectureClientScope = PrerequisiteAnalysis["clientScope"];
export type ArchitectureConfigurationContext = Pick<EvaluationContextValues, "clients" | "browserTokenExposureMinimization">;
export const architectureConfigurationByteLimit = 32_768;
export class InvalidArchitectureConfigurationForm extends Error { }
const invalid = () => new Error("Invalid architecture settings preview");
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
    expected.forEach((value, index) => equal(actual[index], value));
  } else if (expected && typeof expected === "object") {
    const raw = exact(actual, Object.keys(expected)); for (const [key, value] of Object.entries(expected)) equal(raw[key], value);
  } else if (actual !== expected) throw invalid();
}
export function parseArchitectureConfigurationForm(params: URLSearchParams): ArchitectureConfigurationInput {
  const versions = params.getAll("expectedVersion"), patterns = params.getAll("patternId");
  if (versions.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versions[0]) || !Number.isSafeInteger(Number(versions[0])) ||
      patterns.length !== 1 || !Object.hasOwn(architectureConfigurationPatterns, patterns[0])) throw new InvalidArchitectureConfigurationForm();
  const patternId = patterns[0] as ArchitecturePatternId, settings: ArchitectureConfigurationInput["settings"] = {};
  const ids: readonly string[] = architectureConfigurationPatterns[patternId].ids;
  for (const [key, value] of params) {
    if (key === "expectedVersion" || key === "patternId") continue;
    if (!ids.includes(key) || params.getAll(key).length !== 1 || !(architectureSettingValues[key as ArchitectureSettingId] as readonly string[]).includes(value))
      throw new InvalidArchitectureConfigurationForm();
    settings[key as ArchitectureSettingId] = value as ArchitectureSettingValue;
  }
  return { expectedVersion: Number(versions[0]), patternId, settings };
}
export function validateArchitectureConfigurationInput(input: ArchitectureConfigurationInput): void {
  exact(input, ["expectedVersion", "patternId", "settings"]); const settings = record(input.settings);
  if (typeof input.expectedVersion !== "number" || !Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 0) throw invalid();
  const params = new URLSearchParams({ expectedVersion: String(input.expectedVersion), patternId: input.patternId });
  for (const [key, value] of Object.entries(settings)) { if (typeof value !== "string") throw invalid(); params.append(key, value); }
  equal(input, parseArchitectureConfigurationForm(params));
}
function expectedAnalysis(input: ArchitectureConfigurationInput, scope: ArchitectureClientScope) {
  if (!["SELECTED", "NOT_SELECTED", "UNKNOWN"].includes(scope)) throw invalid();
  const checks = architectureSettingDefinitions(input.patternId).map(definition => {
    const value = Object.hasOwn(input.settings, definition.settingId) ? input.settings[definition.settingId]! : "UNKNOWN";
    const reasonCode = scope === "NOT_SELECTED" ? "PATTERN_NOT_APPLICABLE" : scope === "UNKNOWN" ? "CLIENT_SCOPE_UNKNOWN"
      : value === "UNKNOWN" ? "SETTING_UNKNOWN" : definition.compatibleValues.includes(value) ? "EXPECTED_SETTING_DECLARED" : "INCOMPATIBLE_SETTING_DECLARED";
    const outcome = reasonCode === "PATTERN_NOT_APPLICABLE" ? "NOT_APPLICABLE" : reasonCode === "CLIENT_SCOPE_UNKNOWN" || reasonCode === "SETTING_UNKNOWN" ? "UNKNOWN"
      : reasonCode === "EXPECTED_SETTING_DECLARED" ? "CONDITIONALLY_SATISFIED" : "CONDITIONALLY_NOT_SATISFIED";
    return { settingId: definition.settingId, outcome, reasonCode };
  });
  const status = scope === "NOT_SELECTED" ? "NOT_APPLICABLE" : checks.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH"
    : checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES";
  return { patternId: input.patternId, clientScope: scope, settings: { ...input.settings }, checks, status,
    policyVersion: "architecture-configuration-design-1", analysisBasis: "UNVERIFIED_PROPOSED_CONFIGURATION",
    configurationObserved: false, configurationVerified: false, providerCompatibilityVerified: false, runtimeFlowVerified: false,
    recommendationReady: false, publicationReady: false, writesPerformed: false } as const;
}
export type ArchitectureConfigurationAnalysis = ReturnType<typeof expectedAnalysis>;
export type ArchitectureConfigurationPreview = { assessmentVersion: number; analysis: ArchitectureConfigurationAnalysis };
export function architectureConfigurationAnalysis(value: unknown, input: ArchitectureConfigurationInput, scope: ArchitectureClientScope): ArchitectureConfigurationAnalysis {
  validateArchitectureConfigurationInput(input); const expected = expectedAnalysis(input, scope); equal(value, expected); return expected;
}
const checked = ["application.clients", "security.browserTokenExposureMinimization"];
const deferred = ["application.type", "audience", "protocols", "provisioning", "security.multiFactorAuthentication", "security.auditability",
  "security.dataResidency", "security.assurance", "security.complianceTargets", "operations"];
function text(value: unknown, limit = 400) {
  if (typeof value !== "string" || value.length === 0 || value.length > limit) throw invalid();
}
function instant(value: unknown) {
  if (typeof value !== "string") throw invalid();
  const parts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d{1,9})?Z$/.exec(value);
  if (!parts || !Number.isFinite(Date.parse(`${parts[1]}Z`)) || new Date(`${parts[1]}Z`).toISOString() !== `${parts[1]}.000Z`) throw invalid();
}
/** Replays the saved-input checks separately; a proposed settings match cannot override them. */
export function architectureConfigurationFromCore(value: unknown, binding: { workspaceId: string; assessmentId: string;
  input: ArchitectureConfigurationInput; context: ArchitectureConfigurationContext }): ArchitectureConfigurationPreview {
  validateArchitectureConfigurationInput(binding.input);
  const raw = exact(value, ["preflight", "analysis", "settingDefinitions", "deferredBoundaries"]), context = binding.context;
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  if (!uuid.test(binding.workspaceId) || !uuid.test(binding.assessmentId) || !Array.isArray(context.clients) || context.clients.length > 3
      || new Set(context.clients).size !== context.clients.length || context.clients.some(client => !clientTypes.includes(client))
      || !criticalities.includes(context.browserTokenExposureMinimization)) throw invalid();
  const preflight = exact(raw.preflight, ["workspaceId", "assessmentId", "assessmentVersion", "policyVersion", "evaluatedAt", "scope",
    "recommendationReady", "selectedClients", "browserTokenExposureRequirement", "checkedPaths", "deferredPaths", "patterns"]);
  if (preflight.workspaceId !== binding.workspaceId || preflight.assessmentId !== binding.assessmentId || preflight.assessmentVersion !== binding.input.expectedVersion
      || preflight.policyVersion !== "architecture-pattern-preflight-1" || preflight.scope !== "ARCHITECTURE_PATTERN_PREFLIGHT" || preflight.recommendationReady !== false
      || preflight.browserTokenExposureRequirement !== context.browserTokenExposureMinimization) throw invalid();
  instant(preflight.evaluatedAt); equal(preflight.selectedClients, [...context.clients].sort()); equal(preflight.checkedPaths, checked); equal(preflight.deferredPaths, deferred);
  if (!Array.isArray(preflight.patterns) || preflight.patterns.length !== 5) throw invalid();
  Object.entries(architectureConfigurationPatterns).forEach(([patternId, definition], index) => {
    const pattern = exact((preflight.patterns as unknown[])[index], ["patternId", "displayName", "clientType", "tokenHandling", "status", "checks", "advantages", "tradeoffs", "prerequisites", "references"]);
    if (pattern.patternId !== patternId || pattern.clientType !== definition.client || pattern.tokenHandling !== definition.token || !Array.isArray(pattern.checks) || pattern.checks.length !== 2) throw invalid();
    const selected = context.clients.includes(definition.client), unknown = context.clients.length === 0;
    const clientReason = unknown ? "CLIENT_CONTEXT_UNKNOWN" : selected ? "CLIENT_SELECTED" : "CLIENT_NOT_SELECTED";
    const tokenReason = unknown ? "CLIENT_CONTEXT_UNKNOWN" : !selected ? "PATTERN_NOT_APPLICABLE" : definition.client !== "BROWSER" ? "BROWSER_CRITERION_NOT_APPLICABLE"
      : context.browserTokenExposureMinimization === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : context.browserTokenExposureMinimization === "PREFERRED" ? "PREFERENCE_NOT_SCORED"
        : context.browserTokenExposureMinimization === "NOT_REQUIRED" ? "NO_REQUIREMENT" : context.browserTokenExposureMinimization === "FORBIDDEN" ? "MINIMIZATION_PROHIBITION_UNDEFINED"
          : definition.token === "SERVER_SIDE" ? "TOKENS_HELD_SERVER_SIDE" : "ACCEPTABLE_EXPOSURE_UNDEFINED";
    const outcomes = [unknown ? "UNKNOWN" : selected ? "PASS" : "NOT_APPLIED",
      ["PREFERENCE_NOT_SCORED", "NO_REQUIREMENT", "PATTERN_NOT_APPLICABLE", "BROWSER_CRITERION_NOT_APPLICABLE"].includes(tokenReason) ? "NOT_APPLIED" : tokenReason === "TOKENS_HELD_SERVER_SIDE" ? "PASS" : "UNKNOWN"];
    pattern.checks.forEach((entry, checkIndex) => {
      const check = exact(entry, ["profilePath", "outcome", "reasonCode", "explanation"]);
      if (check.profilePath !== checked[checkIndex] || check.outcome !== outcomes[checkIndex] || check.reasonCode !== [clientReason, tokenReason][checkIndex]) throw invalid();
      text(check.explanation);
    });
    const status = unknown ? "NEEDS_INFORMATION" : !selected ? "NOT_APPLICABLE" : outcomes.includes("UNKNOWN") ? "NEEDS_INFORMATION" : "MATCHES_CHECKED_REQUIREMENTS";
    if (pattern.status !== status) throw invalid(); text(pattern.displayName);
    for (const field of ["advantages", "tradeoffs", "prerequisites"]) {
      const items = pattern[field]; if (!Array.isArray(items) || items.length < 1 || items.length > 4 || new Set(items).size !== items.length) throw invalid();
      items.forEach(item => text(item));
    }
    if (!Array.isArray(pattern.references) || pattern.references.length < 1 || pattern.references.length > 2) throw invalid();
    pattern.references.forEach(item => { text(item, 2048); const url = new URL(item as string);
      if (url.protocol !== "https:" || url.username || url.password || !["www.ietf.org", "www.rfc-editor.org"].includes(url.hostname)) throw invalid(); });
  });
  equal(raw.settingDefinitions, architectureSettingDefinitions(binding.input.patternId)); equal(raw.deferredBoundaries, architectureConfigurationDeferred);
  const scope = context.clients.length === 0 ? "UNKNOWN" : context.clients.includes(architectureConfigurationPatterns[binding.input.patternId].client) ? "SELECTED" : "NOT_SELECTED";
  return { assessmentVersion: binding.input.expectedVersion, analysis: architectureConfigurationAnalysis(raw.analysis, binding.input, scope) };
}
