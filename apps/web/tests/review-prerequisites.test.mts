import assert from "node:assert/strict";
import { test } from "node:test";
import { catalogReviewPrerequisites, type ReviewPrerequisiteCode } from "../src/lib/catalog/review-prerequisites.ts";
import type { CatalogProposalReview } from "../src/lib/catalog/proposal-review.ts";
import { evidencePageFromCore, type CandidateEvidencePage } from "../src/lib/catalog/evidence-review.ts";
import { factReviewSummaryFromCore, type FactReviewSummaryPage } from "../src/lib/catalog/fact-review-summary.ts";
import { impactReviewFromCore } from "../src/lib/catalog/impact-review.ts";
import { candidateEvidenceFixture } from "./fixtures/candidate-evidence.mts";
import { factReviewSummaryFixture } from "./fixtures/fact-review-summary.mts";
import { storedImpactFixture } from "./fixtures/stored-impact.mts";

const review: CatalogProposalReview = { proposalId: "90000000-0000-4000-8000-000000000001", version: 0,
  proposalSha256: "a".repeat(64), recordedAt: "2026-09-30T12:00:00Z", rationale: "Synthetic draft.",
  baseCatalogVersion: "synthetic-base", candidateCatalogVersion: "synthetic-candidate",
  affectedOptionIds: [], optionChanges: [], factChanges: [] };
const rawEvidence = candidateEvidenceFixture(review.proposalId, review.version, review.proposalSha256);
const evidence = evidencePageFromCore(rawEvidence, review, 0);
const summary = factReviewSummaryFromCore(factReviewSummaryFixture(review.proposalId, review.version,
  review.proposalSha256), review, evidence);
const rawImpact = storedImpactFixture(review.proposalId, review.version, review.proposalSha256);
const gap = (report: ReturnType<typeof catalogReviewPrerequisites>, code: ReviewPrerequisiteCode) =>
  report.gaps.find(row => row.code === code);
const fixed = ["CANDIDATE_EVIDENCE_UNVERIFIED", "BASELINE_UNVERIFIED", "IMPACT_COVERAGE_INCOMPLETE",
  "APPROVAL_PUBLICATION_UNAVAILABLE"] as const;

test("prerequisites identify absent observations/report and remain display-only without mutating inputs", () => {
  const before = structuredClone({ review, evidence, summary });
  const result = catalogReviewPrerequisites(review, evidence, summary, null, false);
  assert.deepEqual(result.gaps.map(row => row.code), ["MANUAL_OBSERVATIONS_MISSING", ...fixed.slice(0, 2),
    "IMPACT_REPORT_MISSING", ...fixed.slice(2)]);
  assert.equal(gap(result, "MANUAL_OBSERVATIONS_MISSING")?.factCount, 1);
  assert.equal(result.proposalSha256, review.proposalSha256);
  assert.equal(result.evidenceEvaluatedAt, rawEvidence.evaluatedAt);
  assert.equal(result.reviewThroughNumber, 0);
  assert.equal(result.impactReportNumber, null);
  assert.equal(result.approvalStatus, "UNAVAILABLE");
  for (const key of ["approvalGranted", "catalogWritesPerformed", "factTrustChanged", "sourceVerificationPerformed"] as const) {
    assert.equal(result[key], false);
  }
  assert.deepEqual({ review, evidence, summary }, before);
  const projected = JSON.stringify(result);
  for (const omitted of ["sourceUrl", "actor", "recordedAt", "rationale", "OPTIONAL", "Pilot configuration"]) {
    assert.ok(!projected.includes(omitted));
  }
});

test("every supporting observation and an analyzed report with zero uncovered changes still cannot approve", () => {
  const supported: FactReviewSummaryPage = { ...summary, reviewThroughNumber: 21,
    counts: { noObservation: 0, sourceSupportsClaim: 1, sourceDoesNotSupportClaim: 0, insufficientEvidence: 0 } };
  const impact = impactReviewFromCore({ ...rawImpact, report: { ...rawImpact.report, uncoveredChanges: [] } }, review);
  const result = catalogReviewPrerequisites(review, evidence, supported, impact, false);
  assert.deepEqual(result.gaps.map(row => row.code), fixed);
  assert.equal(result.reviewThroughNumber, 21); assert.equal(result.impactReportNumber, 7);
  assert.equal(result.approvalStatus, "UNAVAILABLE"); assert.equal(result.approvalGranted, false);
  for (const freshness of [{ current: 0, stale: 1, future: 0 }, { current: 0, stale: 0, future: 1 }]) {
    const dated = catalogReviewPrerequisites(review, { ...evidence, freshness }, supported, impact, false);
    assert.equal(gap(dated, freshness.stale ? "EVIDENCE_STALE" : "EVIDENCE_FUTURE_DATED")?.factCount, 1);
    assert.equal(dated.approvalGranted, false); assert.equal(dated.factTrustChanged, false);
  }
});

