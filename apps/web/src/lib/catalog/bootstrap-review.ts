import { claimFromCore, type CandidateEvidenceItem } from "./evidence-review.ts";

export const BOOTSTRAP_BODY_BYTES = 4 * 1024 * 1024;
export const BOOTSTRAP_CANDIDATE_BYTES = 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const IDENTIFIER = /^[a-z0-9][a-z0-9.-]{0,99}$/;
export const bootstrapVerdicts = ["SOURCE_SUPPORTS_CLAIM", "SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE"] as const;
export type BootstrapVerdict = (typeof bootstrapVerdicts)[number];
export type BootstrapCandidate = Record<string, unknown> & { catalogVersion: string; options: unknown[] };
export type BootstrapObservation = { optionId: string; factPath: string; verdict: BootstrapVerdict };
export type BootstrapReviewInput = {
  schemaVersion: 1; reviewId: string; expectedCandidateSha256: string; candidate: BootstrapCandidate;
  observations: BootstrapObservation[]; confirmation: "MANUAL_BOOTSTRAP_SOURCE_REVIEW";
};
export type BootstrapPreparation = {
  reviewId: string; candidateSha256: string; candidate: BootstrapCandidate; evaluatedAt: string;
  facts: CandidateEvidenceItem[];
};
export type BootstrapReceipt = {
  reviewId: string; candidateSha256: string; reviewSha256: string; catalogVersion: string;
  factCount: number; counts: { supporting: number; contradicting: number; insufficient: number };
  recordedAt: string; policyVersion: "catalog-bootstrap-source-review-1"; kind: "HUMAN_BOOTSTRAP_SOURCE_REVIEW";
  sourceVerificationPerformed: false; approvalGranted: false; catalogWritesPerformed: false; factTrustChanged: false;
};
function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function exact(body: Record<string, unknown> | null, keys: string[]): boolean {
  return !!body && Object.keys(body).length === keys.length && keys.every(key => Object.hasOwn(body, key));
}
function text(value: unknown, max: number): value is string {
  return typeof value === "string" && value.trim().length > 0 && [...value].length <= max;
}
function instant(value: unknown): value is string {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
}
function count(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= 6800;
}
export function bootstrapReceiptReference(id: unknown, digest: unknown): { id: string; digest: string } | null {
  return typeof id === "string" && UUID.test(id) && typeof digest === "string" && SHA256.test(digest) ? { id, digest } : null;
}
export function bootstrapReceiptHref(receipt: BootstrapReceipt): string {
  return `/catalog/bootstrap?${new URLSearchParams({ reviewId: receipt.reviewId, expectedSha256: receipt.reviewSha256 })}`;
}

// Core owns full JSON Schema/semantic validation and typed canonicalization. This is a bounded display/input guard.
export function bootstrapCandidate(value: unknown): BootstrapCandidate | null {
  const body = object(value);
  if (!exact(body, ["schemaVersion", "kind", "catalogVersion", "options"]) || body!.schemaVersion !== 1 ||
      body!.kind !== "PROVIDER_CATALOG_DRAFT" || typeof body!.catalogVersion !== "string" ||
      !IDENTIFIER.test(body!.catalogVersion) || !Array.isArray(body!.options) ||
      body!.options.length < 1 || body!.options.length > 100) return null;
  try {
    if (new TextEncoder().encode(JSON.stringify(body)).length > BOOTSTRAP_CANDIDATE_BYTES) return null;
  } catch { return null; }
  return body as BootstrapCandidate;
}

