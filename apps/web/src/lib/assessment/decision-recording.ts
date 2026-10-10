import { resultCapabilities, resultReference, resultUuid, type ResultItem, type ResultReference, type ResultSummary } from "./decision-results.ts";
import { boundedPrerequisiteText } from "./architecture-prerequisites.ts";

export const recordingBodyLimit = 16_384;
export const recordingCapabilities = resultCapabilities;
export type RecordingRequest = { schemaVersion: 1; resultId: string; expectedAssessmentVersion: number;
  catalog: ResultItem["catalog"]; previousResult: ResultReference | null;
  weights: ResultSummary["weights"]; confirmation: "RECORD_DECISION_RESULT" | "REEVALUATE_DECISION_RESULT" };
export class InvalidRecording extends Error {}
const invalid = (): never => { throw new InvalidRecording("Use an explicit pinned recording request"); };
function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) invalid();
  return value as Record<string, unknown>;
}
export function recordingRequest(value: unknown): RecordingRequest {
  const raw = object(value, ["schemaVersion", "resultId", "expectedAssessmentVersion", "catalog", "previousResult", "weights", "confirmation"]);
  if (raw.schemaVersion !== 1 || typeof raw.resultId !== "string" || !resultUuid.test(raw.resultId) ||
      !Number.isSafeInteger(raw.expectedAssessmentVersion) || Number(raw.expectedAssessmentVersion) < 0) invalid();
  const catalog = object(raw.catalog, ["snapshotId", "catalogVersion", "snapshotSha256"]);
  if (typeof catalog.snapshotId !== "string" || !resultUuid.test(catalog.snapshotId) || typeof catalog.catalogVersion !== "string" ||
      !/^[a-z0-9][a-z0-9.-]{0,99}$/.test(catalog.catalogVersion) || typeof catalog.snapshotSha256 !== "string" || !/^[a-f0-9]{64}$/.test(catalog.snapshotSha256)) invalid();
  let previousResult: ResultReference | null = null;
  try { if (raw.previousResult !== null) previousResult = resultReference(raw.previousResult); } catch { invalid(); }
  if (previousResult && (previousResult.resultId === raw.resultId || previousResult.version === Number.MAX_SAFE_INTEGER)) invalid();
  const confirmation = previousResult ? "REEVALUATE_DECISION_RESULT" : "RECORD_DECISION_RESULT";
  if (raw.confirmation !== confirmation) invalid();
  const weights = object(raw.weights, ["mode", "values"]);
  if (!["NONE", "EXPLICIT"].includes(String(weights.mode)) || !Array.isArray(weights.values) || weights.values.length > recordingCapabilities.length) invalid();
  const values = (weights.values as unknown[]).map(value => {
    const weight = object(value, ["capability", "weight"]);
    if (!recordingCapabilities.some(c => c === weight.capability) || !Number.isInteger(weight.weight) || Number(weight.weight) < 1 || Number(weight.weight) > 100) invalid();
    return { capability: String(weight.capability), weight: Number(weight.weight) };
  });
  if (new Set(values.map(v => v.capability)).size !== values.length ||
      (weights.mode === "NONE" ? values.length !== 0 : values.length === 0 || values.reduce((n, v) => n + v.weight, 0) !== 100)) invalid();
  return { schemaVersion: 1, resultId: raw.resultId as string, expectedAssessmentVersion: raw.expectedAssessmentVersion as number,
    catalog: { snapshotId: catalog.snapshotId as string, catalogVersion: catalog.catalogVersion as string, snapshotSha256: catalog.snapshotSha256 as string },
    previousResult, weights: { mode: weights.mode as "NONE" | "EXPLICIT", values }, confirmation };
}
export function parseRecordingForm(params: URLSearchParams): RecordingRequest {
  const base = ["resultId", "expectedAssessmentVersion", "snapshotId", "catalogVersion", "snapshotSha256", "previousResultId", "previousVersion", "previousResultSha256", "weightMode", "confirmation"];
  if ([...params.keys()].some(key => !base.includes(key) && !recordingCapabilities.some(c => key === `weight_${c}`)) ||
      [...params.keys()].some(key => params.getAll(key).length !== 1) || base.some(key => !params.has(key))) invalid();
  const number = (value: string, positive = false) => {
    if (!(positive ? /^[1-9][0-9]*$/ : /^(0|[1-9][0-9]*)$/).test(value) || !Number.isSafeInteger(Number(value))) invalid();
    return Number(value);
  };
  const previous = [params.get("previousResultId")!, params.get("previousVersion")!, params.get("previousResultSha256")!];
  if (previous.some(Boolean) && !previous.every(Boolean)) invalid();
  return recordingRequest({ schemaVersion: 1, resultId: params.get("resultId"), expectedAssessmentVersion: number(params.get("expectedAssessmentVersion")!),
    catalog: { snapshotId: params.get("snapshotId"), catalogVersion: params.get("catalogVersion"), snapshotSha256: params.get("snapshotSha256") },
    previousResult: previous.every(Boolean) ? { resultId: previous[0], version: number(previous[1], true), resultSha256: previous[2] } : null,
    weights: { mode: params.get("weightMode"), values: recordingCapabilities.filter(c => params.has(`weight_${c}`))
      .map(c => ({ capability: c, weight: number(params.get(`weight_${c}`)!, true) })) }, confirmation: params.get("confirmation") });
}
/** Bound bytes before decoding/parsing. No chunked or multi-byte bypass, and no body logging. */
export async function readRecordingForm(request: Request): Promise<URLSearchParams> {
  const length = request.headers.get("content-length");
  if (length !== null && (!/^(0|[1-9][0-9]*)$/.test(length) || Number(length) > recordingBodyLimit)) invalid();
  const reader = request.body?.getReader(); if (!reader) return invalid();
  const decoder = new TextDecoder("utf-8", { fatal: true }); let bytes = 0, body = "";
  try {
    while (true) {
      const next = await reader.read(); if (next.done) break;
      bytes += next.value.byteLength; if (bytes > recordingBodyLimit) invalid();
      body += decoder.decode(next.value, { stream: true });
    }
    body += decoder.decode(); return new URLSearchParams(body);
  } catch { await reader.cancel().catch(() => {}); return invalid(); }
  finally { reader.releaseLock(); }
}
export function recordingSummaryMatches(summary: ResultSummary, input: RecordingRequest): boolean {
  return summary.item.reference.resultId === input.resultId && summary.item.reference.version === (input.previousResult?.version ?? 0) + 1 &&
    summary.item.assessmentVersion === input.expectedAssessmentVersion && JSON.stringify(summary.item.catalog) === JSON.stringify(input.catalog) &&
    JSON.stringify(summary.item.previousResult) === JSON.stringify(input.previousResult) && JSON.stringify(summary.weights) === JSON.stringify(input.weights);
}
export type RecordingAck = { scope: "OWNED_ASSESSMENT_RESULT_WRITE_ACK"; assessmentId: string;
  reference: ResultReference; created: boolean; historicalReplayVerified: true };
