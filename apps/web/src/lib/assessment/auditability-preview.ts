import { auditabilityValues, auditCriteria, maximumRetentionDays, type AuditabilityValues,
  type AuditCriterion } from "./auditability.ts";

const identifier = /^[a-z0-9][a-z0-9.-]{0,99}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const policyVersion = "auditability-capability-preflight-1";
const nanosPerMillisecond = BigInt(1_000_000);
const freshnessNanoseconds = BigInt(90) * BigInt(86_400) * BigInt(1_000_000_000);
export const auditabilityPreviewByteLimit = 1_048_576;
export const auditabilityDeferredBoundaries = ["eventRecordContentAndScope", "auditRecordIntegrity",
  "auditAccessControls", "loggingFailureHandling", "exportDeliveryAndRetrieval",
  "deployedLoggingConfiguration", "complianceEvidence"] as const;

export const auditabilityReasons = {
  NO_REQUIREMENT: "Auditability does not constrain this decision. No capability match is claimed.",
  PREFERENCE_NOT_SCORED: "Auditability is preferred, but this partial preview does not score preferences.",
  REQUIREMENT_UNKNOWN: "Decide whether auditability is required before interpreting capabilities.",
  AUDIT_INTENT_UNCLEAR: "Forbidden needs clarification. This preview does not recommend disabling logs.",
  AUDIT_SCOPE_UNKNOWN: "Auditability is required, but no criteria are selected. Empty scope is not an exemption.",
  CRITERION_NOT_SELECTED: "This criterion is outside the selected requirements; it was not applied.",
  EVIDENCE_MISSING: "No fact is recorded for this exact scope. Missing evidence does not mean unsupported.",
  EVIDENCE_UNREVIEWED: "The synthetic claim is unreviewed; its support value cannot establish a match.",
  EVIDENCE_FROM_FUTURE: "The fact is dated after this evaluation and cannot be used.",
  EVIDENCE_STALE: "The fact is more than 90 days old at evaluation time and cannot establish a match.",
  CAPABILITY_UNKNOWN: "The usable fact records unknown support, so more information is needed.",
  CAPABILITY_UNAVAILABLE: "The usable synthetic fact records unsupported capability for this exact scope.",
  DOCUMENTED_CAPABILITY_AVAILABLE: "The usable synthetic fact records this capability, not deployed logging behavior.",
  RETENTION_DURATION_UNKNOWN: "Retention is supported, but no documented minimum duration is recorded.",
  RETENTION_BELOW_MINIMUM: "The documented provider minimum is below your requested duration.",
  RETENTION_MEETS_MINIMUM: "The documented provider minimum meets your request; deployed retention is not verified.",
} as const;
export type AuditabilityReason = keyof typeof auditabilityReasons;
export type AuditabilityScope = { optionId: string; plan: string; region: string; configuration: string };
export type AuditabilityEvidence = {
  scope: AuditabilityScope; emitter: "IDENTITY_PROVIDER"; criterion: AuditCriterion;
  support: "SUPPORTED" | "UNSUPPORTED" | "UNKNOWN"; documentedMinimumRetentionDays: number | null;
  evidenceStatus: "REVIEWED" | "UNREVIEWED"; sourceUrl: string; observedAt: string;
};
export type AuditabilityCheck = {
  criterion: AuditCriterion; outcome: "PASS" | "FAIL" | "UNKNOWN" | "NOT_APPLIED";
  reasonCode: AuditabilityReason; documentedMinimumRetentionDays: number | null;
};
export type AuditabilityCandidate = {
  displayName: string; scope: AuditabilityScope; checks: AuditabilityCheck[]; evidence: AuditabilityEvidence[];
  status: "MATCHES_CHECKED_REQUIREMENTS" | "DOES_NOT_MATCH" | "NEEDS_INFORMATION" | "NOT_APPLIED";
};
export type AuditabilityPreview = {
  assessmentVersion: number; baseCatalogVersion: string; evidenceVersion: string; evaluatedAt: string;
  values: AuditabilityValues; candidates: AuditabilityCandidate[];
};
export type AuditabilityPreviewBinding = {
  workspaceId: string; assessmentId: string; expectedVersion: number; values: AuditabilityValues;
};

function invalid(): never { throw new Error("Invalid auditability capability preview"); }
function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) invalid();
  return value as Record<string, unknown>;
}
function text(value: unknown, limit: number): string {
  if (typeof value !== "string" || !value || value.length > limit || value !== value.trim() ||
      /[\u0000-\u001f\u007f-\u009f]/u.test(value)) invalid();
  return value;
}
function version(value: unknown): string {
  const result = text(value, 100);
  if (!identifier.test(result)) invalid();
  return result;
}
function scope(value: unknown): AuditabilityScope {
  const raw = exact(value, ["optionId", "plan", "region", "configuration"]);
  return { optionId: version(raw.optionId), plan: text(raw.plan, 120), region: text(raw.region, 120),
    configuration: text(raw.configuration, 120) };
}
function same(a: unknown, b: unknown): boolean { return JSON.stringify(a) === JSON.stringify(b); }