function targets(candidate: BootstrapCandidate): CandidateEvidenceItem[] {
  const result: CandidateEvidenceItem[] = [];
  const ids = new Set<string>();
  for (const value of candidate.options) {
    const option = object(value);
    if (!option || typeof option.id !== "string" || !IDENTIFIER.test(option.id) || ids.has(option.id) ||
        typeof option.providerId !== "string" || !IDENTIFIER.test(option.providerId) ||
        !["product", "plan", "region", "configuration"].every(key => text(option[key], 120)) ||
        !["MANAGED", "SELF_HOSTED"].includes(String(option.deployment))) throw new Error("Invalid bootstrap option");
    ids.add(option.id);
    const scope = { providerId: option.providerId, product: option.product as string, plan: option.plan as string,
      deployment: option.deployment as "MANAGED" | "SELF_HOSTED", region: option.region as string, configuration: option.configuration as string };
    const start = result.length;
    const add = (path: string, value: unknown) => {
      const fact = object(value); const evidence = object(fact?.evidence);
      if (!fact || !Array.isArray(fact.conditions) || fact.conditions.length > 10 ||
          !fact.conditions.every(v => text(v, 500)) || new Set(fact.conditions).size !== fact.conditions.length ||
          !exact(evidence, ["sourceUrl", "observedAt", "summary"]) || !text(evidence!.sourceUrl, 2048) ||
          !/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]+)?(?:[/?#][^\s]*)?$/.test(evidence!.sourceUrl) ||
          !instant(evidence!.observedAt) || !text(evidence!.summary, 1000)) throw new Error("Invalid bootstrap evidence");
      let claim;
      if (path.startsWith("facts.")) claim = { kind: "CAPABILITY", availability: fact.availability };
      else if (path.startsWith("compatibility.")) claim = { kind: "COMPATIBILITY", support: fact.support };
      else if (path.startsWith("residency.")) claim = { kind: "RESIDENCY", coverage: fact.coverage, storageCountries: fact.storageCountries };
      else claim = { kind: "AUTHENTICATION_CONTROL", availability: fact.availability, enforcement: fact.enforcement };
      result.push({ optionId: option.id as string, path, claim: claimFromCore(claim, path), scope,
        evidenceStatus: "UNREVIEWED", freshness: "CURRENT", conditions: [...fact.conditions].sort(),
        evidence: { sourceUrl: evidence!.sourceUrl, observedAt: evidence!.observedAt, summary: evidence!.summary } });
      if (result.length > 6800) throw new Error("Too many bootstrap facts");
    };
    const entries = (value: unknown) => {
      const map = object(value);
      if (!map) throw new Error("Invalid bootstrap fact map");
      return Object.entries(map);
    };
    for (const [key, fact] of entries(option.facts)) add(`facts.${key}`, fact);
    for (const [family, map] of entries(option.compatibility)) {
      for (const [key, fact] of entries(map)) add(`compatibility.${family}.${key}`, fact);
    }
    for (const [key, fact] of entries(option.residency)) add(`residency.${key}`, fact);
    for (const [client, populations] of entries(option.authenticationControls)) {
      for (const [population, controls] of entries(populations)) {
        for (const [control, fact] of entries(controls)) add(`authenticationControls.${client}.${population}.${control}`, fact);
      }
    }
    if (result.length === start) throw new Error("No bootstrap facts");
  }
  return result.sort((a, b) => a.optionId < b.optionId ? -1 : a.optionId > b.optionId ? 1 : a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
}

/** Use Core's digest, fact set and current date assessment; never hash browser JSON or fetch source URLs. */
export function bootstrapPreparationFromCore(value: unknown, candidate: BootstrapCandidate, reviewId: string): BootstrapPreparation {
  const body = object(value);
  const facts = targets(candidate);
  if (!UUID.test(reviewId) || !body || body.scope !== "CATALOG_DRAFT_VALIDATION" || body.policyVersion !== "catalog-draft-validation-1" ||
      body.canonicalizationVersion !== "catalog-draft-canonical-json-1" || body.catalogVersion !== candidate.catalogVersion ||
      body.catalogSchemaVersion !== 1 || body.status !== "VALID_DRAFT" || !instant(body.evaluatedAt) ||
      typeof body.contentSha256 !== "string" || !SHA256.test(body.contentSha256) ||
      body.sourceVerificationPerformed !== false || body.approvalGranted !== false || body.writesPerformed !== false ||
      body.evaluationReady !== false || body.optionCount !== candidate.options.length || body.factCount !== facts.length ||
      !Array.isArray(body.issues) || body.issues.length !== 0 || !Array.isArray(body.facts) || body.facts.length !== facts.length) {
    throw new Error("Invalid Core bootstrap preparation");
  }
  for (let i = 0; i < facts.length; i++) {
    const row = object(body.facts[i]); const evidence = object(row?.evidence); const expected = facts[i];
    if (!row || row.optionId !== expected.optionId || row.path !== expected.path || row.evidenceStatus !== "UNREVIEWED" ||
        !["CURRENT", "STALE", "FUTURE"].includes(String(row.freshness)) ||
        JSON.stringify(row.conditions) !== JSON.stringify(expected.conditions) || !evidence ||
        evidence.sourceUrl !== expected.evidence.sourceUrl || evidence.summary !== expected.evidence.summary ||
        !instant(evidence.observedAt) || Date.parse(evidence.observedAt) !== Date.parse(expected.evidence.observedAt)) {
      throw new Error("Core bootstrap evidence does not match candidate");
    }
    expected.freshness = row.freshness as CandidateEvidenceItem["freshness"];
    expected.evidence.observedAt = evidence.observedAt;
  }
  return { reviewId, candidateSha256: body.contentSha256, candidate, evaluatedAt: body.evaluatedAt, facts };
}

export function bootstrapReviewInput(value: unknown): BootstrapReviewInput | null {
  const body = object(value);
  if (!exact(body, ["schemaVersion", "reviewId", "expectedCandidateSha256", "candidate", "observations", "confirmation"]) ||
      body!.schemaVersion !== 1 || typeof body!.reviewId !== "string" || !UUID.test(body!.reviewId) ||
      typeof body!.expectedCandidateSha256 !== "string" || !SHA256.test(body!.expectedCandidateSha256) ||
      body!.confirmation !== "MANUAL_BOOTSTRAP_SOURCE_REVIEW") return null;
  const candidate = bootstrapCandidate(body!.candidate);
  if (!candidate || !Array.isArray(body!.observations) || body!.observations.length < 1 || body!.observations.length > 6800) return null;
  try {
    const facts = targets(candidate);
    const keys = new Set(facts.map(fact => `${fact.optionId}\0${fact.path}`));
    if (facts.length !== body!.observations.length) return null;
    for (const value of body!.observations) {
      const row = object(value);
      if (!exact(row, ["optionId", "factPath", "verdict"]) || typeof row!.optionId !== "string" || typeof row!.factPath !== "string" ||
          !bootstrapVerdicts.includes(row!.verdict as BootstrapVerdict) || !keys.delete(`${row!.optionId}\0${row!.factPath}`)) return null;
    }
    if (keys.size) return null;
  } catch { return null; }
  return body as BootstrapReviewInput;
}

export function bootstrapReviewSubmission(prepared: BootstrapPreparation, verdicts: Record<string, string>, confirmed: boolean): BootstrapReviewInput | null {
  if (!confirmed) return null;
  return bootstrapReviewInput({ schemaVersion: 1, reviewId: prepared.reviewId, expectedCandidateSha256: prepared.candidateSha256,
    candidate: prepared.candidate, confirmation: "MANUAL_BOOTSTRAP_SOURCE_REVIEW", observations: prepared.facts.map(fact =>
      ({ optionId: fact.optionId, factPath: fact.path, verdict: verdicts[`${fact.optionId}\0${fact.path}`] })) });
}

export function bootstrapReceiptFromCore(value: unknown, reference: { id: string; digest?: string }, input?: BootstrapReviewInput): BootstrapReceipt {
  const body = object(value); const counts = object(body?.counts);
  if (!exact(body, ["reviewId", "candidateSha256", "reviewSha256", "catalogVersion", "factCount", "counts", "recordedAt",
    "policyVersion", "kind", "sourceVerificationPerformed", "approvalGranted", "catalogWritesPerformed", "factTrustChanged"]) ||
      body!.reviewId !== reference.id || typeof body!.candidateSha256 !== "string" || !SHA256.test(body!.candidateSha256) ||
      typeof body!.reviewSha256 !== "string" || !SHA256.test(body!.reviewSha256) ||
      (reference.digest !== undefined && body!.reviewSha256 !== reference.digest) ||
      typeof body!.catalogVersion !== "string" || !IDENTIFIER.test(body!.catalogVersion) || !count(body!.factCount) || body!.factCount === 0 ||
      !exact(counts, ["supporting", "contradicting", "insufficient"]) || !count(counts!.supporting) || !count(counts!.contradicting) ||
      !count(counts!.insufficient) || counts!.supporting + counts!.contradicting + counts!.insufficient !== body!.factCount ||
      !instant(body!.recordedAt) || body!.policyVersion !== "catalog-bootstrap-source-review-1" || body!.kind !== "HUMAN_BOOTSTRAP_SOURCE_REVIEW" ||
      body!.sourceVerificationPerformed !== false || body!.approvalGranted !== false || body!.catalogWritesPerformed !== false || body!.factTrustChanged !== false) {
    throw new Error("Invalid bootstrap receipt");
  }
  if (input && (body!.candidateSha256 !== input.expectedCandidateSha256 || body!.catalogVersion !== input.candidate.catalogVersion ||
      body!.factCount !== input.observations.length || counts!.supporting !== input.observations.filter(o => o.verdict === "SOURCE_SUPPORTS_CLAIM").length ||
      counts!.contradicting !== input.observations.filter(o => o.verdict === "SOURCE_DOES_NOT_SUPPORT_CLAIM").length ||
      counts!.insufficient !== input.observations.filter(o => o.verdict === "INSUFFICIENT_EVIDENCE").length)) throw new Error("Unbound bootstrap receipt");
  return body as BootstrapReceipt;
}

/** Bound streamed bytes even without Content-Length; reject malformed UTF-8. */
export async function boundedBootstrapJson(request: Request | Response): Promise<unknown> {
  const length = request.headers.get("content-length");
  if (length && (!/^[0-9]+$/.test(length) || Number(length) > BOOTSTRAP_BODY_BYTES)) throw new RangeError("Body too large");
  const reader = request.body?.getReader();
  if (!reader) throw new SyntaxError("Missing body");
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > BOOTSTRAP_BODY_BYTES) { await reader.cancel(); throw new RangeError("Body too large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}
