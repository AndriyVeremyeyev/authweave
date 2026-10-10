/** Transient, exact-result-bound transport; no browser fact evaluation or result recording. */
import { resultCapabilities, resultReference, resultSummaryFromCore, resultUuid, type ResultReference, type ResultSummary } from "./decision-results.ts";
import { resultScoringFromCore, type ResultAdvice, type AdviceCandidate } from "./decision-advice.ts";
import { boundedPrerequisiteText } from "./architecture-prerequisites.ts";
import { readRecordingForm } from "./decision-recording.ts";

export const sensitivityByteLimit = 1_048_576;
export type SensitivityRequest = { schemaVersion: 1; reference: ResultReference; weights: ResultSummary["weights"] };
export type ResultScoring = Pick<ResultSummary, "weights" | "status" | "shortlist"> & Pick<ResultAdvice, "candidates" | "rankGroups">;
export type ResultSensitivity = { scope: "VERIFIED_ASSESSMENT_WEIGHT_SENSITIVITY"; summary: ResultSummary;
  before: ResultScoring; after: ResultScoring; writesPerformed: false };
export class InvalidSensitivity extends Error {}
const fail = (): never => { throw new InvalidSensitivity("Invalid pinned weight comparison"); };
function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value, k))) fail();
  return value as Record<string, unknown>;
}
export function sensitivityRequest(value: unknown): SensitivityRequest {
  const r = object(value, ["schemaVersion", "reference", "weights"]), w = object(r.weights, ["mode", "values"]);
  if (r.schemaVersion !== 1 || !Array.isArray(w.values) || w.values.length > 9 || !["NONE", "EXPLICIT"].includes(String(w.mode))) fail();
  const values = (w.values as unknown[]).map(v => {
    const x = object(v, ["capability", "weight"]);
    if (!resultCapabilities.some(c => c === x.capability) || !Number.isInteger(x.weight) || Number(x.weight) < 1 || Number(x.weight) > 100) fail();
    return { capability: String(x.capability), weight: Number(x.weight) };
  });
  if (new Set(values.map(v => v.capability)).size !== values.length ||
      (w.mode === "NONE" ? values.length !== 0 : !values.length || values.reduce((n, v) => n + v.weight, 0) !== 100)) fail();
  let reference: ResultReference; try { reference = resultReference(r.reference); } catch { return fail(); }
  return { schemaVersion: 1, reference, weights: { mode: w.mode as "NONE" | "EXPLICIT", values } };
}
export function sensitivityForm(params: URLSearchParams, resultId: string): SensitivityRequest {
  const base = ["version", "resultSha256", "weightMode"];
  if (base.some(k => !params.has(k)) || [...params.keys()].some(k => params.getAll(k).length !== 1 ||
      !base.includes(k) && !resultCapabilities.some(c => k === `weight_${c}`))) fail();
  const integer = (v: string) => { if (!/^[1-9][0-9]*$/.test(v) || !Number.isSafeInteger(Number(v))) fail(); return Number(v); };
  return sensitivityRequest({ schemaVersion: 1, reference: { resultId, version: integer(params.get("version")!), resultSha256: params.get("resultSha256") },
    weights: { mode: params.get("weightMode"), values: resultCapabilities.filter(c => params.has(`weight_${c}`))
      .map(c => ({ capability: c, weight: integer(params.get(`weight_${c}`)!) })) } });
}
export async function readSensitivityForm(request: Request): Promise<URLSearchParams> {
  try { return await readRecordingForm(request); } catch { return fail(); }
}
function scoring(value: unknown, summary: ResultSummary): ResultScoring {
  const r = object(value, ["weights", "status", "shortlist", "candidates", "rankGroups"]);
  if (!Array.isArray(r.candidates) || r.candidates.length > 100) fail();
  const bounds = (r.candidates as unknown[]).map(value => {
    const c = object(value, ["hardChecks", "score"]), h = c.hardChecks as AdviceCandidate["hardChecks"];
    if (!h || typeof h !== "object") fail();
    return { optionId: h.optionId, product: h.product, plan: h.plan, region: h.region, deployment: h.deployment, hardVerdict: h.hardVerdict,
      score: c.score === null ? null : { lowerBound: (c.score as AdviceCandidate["score"])?.lowerBound,
        upperBound: (c.score as AdviceCandidate["score"])?.upperBound, unknownWeight: (c.score as AdviceCandidate["score"])?.unknownWeight } };
  });
  // Reuse consistency guards internally. Never return or label the new comparison as a saved summary.
  const checked = resultSummaryFromCore({ ...summary, weights: r.weights, status: r.status, shortlist: r.shortlist, candidates: bounds },
    summary.workspaceId, summary.assessmentId, summary.item.reference);
  return { weights: checked.weights, status: checked.status, shortlist: checked.shortlist,
    ...resultScoringFromCore({ candidates: r.candidates, rankGroups: r.rankGroups }, checked) };
}
export function savedScoring(advice: Pick<ResultAdvice, "summary" | "candidates" | "rankGroups">): ResultScoring {
  return { weights: advice.summary.weights, status: advice.summary.status, shortlist: advice.summary.shortlist,
    candidates: advice.candidates, rankGroups: advice.rankGroups };
}
const canonical = (value: unknown): unknown => Array.isArray(value) ? value.map(canonical) : value && typeof value === "object"
  ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([k, v]) => [k, canonical(v)])) : value;
