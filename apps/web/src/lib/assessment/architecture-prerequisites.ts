/** Client-safe typed declarations: these are not observed configuration or provider evidence. */
export const prerequisiteIds = {
  BFF_SESSION: ["BFF_BACKEND_API_PROXY", "BFF_SESSION_DEFENSES"],
  SERVER_SIDE_SESSION: ["SERVER_SESSION_RESOURCE_ACCESS", "DIRECT_BROWSER_API_ACCESS_ASSESSED"],
  SPA_CODE_PKCE: ["SPA_PUBLIC_PKCE_BROWSER_ENDPOINTS", "SPA_TOKEN_THREAT_MODEL"],
  NATIVE_CODE_PKCE: ["NATIVE_EXTERNAL_AGENT_REDIRECT_PKCE", "NATIVE_STORAGE_API_AUTHORIZATION"],
  M2M_CLIENT_CREDENTIALS: ["WORKLOAD_CONFIDENTIAL_CLIENT", "WORKLOAD_AUTHORIZATION_CONTEXT", "WORKLOAD_GRANT_API_PERMISSIONS"],
} as const;
export type ArchitecturePatternId = keyof typeof prerequisiteIds;
export type PrerequisiteId = (typeof prerequisiteIds)[ArchitecturePatternId][number];
export type DesignDeclaration = "SATISFIED" | "NOT_SATISFIED" | "UNKNOWN";
export type PrerequisiteInput = {
  expectedVersion: number;
  patternId: ArchitecturePatternId;
  declarations: Partial<Record<PrerequisiteId, DesignDeclaration>>;
};
export type PrerequisiteAnalysis = {
  patternId: ArchitecturePatternId;
  clientScope: "SELECTED" | "NOT_SELECTED" | "UNKNOWN";
  checks: { prerequisiteId: PrerequisiteId;
    outcome: "CONDITIONALLY_SATISFIED" | "CONDITIONALLY_NOT_SATISFIED" | "UNKNOWN" | "NOT_APPLICABLE";
    reasonCode: "DECLARED_CONDITION_SATISFIED" | "DECLARED_CONDITION_NOT_SATISFIED" |
      "CONDITION_UNKNOWN" | "CLIENT_SCOPE_UNKNOWN" | "PATTERN_NOT_APPLICABLE" }[];
  status: "CONDITIONALLY_MATCHES" | "CONDITIONALLY_DOES_NOT_MATCH" | "NEEDS_INFORMATION" | "NOT_APPLICABLE";
  policyVersion: "architecture-prerequisites-1";
  analysisBasis: "UNVERIFIED_DESIGN_DECLARATIONS";
  configurationVerified: false;
  providerCompatibilityVerified: false;
  recommendationReady: false;
};
export type PrerequisitePreview = { assessmentVersion: number; analysis: PrerequisiteAnalysis };
export const architecturePrerequisiteByteLimit = 32_768;
export class InvalidPrerequisiteForm extends Error { }

export function parsePrerequisiteForm(params: URLSearchParams): PrerequisiteInput {
  const versions = params.getAll("expectedVersion");
  const patterns = params.getAll("patternId");
  if (versions.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versions[0]) ||
      !Number.isSafeInteger(Number(versions[0])) || patterns.length !== 1 ||
      !Object.hasOwn(prerequisiteIds, patterns[0])) throw new InvalidPrerequisiteForm();
  const patternId = patterns[0] as ArchitecturePatternId;
  const ids: readonly string[] = prerequisiteIds[patternId];
  const declarations: PrerequisiteInput["declarations"] = {};
  for (const [key, value] of params) {
    if (key === "expectedVersion" || key === "patternId") continue;
    if (!ids.includes(key) || params.getAll(key).length !== 1 ||
        !["SATISFIED", "NOT_SATISFIED", "UNKNOWN"].includes(value)) throw new InvalidPrerequisiteForm();
    declarations[key as PrerequisiteId] = value as DesignDeclaration;
  }
  return { expectedVersion: Number(versions[0]), patternId, declarations };
}

