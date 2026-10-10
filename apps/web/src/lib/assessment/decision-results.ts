/** Client-safe transport guards, not a second engine or source verifier. Core owns historical replay. */
export type ResultReference = { resultId: string; version: number; resultSha256: string };
export type ResultItem = { reference: ResultReference; assessmentVersion: number;
  catalog: { snapshotId: string; catalogVersion: string; snapshotSha256: string };
  previousResult: ResultReference | null; recordedAt: string };
export type ResultPage = { scope: "OWNED_ASSESSMENT_RESULT_INDEX"; workspaceId: string; assessmentId: string;
  items: ResultItem[]; nextBefore: ResultReference | null; historicalReplayVerified: false };
export type ResultCandidate = { optionId: string; product: string; plan: string; region: string;
  deployment: "MANAGED" | "SELF_HOSTED"; hardVerdict: "ELIGIBLE" | "EXCLUDED" | "UNRESOLVED";
  score: { lowerBound: number; upperBound: number; unknownWeight: number } | null };
export type ResultSummary = { scope: "VERIFIED_ASSESSMENT_DECISION_SUMMARY"; workspaceId: string; assessmentId: string;
  item: ResultItem; evaluatedAt: string; profileSchemaVersion: number; profileSha256: string; policySha256: string;
  weights: { mode: "NONE" | "EXPLICIT"; values: { capability: string; weight: number }[] };
  status: "RANKED_SHORTLIST" | "UNRANKED_SHORTLIST" | "NEEDS_INFORMATION" | "NO_ELIGIBLE_OPTIONS";
  shortlist: string[]; candidates: ResultCandidate[]; verificationGapCount: 22; historicalReplayVerified: true;
  externalSourceVerificationPerformed: false; configurationVerified: false; complianceVerified: false; decisionApproved: false };