// Core serializes UTC Instants. Preserve nanoseconds at future and exact 90-day boundaries;
// Date.parse alone would silently truncate evidence-policy precision to milliseconds.
function instant(value: unknown): { text: string; nanos: bigint } {
  const date = text(value, 30);
  const parts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(date);
  if (!parts) invalid();
  const millis = Date.parse(`${parts[1]}Z`);
  if (!Number.isFinite(millis) || new Date(millis).toISOString() !== `${parts[1]}.000Z`) invalid();
  return { text: date, nanos: BigInt(millis) * nanosPerMillisecond + BigInt((parts[2] ?? "").padEnd(9, "0")) };
}

export function auditabilityPreviewBinding(workspaceId: string, assessmentId: string,
  expectedVersion: number, values: AuditabilityValues): AuditabilityPreviewBinding {
  if (!uuid.test(workspaceId) || !uuid.test(assessmentId) ||
      !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) invalid();
  exact(values, ["criticality", "selectedCriteria", "minimumRetentionDays"]);
  const checked = auditabilityValues({ security: { auditability: values.criticality,
    auditabilityRequirements: { selectedCriteria: values.selectedCriteria,
      minimumRetentionDays: values.minimumRetentionDays } } });
  if (!checked) invalid();
  return { workspaceId, assessmentId, expectedVersion, values: checked };
}