function exactObject(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) {
    throw new Error("Invalid prerequisite preview");
  }
  return value as Record<string, unknown>;
}

/** Fail closed on forged readiness, missing/foreign checks, scope or declaration/result drift. */
export function prerequisiteAnalysis(value: unknown, input: PrerequisiteInput,
  scope: PrerequisiteAnalysis["clientScope"]): PrerequisiteAnalysis {
  const raw = exactObject(value, ["patternId", "clientScope", "checks", "status", "policyVersion",
    "analysisBasis", "configurationVerified", "providerCompatibilityVerified", "recommendationReady"]);
  const ids = prerequisiteIds[input.patternId];
  if (raw.patternId !== input.patternId || raw.clientScope !== scope ||
      raw.policyVersion !== "architecture-prerequisites-1" ||
      raw.analysisBasis !== "UNVERIFIED_DESIGN_DECLARATIONS" || raw.configurationVerified !== false ||
      raw.providerCompatibilityVerified !== false || raw.recommendationReady !== false ||
      !Array.isArray(raw.checks) || raw.checks.length !== ids.length) throw new Error("Invalid prerequisite preview");
  const checks = raw.checks.map((entry: unknown, index: number) => {
    const check = exactObject(entry, ["prerequisiteId", "outcome", "reasonCode"]);
    const id = ids[index];
    const declaration = input.declarations[id] ?? "UNKNOWN";
    const reason = scope === "NOT_SELECTED" ? "PATTERN_NOT_APPLICABLE" : scope === "UNKNOWN" ? "CLIENT_SCOPE_UNKNOWN" :
      declaration === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : declaration === "NOT_SATISFIED" ?
        "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN";
    const outcome = scope === "NOT_SELECTED" ? "NOT_APPLICABLE" : scope === "UNKNOWN" || declaration === "UNKNOWN" ?
      "UNKNOWN" : declaration === "SATISFIED" ? "CONDITIONALLY_SATISFIED" : "CONDITIONALLY_NOT_SATISFIED";
    if (check.prerequisiteId !== id || check.reasonCode !== reason || check.outcome !== outcome) {
      throw new Error("Invalid prerequisite preview");
    }
    return { prerequisiteId: id, outcome, reasonCode: reason } as PrerequisiteAnalysis["checks"][number];
  });
  const status = scope === "NOT_SELECTED" ? "NOT_APPLICABLE" :
    checks.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" :
      checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES";
  if (raw.status !== status) throw new Error("Invalid prerequisite preview");
  return { patternId: input.patternId, clientScope: scope, checks, status,
    policyVersion: "architecture-prerequisites-1", analysisBasis: "UNVERIFIED_DESIGN_DECLARATIONS",
    configurationVerified: false, providerCompatibilityVerified: false, recommendationReady: false };
}

/** Count actual UTF-8 bytes; an optional deadline/cancel signal also interrupts a pending body read. */
export async function boundedPrerequisiteText(message: Request | Response, limit: number, signal?: AbortSignal): Promise<string> {
  if (signal?.aborted) {
    void message.body?.cancel().catch(() => {});
    signal.throwIfAborted();
  }
  const length = message.headers.get("content-length");
  if (length && (!/^[0-9]+$/.test(length) || Number(length) > limit)) {
    void message.body?.cancel().catch(() => {});
    throw new RangeError("Body too large");
  }
  if (!message.body) throw new InvalidPrerequisiteForm();
  const reader = message.body.getReader();
  const cancel = () => { void reader.cancel().catch(() => {}); };
  signal?.addEventListener("abort", cancel, { once: true });
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0;
  let text = "";
  try {
    signal?.throwIfAborted();
    while (true) {
      const chunk = await reader.read();
      signal?.throwIfAborted();
      if (chunk.done) return text + decoder.decode();
      size += chunk.value.byteLength;
      if (size > limit) throw new RangeError("Body too large");
      text += decoder.decode(chunk.value, { stream: true });
    }
  } catch (error) { cancel(); throw error; }
  finally { signal?.removeEventListener("abort", cancel); reader.releaseLock(); }
}
