import assert from "node:assert/strict";
import { test } from "node:test";
import { evidenceOffsetFromQuery, evidencePageFromCore } from "../src/lib/catalog/evidence-review.ts";
import { candidateEvidenceFixture } from "./fixtures/candidate-evidence.mts";

const review = { proposalId: "90000000-0000-4000-8000-000000000001", version: 2,
  proposalSha256: "a".repeat(64), candidateCatalogVersion: "synthetic-candidate",
  recordedAt: "2026-09-30T12:00:00Z", rationale: "Synthetic proposal.", baseCatalogVersion: "synthetic-base",
  affectedOptionIds: [], optionChanges: [], factChanges: [] };
const page = candidateEvidenceFixture(review.proposalId, review.version, review.proposalSha256);

test("candidate evidence remains available when no fact changes are listed", () => {
  const result = evidencePageFromCore(page, review, 0);
  assert.equal(result.factCount, 1);
  assert.equal(result.items[0].path, "facts.OIDC");
  assert.equal(result.items[0].evidenceStatus, "UNREVIEWED");
  assert.equal(evidenceOffsetFromQuery("20"), 20);
  for (const invalid of ["-20", "21", "6801", "00", ["20"]]) assert.equal(evidenceOffsetFromQuery(invalid), 0);
});

test("evidence pagination accepts a full first page and the remaining immutable rows", () => {
  const items = Array.from({ length: 21 }, (_, n) => ({ ...page.items[0], optionId: `example-${String(n).padStart(2, "0")}` }));
  const first = { ...page, factCount: 21, freshness: { current: 21, stale: 0, future: 0 },
    items: items.slice(0, 20), nextOffset: 20 };
  assert.equal(evidencePageFromCore(first, review, 0).nextOffset, 20);
  assert.equal(evidencePageFromCore({ ...first, offset: 20, items: items.slice(20), nextOffset: null },
    review, 20).items.length, 1);
});

test("evidence pages reject mismatched snapshots, forged authority, counts and pagination", () => {
  for (const invalid of [{ ...page, proposalVersion: 3 }, { ...page, proposalSha256: "b".repeat(64) },
    { ...page, sourceVerificationPerformed: true }, { ...page, approvalGranted: true },
    { ...page, factCount: 2 }, { ...page, nextOffset: 20 },
    { ...page, items: [{ ...page.items[0], evidenceStatus: "REVIEWED" }] },
    { ...page, items: [{ ...page.items[0], evidence: { ...page.items[0].evidence,
      sourceUrl: "https://user:password@example.invalid/" } }] }]) {
    assert.throws(() => evidencePageFromCore(invalid, review, 0), /invalid/);
  }
});