function requirements(value: unknown, criticality: unknown, expected: AuditabilityValues): void {
  const raw = exact(value, ["selectedCriteria", "minimumRetentionDays"]);
  if (criticality !== expected.criticality || !same(raw.selectedCriteria, expected.selectedCriteria) ||
      raw.minimumRetentionDays !== expected.minimumRetentionDays) invalid();
}
function evidence(value: unknown, target: AuditabilityScope): AuditabilityEvidence[] {
  if (!Array.isArray(value) || value.length > auditCriteria.length) invalid();
  const seen = new Set<string>();
  return value.map(entry => {
    const raw = exact(entry, ["scope", "emitter", "criterion", "support", "documentedMinimumRetentionDays",
      "evidenceStatus", "sourceUrl", "observedAt"]);
    const boundScope = scope(raw.scope);
    if (!same(boundScope, target) || raw.emitter !== "IDENTITY_PROVIDER" ||
        !auditCriteria.some(c => c.key === raw.criterion) || seen.has(String(raw.criterion)) ||
        !["SUPPORTED", "UNSUPPORTED", "UNKNOWN"].includes(String(raw.support)) ||
        !["REVIEWED", "UNREVIEWED"].includes(String(raw.evidenceStatus))) invalid();
    seen.add(String(raw.criterion));
    const duration = raw.documentedMinimumRetentionDays;
    if (duration !== null && (raw.criterion !== "AUDIT_LOG_RETENTION" || raw.support !== "SUPPORTED" ||
        !Number.isSafeInteger(duration) || Number(duration) < 0 || Number(duration) > maximumRetentionDays)) invalid();
    const sourceUrl = text(raw.sourceUrl, 2048);
    if (!/^https:\/\/[^/@?#:]+\.invalid(?::[0-9]+)?(?:[/?#][^\s]*)?$/.test(sourceUrl)) invalid();
    const source = new URL(sourceUrl);
    if (source.protocol !== "https:" || source.username || source.password || !source.hostname.endsWith(".invalid")) invalid();
    return { scope: boundScope, emitter: "IDENTITY_PROVIDER", criterion: raw.criterion as AuditCriterion,
      support: raw.support as AuditabilityEvidence["support"], documentedMinimumRetentionDays: duration as number | null,
      evidenceStatus: raw.evidenceStatus as AuditabilityEvidence["evidenceStatus"], sourceUrl,
      observedAt: instant(raw.observedAt).text };
  });
}

function expectedCheck(criterion: AuditCriterion, values: AuditabilityValues,
  facts: AuditabilityEvidence[], at: bigint): AuditabilityCheck {
  let reason: AuditabilityReason;
  let duration: number | null = null;
  const fact = facts.find(f => f.criterion === criterion);
  if (values.criticality === "NOT_REQUIRED") reason = "NO_REQUIREMENT";
  else if (values.criticality === "PREFERRED") reason = "PREFERENCE_NOT_SCORED";
  else if (values.criticality === "UNKNOWN") reason = "REQUIREMENT_UNKNOWN";
  else if (values.criticality === "FORBIDDEN") reason = "AUDIT_INTENT_UNCLEAR";
  else if (values.selectedCriteria.length === 0) reason = "AUDIT_SCOPE_UNKNOWN";
  else if (!values.selectedCriteria.includes(criterion)) reason = "CRITERION_NOT_SELECTED";
  else if (!fact) reason = "EVIDENCE_MISSING";
  else if (fact.evidenceStatus !== "REVIEWED") reason = "EVIDENCE_UNREVIEWED";
  else if (instant(fact.observedAt).nanos > at) reason = "EVIDENCE_FROM_FUTURE";
  else if (at - instant(fact.observedAt).nanos > freshnessNanoseconds) reason = "EVIDENCE_STALE";
  else if (fact.support === "UNSUPPORTED") reason = "CAPABILITY_UNAVAILABLE";
  else if (fact.support === "UNKNOWN") reason = "CAPABILITY_UNKNOWN";
  else if (criterion !== "AUDIT_LOG_RETENTION") reason = "DOCUMENTED_CAPABILITY_AVAILABLE";
  else if (fact.documentedMinimumRetentionDays === null) reason = "RETENTION_DURATION_UNKNOWN";
  else {
    duration = fact.documentedMinimumRetentionDays;
    reason = duration < values.minimumRetentionDays! ? "RETENTION_BELOW_MINIMUM" : "RETENTION_MEETS_MINIMUM";
  }
  const outcome = ["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED", "CRITERION_NOT_SELECTED"].includes(reason) ? "NOT_APPLIED" :
    ["CAPABILITY_UNAVAILABLE", "RETENTION_BELOW_MINIMUM"].includes(reason) ? "FAIL" :
      ["DOCUMENTED_CAPABILITY_AVAILABLE", "RETENTION_MEETS_MINIMUM"].includes(reason) ? "PASS" : "UNKNOWN";
  return { criterion, outcome, reasonCode: reason, documentedMinimumRetentionDays: duration };
}

/** Strict personal binding and semantic evidence parity, not an authority or provider verification. */
export function auditabilityPreviewFromCore(value: unknown, input: AuditabilityPreviewBinding): AuditabilityPreview {
  const binding = auditabilityPreviewBinding(input.workspaceId, input.assessmentId, input.expectedVersion, input.values);
  const raw = exact(value, ["workspaceId", "assessmentId", "assessmentVersion", "baseCatalogVersion", "evidenceVersion",
    "catalogKind", "evaluatedAt", "criticality", "requirements", "candidates", "policyVersion", "scope",
    "checkedPaths", "sourceVerificationPerformed", "recommendationReady"]);
  if (raw.workspaceId !== binding.workspaceId || raw.assessmentId !== binding.assessmentId ||
      raw.assessmentVersion !== binding.expectedVersion || raw.catalogKind !== "SYNTHETIC" ||
      raw.policyVersion !== policyVersion || raw.scope !== "SYNTHETIC_AUDITABILITY_CAPABILITY_PREFLIGHT" ||
      !same(raw.checkedPaths, ["security.auditability", "security.auditabilityRequirements"]) ||
      raw.sourceVerificationPerformed !== false || raw.recommendationReady !== false ||
      !Array.isArray(raw.candidates) || raw.candidates.length === 0 || raw.candidates.length > 100) invalid();
  requirements(raw.requirements, raw.criticality, binding.values);
  const at = instant(raw.evaluatedAt);
  const scopes = new Set<string>();
  const candidates = raw.candidates.map(entry => {
    const candidate = exact(entry, ["displayName", "analysis", "evidence"]);
    const analysis = exact(candidate.analysis, ["optionScope", "criticality", "requirements", "evaluatedAt", "checks",
      "status", "policyVersion", "analysisBasis", "deferredBoundaries", "configurationVerified", "complianceVerified",
      "recommendationReady"]);
    const target = scope(analysis.optionScope);
    if (scopes.has(JSON.stringify(target)) || analysis.evaluatedAt !== at.text || analysis.policyVersion !== policyVersion ||
        analysis.analysisBasis !== "SYNTHETIC_SCOPED_PROVIDER_CAPABILITY_EVIDENCE" ||
        !same(analysis.deferredBoundaries, auditabilityDeferredBoundaries) || analysis.configurationVerified !== false ||
        analysis.complianceVerified !== false || analysis.recommendationReady !== false ||
        !Array.isArray(analysis.checks) || analysis.checks.length !== auditCriteria.length) invalid();
    scopes.add(JSON.stringify(target));
    requirements(analysis.requirements, analysis.criticality, binding.values);
    const facts = evidence(candidate.evidence, target);
    const checks = analysis.checks.map((entry: unknown, index: number) => {
      const rawCheck = exact(entry, ["criterion", "outcome", "reasonCode", "documentedMinimumRetentionDays"]);
      const expected = expectedCheck(auditCriteria[index].key, binding.values, facts, at.nanos);
      if (rawCheck.criterion !== expected.criterion || rawCheck.outcome !== expected.outcome ||
          rawCheck.reasonCode !== expected.reasonCode ||
          rawCheck.documentedMinimumRetentionDays !== expected.documentedMinimumRetentionDays) invalid();
      return expected;
    });
    const status: AuditabilityCandidate["status"] = checks.some(c => c.outcome === "FAIL") ? "DOES_NOT_MATCH" :
      checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" :
        checks.some(c => c.outcome === "PASS") ? "MATCHES_CHECKED_REQUIREMENTS" : "NOT_APPLIED";
    if (analysis.status !== status) invalid();
    return { displayName: text(candidate.displayName, 120), scope: target, checks, evidence: facts, status };
  });
  return { assessmentVersion: binding.expectedVersion, baseCatalogVersion: version(raw.baseCatalogVersion),
    evidenceVersion: version(raw.evidenceVersion), evaluatedAt: at.text, values: binding.values, candidates };
}
