import assert from "node:assert/strict";
import { test } from "node:test";
import { factReviewHistoryCursorFromQuery, factReviewHistoryFromCore, factReviewHistoryHref,
  factReviewVerdictLabel } from "../src/lib/catalog/fact-review-history.ts";

const review = { proposalId: "90000000-0000-4000-8000-000000000001", version: 2, proposalSha256: "a".repeat(64) };
const row = (number: number) => ({ reviewId: `90000000-0000-4000-8000-${number.toString().padStart(12, "0")}`,
  proposalId: review.proposalId, proposalVersion: review.version, proposalSha256: review.proposalSha256,
  reviewNumber: number, optionId: "example-managed-eu", factPath: "facts.SCIM", verdict: "SOURCE_SUPPORTS_CLAIM",
  recordedAt: "2026-09-30T12:00:00Z", kind: "HUMAN_SOURCE_REVIEW_OBSERVATION", sourceVerificationPerformed: false,
  approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false });
const page = (items = [row(1)], afterReviewNumber = 0, nextAfterReviewNumber: number | null = null) => ({
  proposalId: review.proposalId, proposalVersion: review.version, proposalSha256: review.proposalSha256,
  afterReviewNumber, items, nextAfterReviewNumber });

test("review history preserves corrections without interpreting them as trusted facts", () => {
  const input = page([row(1), { ...row(2), verdict: "SOURCE_DOES_NOT_SUPPORT_CLAIM" },
    { ...row(3), verdict: "INSUFFICIENT_EVIDENCE" }]);
  assert.deepEqual(factReviewHistoryFromCore(input, review, 0), input);
  assert.deepEqual(factReviewHistoryFromCore(page([]), review, 0).items, []);
  assert.deepEqual(factReviewHistoryFromCore(page(Array.from({ length: 20 }, (_, i) => row(i + 1)), 0, 20), review, 0).nextAfterReviewNumber, 20);
  assert.deepEqual(factReviewHistoryFromCore(page([row(21)], 20), review, 20).items[0].reviewNumber, 21);
  for (const verdict of ["SOURCE_SUPPORTS_CLAIM", "SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE"] as const) {
    assert.ok(factReviewVerdictLabel(verdict).startsWith("Curator reported:"));
    assert.ok(!factReviewVerdictLabel(verdict).includes("verified"));
  }
});

test("history rejects another revision, forged authority, private actor data and incoherent pagination", () => {
  const valid = page();
  for (const change of [{ proposalId: "other-id" }, { proposalVersion: 3 }, { proposalSha256: "b".repeat(64) },
    { afterReviewNumber: 1 }, { approved: true }, { nextAfterReviewNumber: 1 }, { nextAfterReviewNumber: "1" },
    { items: Array.from({ length: 21 }, (_, i) => row(i + 1)) }, { items: [row(2), row(1)] },
    { items: [row(1), { ...row(2), reviewId: row(1).reviewId }] },
    { items: [{ ...row(1), actorSubject: "private" }] }, { items: [{ ...row(1), factTrustChanged: true }] },
    { items: [{ ...row(1), proposalVersion: 3 }] }, { items: [{ ...row(1), proposalSha256: "b".repeat(64) }] },
    { items: [{ ...row(1), factPath: "request" }] }, { items: [{ ...row(1), reviewId: "bad" }] }]) {
    assert.throws(() => factReviewHistoryFromCore({ ...valid, ...change }, review, 0), /Invalid/);
  }
  assert.throws(() => factReviewHistoryFromCore(page([row(20)], 20), review, 20), /order/);
  assert.throws(() => factReviewHistoryFromCore(page(Array.from({ length: 20 }, (_, i) => row(i + 1)), 0, 19), review, 0), /cursor/);
});

test("browser history cursors are canonical pairs bound to a proposal revision", () => {
  assert.equal(factReviewHistoryCursorFromQuery(undefined, undefined), null);
  assert.deepEqual(factReviewHistoryCursorFromQuery("0", "0"), { version: 0, afterReviewNumber: 0 });
  assert.deepEqual(factReviewHistoryCursorFromQuery("2", "20"), { version: 2, afterReviewNumber: 20 });
  for (const [version, after] of [[undefined, "20"], ["2", undefined], ["-1", "20"], ["2", "01"],
    ["2", "1.5"], ["2", "9007199254740992"], [["2", "3"], "20"], ["2", ["20", "40"]]]) {
    assert.throws(() => factReviewHistoryCursorFromQuery(version, after), /Invalid/);
  }
  assert.equal(factReviewHistoryHref(review.proposalId, 2, 20),
    `/catalog/review/${review.proposalId}?reviewVersion=2&reviewAfter=20#fact-review-history`);
  assert.throws(() => factReviewHistoryHref("../other", 2, 20), /Invalid/);
});
