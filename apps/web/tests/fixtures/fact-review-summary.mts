import { candidateEvidenceFixture } from "./candidate-evidence.mts";

export function factReviewSummaryFixture(proposalId: string, proposalVersion: number, proposalSha256: string,
  evidence = candidateEvidenceFixture(proposalId, proposalVersion, proposalSha256)) {
  return { proposalId, proposalVersion, proposalSha256, policyVersion: "catalog-fact-review-summary-1",
    reviewThroughNumber: 0, factCount: evidence.factCount,
    counts: { noObservation: evidence.factCount, sourceSupportsClaim: 0, sourceDoesNotSupportClaim: 0, insufficientEvidence: 0 },
    offset: evidence.offset, items: evidence.items.map(item => ({ optionId: item.optionId, factPath: item.path, latestObservation: null })),
    nextOffset: evidence.nextOffset, sourceVerificationPerformed: false, approvalGranted: false,
    catalogWritesPerformed: false, factTrustChanged: false };
}
