import { factReviewFromCore, factReviewInput, type FactReviewReceipt } from "./fact-review.ts";
import type { CatalogProposalReview } from "./proposal-review.ts";

export type FactReviewHistoryCursor = { version: number; afterReviewNumber: number };
export type FactReviewHistoryPage = {
  proposalId: string; proposalVersion: number; proposalSha256: string; afterReviewNumber: number;
  items: FactReviewReceipt[]; nextAfterReviewNumber: number | null;
};

export function factReviewHistoryCursorFromQuery(version: string | string[] | undefined,
  after: string | string[] | undefined): FactReviewHistoryCursor | null {
  if (version === undefined && after === undefined) return null;
  const integer = (value: unknown): value is string => typeof value === "string" &&
    /^(0|[1-9][0-9]*)$/.test(value) && Number.isSafeInteger(Number(value));
  if (!integer(version) || !integer(after)) throw new RangeError("Invalid review history cursor");
  return { version: Number(version), afterReviewNumber: Number(after) };
}

export function factReviewHistoryFromCore(value: unknown,
  review: Pick<CatalogProposalReview, "proposalId" | "version" | "proposalSha256">,
  afterReviewNumber: number): FactReviewHistoryPage {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid review history");
  const body = value as Record<string, unknown>;
  const keys = ["proposalId", "proposalVersion", "proposalSha256", "afterReviewNumber", "items", "nextAfterReviewNumber"];
  if (!Number.isSafeInteger(afterReviewNumber) || afterReviewNumber < 0 ||
      Object.keys(body).length !== keys.length || !keys.every(key => Object.hasOwn(body, key)) ||
      body.proposalId !== review.proposalId || body.proposalVersion !== review.version ||
      body.proposalSha256 !== review.proposalSha256 || body.afterReviewNumber !== afterReviewNumber ||
      !Array.isArray(body.items) || body.items.length > 20) throw new Error("Invalid review history");
  const ids = new Set<string>();
  let previous = afterReviewNumber;
  const items = body.items.map((value: unknown) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid review history item");
    const row = value as Record<string, unknown>;
    const input = factReviewInput({ reviewId: row.reviewId, expectedVersion: review.version,
      expectedSha256: review.proposalSha256, optionId: row.optionId, factPath: row.factPath,
      verdict: row.verdict, confirmation: "MANUAL_SOURCE_REVIEW" });
    if (!input) throw new Error("Invalid review history item");
    const receipt = factReviewFromCore(row, review.proposalId, input);
    if (receipt.reviewNumber <= previous || ids.has(receipt.reviewId)) throw new Error("Invalid review history order");
    previous = receipt.reviewNumber; ids.add(receipt.reviewId);
    return receipt;
  });
  const next = body.nextAfterReviewNumber;
  if (next !== null && (!Number.isSafeInteger(next) || next !== previous || items.length !== 20)) {
    throw new Error("Invalid review history cursor");
  }
  return { proposalId: review.proposalId, proposalVersion: review.version, proposalSha256: review.proposalSha256,
    afterReviewNumber, items, nextAfterReviewNumber: next as number | null };
}

export function factReviewVerdictLabel(verdict: FactReviewReceipt["verdict"]): string {
  return {
    SOURCE_SUPPORTS_CLAIM: "Curator reported: source supports the claim",
    SOURCE_DOES_NOT_SUPPORT_CLAIM: "Curator reported: source does not support the claim",
    INSUFFICIENT_EVIDENCE: "Curator reported: insufficient evidence",
  }[verdict];
}

export function factReviewHistoryHref(id: string, version: number, afterReviewNumber: number): string {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id) ||
      !Number.isSafeInteger(version) || version < 0 || !Number.isSafeInteger(afterReviewNumber) || afterReviewNumber < 0) {
    throw new RangeError("Invalid review history link");
  }
  return `/catalog/review/${id}?reviewVersion=${version}&reviewAfter=${afterReviewNumber}#fact-review-history`;
}
