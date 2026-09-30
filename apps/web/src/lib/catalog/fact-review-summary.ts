import { factReviewFromCore, factReviewInput, type FactReviewReceipt } from "./fact-review.ts";
import type { CatalogProposalReview } from "./proposal-review.ts";
import type { CandidateEvidencePage } from "./evidence-review.ts";

export type FactReviewSummaryCounts = {
  noObservation: number; sourceSupportsClaim: number; sourceDoesNotSupportClaim: number; insufficientEvidence: number;
};
export type FactReviewSummaryPage = {
  proposalId: string; proposalVersion: number; proposalSha256: string; policyVersion: "catalog-fact-review-summary-1";
  reviewThroughNumber: number; factCount: number; counts: FactReviewSummaryCounts; offset: number;
  items: { optionId: string; factPath: string; latestObservation: FactReviewReceipt | null }[]; nextOffset: number | null;
  sourceVerificationPerformed: false; approvalGranted: false; catalogWritesPerformed: false; factTrustChanged: false;
};
function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function exact(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}
const count = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= 6800;

/** Bind whole-candidate counts and latest receipts to the independently validated evidence targets. */
export function factReviewSummaryFromCore(value: unknown,
  review: Pick<CatalogProposalReview, "proposalId" | "version" | "proposalSha256">,
  evidence: CandidateEvidencePage): FactReviewSummaryPage {
  const body = object(value), counts = object(body?.counts);
  const keys = ["proposalId", "proposalVersion", "proposalSha256", "policyVersion", "reviewThroughNumber", "factCount",
    "counts", "offset", "items", "nextOffset", "sourceVerificationPerformed", "approvalGranted", "catalogWritesPerformed", "factTrustChanged"];
  const countKeys = ["noObservation", "sourceSupportsClaim", "sourceDoesNotSupportClaim", "insufficientEvidence"] as const;
  if (!body || !exact(body, keys) || body.proposalId !== review.proposalId || body.proposalVersion !== review.version ||
      body.proposalSha256 !== review.proposalSha256 || body.policyVersion !== "catalog-fact-review-summary-1" ||
      !Number.isSafeInteger(body.reviewThroughNumber) || Number(body.reviewThroughNumber) < 0 ||
      body.factCount !== evidence.factCount || body.offset !== evidence.offset || body.nextOffset !== evidence.nextOffset ||
      body.sourceVerificationPerformed !== false || body.approvalGranted !== false || body.catalogWritesPerformed !== false ||
      body.factTrustChanged !== false || !counts || !exact(counts, [...countKeys]) || !countKeys.every(key => count(counts[key])) ||
      countKeys.reduce((sum, key) => sum + Number(counts[key]), 0) !== evidence.factCount ||
      !Array.isArray(body.items) || body.items.length !== evidence.items.length || body.items.length > 20) {
    throw new Error("Invalid fact-review summary");
  }
  const observed = evidence.factCount - Number(counts.noObservation), through = Number(body.reviewThroughNumber);
  if ((observed === 0) !== (through === 0) || observed > through) throw new Error("Invalid summary observation boundary");
  const seenIds = new Set<string>(), seenNumbers = new Set<number>();
  const pageCounts: FactReviewSummaryCounts = { noObservation: 0, sourceSupportsClaim: 0,
    sourceDoesNotSupportClaim: 0, insufficientEvidence: 0 };
  const items = body.items.map((value: unknown, index: number) => {
    const row = object(value), target = evidence.items[index];
    if (!row || !exact(row, ["optionId", "factPath", "latestObservation"]) ||
        row.optionId !== target.optionId || row.factPath !== target.path) throw new Error("Invalid summary fact target");
    if (row.latestObservation === null) {
      pageCounts.noObservation++;
      return { optionId: target.optionId, factPath: target.path, latestObservation: null };
    }
    const observation = object(row.latestObservation);
    const input = factReviewInput({ reviewId: observation?.reviewId, expectedVersion: review.version,
      expectedSha256: review.proposalSha256, optionId: target.optionId, factPath: target.path,
      verdict: observation?.verdict, confirmation: "MANUAL_SOURCE_REVIEW" });
    if (!input) throw new Error("Invalid summary observation");
    const receipt = factReviewFromCore(observation, review.proposalId, input);
    if (receipt.reviewNumber > through || seenIds.has(receipt.reviewId) || seenNumbers.has(receipt.reviewNumber)) {
      throw new Error("Invalid summary observation identity");
    }
    seenIds.add(receipt.reviewId); seenNumbers.add(receipt.reviewNumber);
    const key = { SOURCE_SUPPORTS_CLAIM: "sourceSupportsClaim", SOURCE_DOES_NOT_SUPPORT_CLAIM: "sourceDoesNotSupportClaim",
      INSUFFICIENT_EVIDENCE: "insufficientEvidence" }[receipt.verdict] as keyof FactReviewSummaryCounts;
    pageCounts[key]++;
    return { optionId: target.optionId, factPath: target.path, latestObservation: receipt };
  });
  if (countKeys.some(key => pageCounts[key] > Number(counts[key])) ||
      (evidence.items.length === evidence.factCount && (countKeys.some(key => pageCounts[key] !== counts[key]) ||
        Math.max(0, ...seenNumbers) !== through))) throw new Error("Invalid summary page counts");
  return { ...body, items } as FactReviewSummaryPage;
}