const equal = (a: unknown, b: unknown) => JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
export function sensitivityFromCore(value: unknown, workspace: string, assessment: string, input: SensitivityRequest,
  baseline?: Pick<ResultAdvice, "summary" | "candidates" | "rankGroups">): ResultSensitivity {
  const request = sensitivityRequest(input), r = object(value, ["scope", "summary", "before", "after", "writesPerformed"]);
  if (r.scope !== "VERIFIED_ASSESSMENT_WEIGHT_SENSITIVITY" || r.writesPerformed !== false) fail();
  const summary = resultSummaryFromCore(r.summary, workspace, assessment, request.reference);
  const before = scoring(r.before, summary), after = scoring(r.after, summary);
  if (!equal(before.weights, summary.weights) || before.status !== summary.status || !equal(before.shortlist, summary.shortlist) ||
      !equal(after.weights, request.weights) || before.weights.mode !== after.weights.mode ||
      !equal(before.weights.values.map(w => w.capability).sort(), after.weights.values.map(w => w.capability).sort()) ||
      !equal(before.candidates.map(c => c.hardChecks), after.candidates.map(c => c.hardChecks)) || !equal(before.shortlist, after.shortlist)) fail();
  const original = savedScoring({ summary, ...resultScoringFromCore({ candidates: before.candidates, rankGroups: before.rankGroups }, summary) });
  if (!equal(before, original)) fail();
  for (let i = 0; i < before.candidates.length; i++) {
    const fixed = (c: AdviceCandidate) => c.score?.contributions.map(({ weight, earnedPoints, ...fact }) => { void weight; void earnedPoints; return fact; }) ?? null;
    if (!equal(fixed(before.candidates[i]), fixed(after.candidates[i]))) fail();
  }
  if (baseline && (!equal(summary, baseline.summary) || !equal(before, savedScoring(baseline)))) fail();
  return { scope: "VERIFIED_ASSESSMENT_WEIGHT_SENSITIVITY", summary, before, after, writesPerformed: false };
}
export async function postSensitivity(assessment: string, body: string, baseline: Pick<ResultAdvice, "summary" | "candidates" | "rankGroups">,
  fetcher: typeof fetch = fetch): Promise<ResultSensitivity | null> {
  try {
    if (!resultUuid.test(assessment)) fail();
    const input = sensitivityForm(new URLSearchParams(body), baseline.summary.item.reference.resultId), signal = AbortSignal.timeout(30_000);
    const response = await fetcher(`/api/assessments/${assessment}/decision-results/${input.reference.resultId}/sensitivity`, {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" }, body,
      credentials: "same-origin", mode: "same-origin", cache: "no-store", redirect: "error", signal });
    if (response.status !== 200 || response.redirected || response.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
      void response.body?.cancel().catch(() => {}); return null;
    }
    return sensitivityFromCore(JSON.parse(await boundedPrerequisiteText(response, sensitivityByteLimit, signal)), baseline.summary.workspaceId, assessment, input, baseline);
  } catch { return null; }
}
