import type { CatalogProposalReview } from "./proposal-review.ts";
import type { CandidateEvidencePage } from "./evidence-review.ts";
import type { FactReviewSummaryPage } from "./fact-review-summary.ts";
import type { CatalogImpactReview } from "./impact-review.ts";

export type ReviewPrerequisiteCode = "REVISION_REJECTED" | "NO_CANDIDATE_FACTS" |
  "MANUAL_OBSERVATIONS_MISSING" | "SOURCE_NOT_SUPPORTING" | "SOURCE_EVIDENCE_INSUFFICIENT" |
  "EVIDENCE_STALE" | "EVIDENCE_FUTURE_DATED" | "CANDIDATE_EVIDENCE_UNVERIFIED" |
  "BASELINE_UNVERIFIED" | "IMPACT_REPORT_MISSING" | "IMPACT_REPORT_BLOCKED" |
  "IMPACT_COVERAGE_INCOMPLETE" | "APPROVAL_PUBLICATION_UNAVAILABLE";
export type ReviewPrerequisite = {
  code: ReviewPrerequisiteCode; title: string; explanation: string; factCount: number | null;
  section: "candidate-evidence" | "fact-review-history" | "impact-heading" | "decision-heading" | "summary-heading";
};
export type CatalogReviewPrerequisites = {
  proposalId: string; proposalVersion: number; proposalSha256: string;
  policyVersion: "catalog-review-prerequisites-display-1"; approvalStatus: "UNAVAILABLE";
  evidenceEvaluatedAt: string; reviewThroughNumber: number; impactReportNumber: number | null;
  gaps: ReviewPrerequisite[];
  sourceVerificationPerformed: false; approvalGranted: false; catalogWritesPerformed: false; factTrustChanged: false;
};

/** Display-only projection of validated Core reads. Never an exhaustive or authoritative approval gate. */
export function catalogReviewPrerequisites(review: CatalogProposalReview, evidence: CandidateEvidencePage,
  summary: FactReviewSummaryPage, impact: CatalogImpactReview | null, rejected: boolean): CatalogReviewPrerequisites {
  if (summary.proposalId !== review.proposalId || summary.proposalVersion !== review.version ||
      summary.proposalSha256 !== review.proposalSha256 || summary.factCount !== evidence.factCount) {
    throw new Error("Mismatched review prerequisites");
  }
  const gaps: ReviewPrerequisite[] = [];
  const add = (code: ReviewPrerequisiteCode, title: string, explanation: string,
    section: ReviewPrerequisite["section"], factCount: number | null = null) => {
    if (factCount === null || factCount > 0) gaps.push({ code, title, explanation, section, factCount });
  };
  if (rejected) add("REVISION_REJECTED", "This revision was rejected",
    "The recorded rejection remains separate from the immutable candidate. Reading this report does not reopen it.", "decision-heading");
  if (summary.factCount === 0) add("NO_CANDIDATE_FACTS", "No candidate facts are recorded",
    "An empty candidate is not complete evidence or a vacuous approval success.", "candidate-evidence");
  add("MANUAL_OBSERVATIONS_MISSING", "Manual observations are missing",
    "No human source-review observation is recorded for these facts. Absence is not a negative provider claim.",
    "candidate-evidence", summary.counts.noObservation);
  add("SOURCE_NOT_SUPPORTING", "Latest observations do not support the claims",
    "Curators reported that the submitted sources do not support these claims in their stated scopes. This is not a provider capability verdict.",
    "fact-review-history", summary.counts.sourceDoesNotSupportClaim);
  add("SOURCE_EVIDENCE_INSUFFICIENT", "Latest observations report insufficient evidence",
    "Curators could not decide from the submitted sources. Do not treat uncertainty as support or lack of capability.",
    "fact-review-history", summary.counts.insufficientEvidence);
  add("EVIDENCE_STALE", "Evidence dates are stale",
    "Core classified these observation dates as older than 90 days. A later manual observation does not refresh observedAt.",
    "candidate-evidence", evidence.freshness.stale);
  add("EVIDENCE_FUTURE_DATED", "Evidence dates are in the future",
    "Core classified these observation dates as future-dated and unusable. A supporting manual observation does not repair them.",
    "candidate-evidence", evidence.freshness.future);
  add("CANDIDATE_EVIDENCE_UNVERIFIED", "Candidate evidence remains unverified",
    "All recorded candidate evidence is UNREVIEWED. Human observations, including support for every fact, do not promote evidence trust.",
    "candidate-evidence", summary.factCount);
  add("BASELINE_UNVERIFIED", "The submitted baseline is not trusted",
    "The base is a caller-supplied draft, not a verified published snapshot. Manual observations cover candidate facts, not baseline trust.", "summary-heading");
  if (!impact) add("IMPACT_REPORT_MISSING", "No exact-revision scenario report is stored",
    "An explicit local command can record conditional scenario impact. A missing report does not mean no impact.", "impact-heading");
  else if (impact.status === "BLOCKED") add("IMPACT_REPORT_BLOCKED", "The latest stored scenario run was blocked",
    "That historical run performed no hypothetical scenario evaluation. Reading this report does not run it again.", "impact-heading");
  add("IMPACT_COVERAGE_INCOMPLETE", "Full impact coverage is not established",
    "Stored scenarios are historical, conditional and incomplete. An analyzed run or zero uncovered changes does not prove current-rule coverage of all affected assessments.", "impact-heading");
  add("APPROVAL_PUBLICATION_UNAVAILABLE", "Approval and publication are not implemented",
    "No trusted publication or approval path exists yet. Resolving the displayed gaps does not enable a write or grant permission to publish.", "decision-heading");
  return { proposalId: review.proposalId, proposalVersion: review.version, proposalSha256: review.proposalSha256,
    policyVersion: "catalog-review-prerequisites-display-1", approvalStatus: "UNAVAILABLE",
    evidenceEvaluatedAt: evidence.evaluatedAt, reviewThroughNumber: summary.reviewThroughNumber,
    impactReportNumber: impact?.reportNumber ?? null, gaps,
    sourceVerificationPerformed: false, approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false };
}
