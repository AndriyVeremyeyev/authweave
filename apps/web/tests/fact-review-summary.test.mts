import assert from "node:assert/strict";
import { test } from "node:test";
import { factReviewSummaryFromCore } from "../src/lib/catalog/fact-review-summary.ts";
import type { CandidateEvidencePage } from "../src/lib/catalog/evidence-review.ts";
import { candidateEvidenceFixture } from "./fixtures/candidate-evidence.mts";
import { factReviewSummaryFixture } from "./fixtures/fact-review-summary.mts";

const review = { proposalId: "90000000-0000-4000-8000-000000000001", version: 0, proposalSha256: "a".repeat(64) };
const rawEvidence = candidateEvidenceFixture(review.proposalId, review.version, review.proposalSha256);
const evidence = rawEvidence as unknown as CandidateEvidencePage;
const empty = factReviewSummaryFixture(review.proposalId, review.version, review.proposalSha256);
const receipt = { reviewId: "90000000-0000-4000-8000-000000000002", proposalId: review.proposalId,
  proposalVersion: 0, proposalSha256: review.proposalSha256, reviewNumber: 2, optionId: "example-managed-eu", factPath: "facts.OIDC",
  verdict: "SOURCE_SUPPORTS_CLAIM", recordedAt: "2026-09-30T12:00:00Z", kind: "HUMAN_SOURCE_REVIEW_OBSERVATION",
  sourceVerificationPerformed: false, approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false };
const supported = { ...empty, reviewThroughNumber: 2,
  counts: { ...empty.counts, noObservation: 0, sourceSupportsClaim: 1 },
  items: [{ ...empty.items[0], latestObservation: receipt }] };

test("whole-candidate summary distinguishes absent and all three reported latest conclusions without granting trust", () => {
  assert.deepEqual(factReviewSummaryFromCore(empty, review, evidence), empty);
  for (const [verdict, key] of [["SOURCE_SUPPORTS_CLAIM", "sourceSupportsClaim"],
    ["SOURCE_DOES_NOT_SUPPORT_CLAIM", "sourceDoesNotSupportClaim"], ["INSUFFICIENT_EVIDENCE", "insufficientEvidence"]]) {
    const input = { ...supported, counts: { noObservation: 0, sourceSupportsClaim: 0, sourceDoesNotSupportClaim: 0,
      insufficientEvidence: 0, [key]: 1 }, items: [{ ...supported.items[0], latestObservation: { ...receipt, verdict } }] };
    const result = factReviewSummaryFromCore(input, review, evidence);
    assert.deepEqual(result, input); assert.equal(result.factTrustChanged, false); assert.equal(result.approvalGranted, false);
  }
  const laterEvidence = { ...evidence, factCount: 21, offset: 20, nextOffset: null };
  const later = { ...supported, factCount: 21, offset: 20, counts: { ...supported.counts, noObservation: 20 } };
  assert.equal(factReviewSummaryFromCore(later, review, laterEvidence).counts.noObservation, 20);
  assert.deepEqual(factReviewSummaryFromCore({ ...empty, factCount: 0, counts: { ...empty.counts, noObservation: 0 }, items: [] },
    review, { ...evidence, factCount: 0, items: [] }).items, []);
});

test("summary rejects foreign revisions/targets, inconsistent counts/boundaries and forged authority/actor fields", () => {
  for (const change of [{ proposalId: "other" }, { proposalVersion: 1 }, { proposalSha256: "b".repeat(64) },
    { policyVersion: "other" }, { offset: 20 }, { factCount: 2 }, { nextOffset: 20 }, { reviewThroughNumber: 1 },
    { reviewThroughNumber: Number.MAX_SAFE_INTEGER + 1 }, { reviewThroughNumber: -1 }, { approved: true },
    { sourceVerificationPerformed: true }, { approvalGranted: true }, { factTrustChanged: true }, { catalogWritesPerformed: true },
    { counts: { ...supported.counts, noObservation: 1 } }, { counts: { ...supported.counts, sourceSupportsClaim: "1" } },
    { counts: { ...supported.counts, sourceSupportsClaim: -1 } }, { counts: { ...supported.counts, reviewed: 1 } },
    { items: [] }, { items: [{ ...supported.items[0], factPath: "facts.SCIM" }] },
    { items: [{ ...supported.items[0], latestObservation: null }] },
    { items: [{ ...supported.items[0], latestObservation: { ...receipt, proposalVersion: 1 } }] },
    { items: [{ ...supported.items[0], latestObservation: { ...receipt, factPath: "facts.SCIM" } }] },
    { items: [{ ...supported.items[0], latestObservation: { ...receipt, actorSubject: "private" } }] },
    { items: [{ ...supported.items[0], latestObservation: { ...receipt, sourceVerificationPerformed: true } }] }]) {
    assert.throws(() => factReviewSummaryFromCore({ ...supported, ...change }, review, evidence), /Invalid/);
  }
  assert.throws(() => factReviewSummaryFromCore({ ...empty, reviewThroughNumber: 1 }, review, evidence), /boundary/);
  assert.throws(() => factReviewSummaryFromCore({ ...supported, reviewThroughNumber: 3 }, review, evidence), /counts/);
});

test("summary receipts have unique numbers and IDs and global counts need not be page counts", () => {
  const secondTarget = { ...evidence.items[0], path: "facts.SCIM" };
  const two = { ...evidence, factCount: 2, items: [evidence.items[0], secondTarget] };
  const second = { ...receipt, reviewId: "90000000-0000-4000-8000-000000000003", reviewNumber: 3, factPath: "facts.SCIM" };
  const input = { ...supported, reviewThroughNumber: 3, factCount: 2, counts: { ...supported.counts, sourceSupportsClaim: 2 },
    items: [supported.items[0], { optionId: secondTarget.optionId, factPath: secondTarget.path, latestObservation: second }] };
  assert.deepEqual(factReviewSummaryFromCore(input, review, two), input);
  for (const change of [{ reviewNumber: 2 }, { reviewId: receipt.reviewId }]) {
    assert.throws(() => factReviewSummaryFromCore({ ...input, items: [input.items[0],
      { ...input.items[1], latestObservation: { ...second, ...change } }] }, review, two), /identity/);
  }
});