export const resultHistoryByteLimit = 65_536;
export const resultSummaryByteLimit = 262_144;
export const resultUuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const sha = /^[a-f0-9]{64}$/;
export const resultCapabilities = ["OIDC", "SAML", "SOCIAL_LOGIN", "ENTERPRISE_SSO", "SCIM", "JIT", "GROUP_SYNC", "MFA", "OAUTH2_APIS"] as const;
const capabilities: readonly string[] = resultCapabilities;
const invalid = (): never => { throw new Error("Invalid owned result transport"); };
function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length ||
      keys.some(key => !Object.hasOwn(value, key))) invalid();
  return value as Record<string, unknown>;
}
function integer(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isSafeInteger(value) || Number(value) < min || Number(value) > max) invalid();
  return value as number;
}
function text(value: unknown, max: number, pattern?: RegExp): string {
  if (typeof value !== "string" || !value.length || value.length > max || (pattern && !pattern.test(value))) invalid();
  return value as string;
}
function date(value: unknown): string {
  const valueText = text(value, 100);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(valueText) || Number.isNaN(Date.parse(valueText))) invalid();
  return valueText;
}
export function resultReference(value: unknown): ResultReference {
  const raw = object(value, ["resultId", "version", "resultSha256"]);
  return { resultId: text(raw.resultId, 36, resultUuid), version: integer(raw.version, 1), resultSha256: text(raw.resultSha256, 64, sha) };
}
function sameReference(a: ResultReference, b: ResultReference): boolean {
  return a.resultId === b.resultId && a.version === b.version && a.resultSha256 === b.resultSha256;
}
function item(value: unknown): ResultItem {
  const raw = object(value, ["reference", "assessmentVersion", "catalog", "previousResult", "recordedAt"]);
  const reference = resultReference(raw.reference), previousResult = raw.previousResult === null ? null : resultReference(raw.previousResult);
  if (reference.version === 1 ? previousResult !== null : !previousResult ||
      previousResult.version !== reference.version - 1 || previousResult.resultId === reference.resultId) invalid();
  const catalog = object(raw.catalog, ["snapshotId", "catalogVersion", "snapshotSha256"]);
  return { reference, previousResult, assessmentVersion: integer(raw.assessmentVersion), recordedAt: date(raw.recordedAt),
    catalog: { snapshotId: text(catalog.snapshotId, 36, resultUuid), catalogVersion: text(catalog.catalogVersion, 100, /^[a-z0-9][a-z0-9.-]{0,99}$/),
      snapshotSha256: text(catalog.snapshotSha256, 64, sha) } };
}
export function resultPageFromCore(value: unknown, workspaceId: string, assessmentId: string, before: ResultReference | null): ResultPage {
  const raw = object(value, ["scope", "workspaceId", "assessmentId", "items", "nextBefore", "historicalReplayVerified"]);
  if (raw.scope !== "OWNED_ASSESSMENT_RESULT_INDEX" || raw.workspaceId !== workspaceId || raw.assessmentId !== assessmentId ||
      raw.historicalReplayVerified !== false || !Array.isArray(raw.items) || raw.items.length > 20) invalid();
  const items = (raw.items as unknown[]).map(item), nextBefore = raw.nextBefore === null ? null : resultReference(raw.nextBefore);
  if (new Set(items.map(i => i.reference.resultId)).size !== items.length ||
      (before && (items.length ? items[0].reference.version !== before.version - 1 : before.version !== 1)) ||
      items.some((entry, index) => index > 0 && (!items[index - 1].previousResult ||
        !sameReference(items[index - 1].previousResult!, entry.reference))) ||
      (nextBefore && (items.length !== 20 || !sameReference(nextBefore, items.at(-1)!.reference) || nextBefore.version === 1)) ||
      (!nextBefore && items.length && items.at(-1)!.reference.version !== 1)) invalid();
  return { scope: "OWNED_ASSESSMENT_RESULT_INDEX", workspaceId, assessmentId, items, nextBefore, historicalReplayVerified: false };
}
export function resultSummaryFromCore(value: unknown, workspaceId: string, assessmentId: string, reference: ResultReference): ResultSummary {
  const raw = object(value, ["scope", "workspaceId", "assessmentId", "item", "evaluatedAt", "profileSchemaVersion", "profileSha256",
    "policySha256", "weights", "status", "shortlist", "candidates", "verificationGapCount", "historicalReplayVerified",
    "externalSourceVerificationPerformed", "configurationVerified", "complianceVerified", "decisionApproved"]);
  const saved = item(raw.item);
  if (raw.scope !== "VERIFIED_ASSESSMENT_DECISION_SUMMARY" || raw.workspaceId !== workspaceId || raw.assessmentId !== assessmentId ||
      !sameReference(saved.reference, reference) || raw.historicalReplayVerified !== true || raw.verificationGapCount !== 22 ||
      ["externalSourceVerificationPerformed", "configurationVerified", "complianceVerified", "decisionApproved"].some(key => raw[key] !== false) ||
      !Array.isArray(raw.candidates) || raw.candidates.length < 1 || raw.candidates.length > 100 || !Array.isArray(raw.shortlist)) invalid();
  const weightsRaw = object(raw.weights, ["mode", "values"]);
  if (!["NONE", "EXPLICIT"].includes(String(weightsRaw.mode)) || !Array.isArray(weightsRaw.values) || weightsRaw.values.length > 9) invalid();
  const values = (weightsRaw.values as unknown[]).map(value => { const w = object(value, ["capability", "weight"]);
    if (!capabilities.includes(String(w.capability))) invalid(); return { capability: String(w.capability), weight: integer(w.weight, 1, 100) }; });
  const explicit = weightsRaw.mode === "EXPLICIT";
  if (new Set(values.map(v => v.capability)).size !== values.length || (explicit ? !values.length || values.reduce((n, v) => n + v.weight, 0) !== 100 : values.length)) invalid();
  const candidates = (raw.candidates as unknown[]).map((value): ResultCandidate => {
    const c = object(value, ["optionId", "product", "plan", "region", "deployment", "hardVerdict", "score"]);
    if (!["MANAGED", "SELF_HOSTED"].includes(String(c.deployment)) || !["ELIGIBLE", "EXCLUDED", "UNRESOLVED"].includes(String(c.hardVerdict))) invalid();
    let score: ResultCandidate["score"] = null;
    if (c.score !== null) { const bounds = object(c.score, ["lowerBound", "upperBound", "unknownWeight"]);
      score = { lowerBound: integer(bounds.lowerBound, 0, 100), upperBound: integer(bounds.upperBound, 0, 100), unknownWeight: integer(bounds.unknownWeight, 0, 100) };
      if (score.upperBound !== score.lowerBound + score.unknownWeight) invalid(); }
    if ((score !== null) !== (explicit && c.hardVerdict === "ELIGIBLE")) invalid();
    return { optionId: text(c.optionId, 100), product: text(c.product, 200), plan: text(c.plan, 200), region: text(c.region, 200),
      deployment: c.deployment as ResultCandidate["deployment"], hardVerdict: c.hardVerdict as ResultCandidate["hardVerdict"], score };
  });
  const eligible = candidates.filter(c => c.hardVerdict === "ELIGIBLE"), shortlist = raw.shortlist as string[];
  const status = eligible.length ? explicit && eligible.every(c => c.score?.unknownWeight === 0) ? "RANKED_SHORTLIST" : "UNRANKED_SHORTLIST"
    : candidates.some(c => c.hardVerdict === "UNRESOLVED") ? "NEEDS_INFORMATION" : "NO_ELIGIBLE_OPTIONS";
  if (new Set(candidates.map(c => c.optionId)).size !== candidates.length || raw.status !== status ||
      JSON.stringify(shortlist) !== JSON.stringify(eligible.map(c => c.optionId))) invalid();
  return { scope: "VERIFIED_ASSESSMENT_DECISION_SUMMARY", workspaceId, assessmentId, item: saved, evaluatedAt: date(raw.evaluatedAt),
    profileSchemaVersion: integer(raw.profileSchemaVersion, 1, 6), profileSha256: text(raw.profileSha256, 64, sha), policySha256: text(raw.policySha256, 64, sha),
    weights: { mode: explicit ? "EXPLICIT" : "NONE", values }, status, shortlist: [...shortlist], candidates, verificationGapCount: 22,
    historicalReplayVerified: true, externalSourceVerificationPerformed: false, configurationVerified: false, complianceVerified: false, decisionApproved: false };
}
export function resultReferenceQuery(reference: ResultReference, before = false): string {
  const ref = resultReference(reference);
  return new URLSearchParams(before ? { beforeResultId: ref.resultId, beforeVersion: String(ref.version), beforeResultSha256: ref.resultSha256 }
    : { version: String(ref.version), resultSha256: ref.resultSha256 }).toString();
}
export function resultQueryReference(query: Record<string, string | string[] | undefined>, before = false, resultId?: string): ResultReference | null {
  const keys = before ? ["beforeResultId", "beforeVersion", "beforeResultSha256"] : ["version", "resultSha256"];
  if (Object.keys(query).some(key => !keys.includes(key))) invalid();
  if (before && keys.every(key => query[key] === undefined)) return null;
  if (keys.some(key => typeof query[key] !== "string")) invalid();
  const version = String(query[keys[before ? 1 : 0]]);
  if (!/^[1-9][0-9]*$/.test(version)) invalid();
  return resultReference({ resultId: before ? query.beforeResultId : resultId,
    version: Number(version), resultSha256: query[keys[before ? 2 : 1]] });
}
