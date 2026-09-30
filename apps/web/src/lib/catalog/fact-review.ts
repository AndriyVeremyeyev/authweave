// These observations never promote evidence trust or approve a catalog revision.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const FACT_PATH = /^(facts\.[A-Z0-9_]+|compatibility\.(applications|clients|populations|tenancy|membership)\.[A-Z0-9_]+|residency\.[A-Z0-9_]+|authenticationControls\.[A-Z0-9_]+\.[A-Z0-9_]+\.[A-Z0-9_]+)$/;
const verdicts = ["SOURCE_SUPPORTS_CLAIM", "SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE"] as const;
export type FactReviewInput = {
  reviewId: string; expectedVersion: number; expectedSha256: string;
  optionId: string; factPath: string; verdict: (typeof verdicts)[number];
  confirmation: "MANUAL_SOURCE_REVIEW";
};
export type FactReviewReceipt = {
  reviewId: string; proposalId: string; proposalVersion: number; proposalSha256: string;
  reviewNumber: number; optionId: string; factPath: string; verdict: FactReviewInput["verdict"];
  recordedAt: string; kind: "HUMAN_SOURCE_REVIEW_OBSERVATION";
  sourceVerificationPerformed: false; approvalGranted: false; catalogWritesPerformed: false; factTrustChanged: false;
};

export function factReviewInput(value: unknown): FactReviewInput | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  const keys = ["reviewId", "expectedVersion", "expectedSha256", "optionId", "factPath", "verdict", "confirmation"];
  if (Object.keys(body).length !== keys.length || !keys.every(key => Object.hasOwn(body, key)) ||
      typeof body.reviewId !== "string" || !UUID.test(body.reviewId) ||
      !Number.isSafeInteger(body.expectedVersion) || Number(body.expectedVersion) < 0 ||
      typeof body.expectedSha256 !== "string" || !SHA256.test(body.expectedSha256) ||
      typeof body.optionId !== "string" || !/^[a-z0-9][a-z0-9.-]{0,99}$/.test(body.optionId) ||
      typeof body.factPath !== "string" || body.factPath.length > 200 || !FACT_PATH.test(body.factPath) ||
      typeof body.verdict !== "string" || !verdicts.some(verdict => verdict === body.verdict) ||
      body.confirmation !== "MANUAL_SOURCE_REVIEW") return null;
  return body as FactReviewInput;
}

export function factReviewFromCore(value: unknown, proposalId: string, input: FactReviewInput): FactReviewReceipt {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid fact review receipt");
  const body = value as Record<string, unknown>;
  const keys = ["reviewId", "proposalId", "proposalVersion", "proposalSha256", "reviewNumber", "optionId", "factPath",
    "verdict", "recordedAt", "kind", "sourceVerificationPerformed", "approvalGranted", "catalogWritesPerformed", "factTrustChanged"];
  if (Object.keys(body).length !== keys.length || !keys.every(key => Object.hasOwn(body, key)) ||
      body.reviewId !== input.reviewId || body.proposalId !== proposalId || body.proposalVersion !== input.expectedVersion ||
      body.proposalSha256 !== input.expectedSha256 || body.optionId !== input.optionId || body.factPath !== input.factPath ||
      body.verdict !== input.verdict || !Number.isSafeInteger(body.reviewNumber) || Number(body.reviewNumber) < 1 ||
      typeof body.recordedAt !== "string" || !Number.isFinite(Date.parse(body.recordedAt)) ||
      body.kind !== "HUMAN_SOURCE_REVIEW_OBSERVATION" || body.sourceVerificationPerformed !== false ||
      body.approvalGranted !== false || body.catalogWritesPerformed !== false || body.factTrustChanged !== false) {
    throw new Error("Invalid fact review receipt");
  }
  return body as FactReviewReceipt;
}