test("whole-candidate gaps do not depend on displayed page length or old history verdicts", () => {
  const pagedEvidence: CandidateEvidencePage = { ...evidence, factCount: 31, offset: 20, nextOffset: null,
    freshness: { current: 25, stale: 4, future: 2 }, items: [] };
  const pagedSummary: FactReviewSummaryPage = { ...summary, factCount: 31, offset: 20, nextOffset: null,
    reviewThroughNumber: 42, items: [],
    counts: { noObservation: 20, sourceSupportsClaim: 8, sourceDoesNotSupportClaim: 1, insufficientEvidence: 2 } };
  const result = catalogReviewPrerequisites(review, pagedEvidence, pagedSummary, null, false);
  for (const [code, count] of [["MANUAL_OBSERVATIONS_MISSING", 20], ["SOURCE_NOT_SUPPORTING", 1],
    ["SOURCE_EVIDENCE_INSUFFICIENT", 2], ["EVIDENCE_STALE", 4], ["EVIDENCE_FUTURE_DATED", 2],
    ["CANDIDATE_EVIDENCE_UNVERIFIED", 31]] as const) assert.equal(gap(result, code)?.factCount, count);
  const firstPage = catalogReviewPrerequisites(review, { ...pagedEvidence, offset: 0, nextOffset: 20 },
    { ...pagedSummary, offset: 0, nextOffset: 20 }, null, false);
  assert.deepEqual(firstPage, result);
  const corrected = catalogReviewPrerequisites(review, pagedEvidence, { ...pagedSummary, reviewThroughNumber: 43,
    counts: { ...pagedSummary.counts, sourceDoesNotSupportClaim: 0, sourceSupportsClaim: 9 } }, null, false);
  assert.equal(gap(corrected, "SOURCE_NOT_SUPPORTING"), undefined);
  assert.equal(gap(result, "SOURCE_NOT_SUPPORTING")?.factCount, 1);
  assert.equal(corrected.approvalGranted, false);
});

test("rejected, empty and blocked-run states never imply a ready-to-publish result", () => {
  const blocked = impactReviewFromCore({ ...rawImpact, report: { ...rawImpact.report, status: "BLOCKED",
    impactAnalysisPerformed: false, hypotheticalEvaluationPerformed: false, scenarios: [], uncoveredChanges: [] } }, review);
  const result = catalogReviewPrerequisites(review, evidence, summary, blocked, true);
  assert.ok(gap(result, "REVISION_REJECTED")); assert.ok(gap(result, "IMPACT_REPORT_BLOCKED"));
  assert.equal(gap(result, "IMPACT_REPORT_MISSING"), undefined);
  assert.equal(result.approvalGranted, false);
  const empty = catalogReviewPrerequisites(review, { ...evidence, factCount: 0, items: [],
    freshness: { current: 0, stale: 0, future: 0 } }, { ...summary, factCount: 0, items: [],
    counts: { ...summary.counts, noObservation: 0 } }, null, false);
  assert.ok(gap(empty, "NO_CANDIDATE_FACTS")); assert.equal(empty.approvalStatus, "UNAVAILABLE");
  assert.equal(gap(empty, "MANUAL_OBSERVATIONS_MISSING"), undefined);
});

test("projection refuses a summary from another revision, digest, proposal or candidate size", () => {
  for (const change of [{ proposalId: "other" }, { proposalVersion: 1 }, { proposalSha256: "b".repeat(64) },
    { factCount: 2 }]) assert.throws(() => catalogReviewPrerequisites(review, evidence,
    { ...summary, ...change }, null, false), /Mismatched/);
});