export function recordingAck(value: unknown, assessmentId: string, input: RecordingRequest): RecordingAck {
  const raw = object(value, ["scope", "assessmentId", "reference", "created", "historicalReplayVerified"]);
  let reference: ResultReference;
  try { reference = resultReference(raw.reference); } catch { invalid(); }
  if (raw.scope !== "OWNED_ASSESSMENT_RESULT_WRITE_ACK" || raw.assessmentId !== assessmentId || typeof raw.created !== "boolean" || raw.historicalReplayVerified !== true ||
      reference!.resultId !== input.resultId || reference!.version !== (input.previousResult?.version ?? 0) + 1) invalid();
  return { scope: "OWNED_ASSESSMENT_RESULT_WRITE_ACK", assessmentId, reference: reference!, created: raw.created as boolean, historicalReplayVerified: true };
}
export type RecordingOutcome = { outcome: "saved"; ack: RecordingAck } | { outcome: "denied" } | { outcome: "uncertain"; conflict: boolean };
export async function postRecording(assessmentId: string, body: string, fetcher: typeof fetch = fetch): Promise<RecordingOutcome> {
  let input: RecordingRequest;
  try { if (!resultUuid.test(assessmentId)) invalid(); input = parseRecordingForm(new URLSearchParams(body)); }
  catch { return { outcome: "denied" }; }
  try {
    const signal = AbortSignal.timeout(30_000);
    const response = await fetcher(`/api/assessments/${assessmentId}/decision-results`, { method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" }, body,
      credentials: "same-origin", mode: "same-origin", cache: "no-store", redirect: "error", signal });
    if (response.redirected) return { outcome: "uncertain", conflict: false };
    if ([400, 401, 403, 404, 413, 415].includes(response.status)) return { outcome: "denied" };
    // Summary verification can fail after the original write committed. A 409 is not proof of no write.
    if (response.status === 409) return { outcome: "uncertain", conflict: true };
    if (![200, 201].includes(response.status) || response.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") return { outcome: "uncertain", conflict: false };
    const ack = recordingAck(JSON.parse(await boundedPrerequisiteText(response, 4096, signal)), assessmentId, input);
    if (ack.created !== (response.status === 201)) return { outcome: "uncertain", conflict: false };
    return { outcome: "saved", ack };
  } catch { return { outcome: "uncertain", conflict: false }; }
}
