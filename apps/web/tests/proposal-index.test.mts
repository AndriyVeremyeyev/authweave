import assert from "node:assert/strict";
import { test } from "node:test";

import { parseProposalReviewCursor, proposalIndexFromCore } from "../src/lib/catalog/proposal-index.ts";

const id = (n: number) => `90000000-0000-4000-8000-${n.toString().padStart(12, "0")}`;
const createdAt = "2026-09-22T12:00:00.123456Z";
const item = (n: number) => ({ proposalId: id(n), version: 0, proposalSha256: "a".repeat(64),
  createdAt, updatedAt: createdAt, rejectionRecorded: n === 1 });

test("review cursor preserves PostgreSQL microseconds and rejects partial or malformed input", () => {
  const cursor = { createdAt, id: id(1) };
  assert.deepEqual(parseProposalReviewCursor(cursor), cursor);
  assert.equal(parseProposalReviewCursor(null), null);
  assert.throws(() => parseProposalReviewCursor({ createdAt }), /cursor/);
  assert.throws(() => parseProposalReviewCursor({ ...cursor, id: [id(1)] }), /cursor/);
  assert.throws(() => parseProposalReviewCursor({ ...cursor, createdAt: "bad" }), /cursor/);
});

test("Core review index accepts bounded body-free summaries and a matching older-page cursor", () => {
  const items = Array.from({ length: 20 }, (_, n) => item(n + 1));
  const nextBefore = { createdAt, id: id(20) };
  assert.deepEqual(proposalIndexFromCore({ items, nextBefore }), { items, nextBefore });
  assert.deepEqual(proposalIndexFromCore({ items: [], nextBefore: null }), {
    items: [], nextBefore: null,
  });
  assert.throws(() => proposalIndexFromCore({ items: [...items, item(21)], nextBefore }), /page/);
  assert.throws(() => proposalIndexFromCore({ items: [item(1), item(1)], nextBefore: null }), /Duplicate/);
  assert.throws(() => proposalIndexFromCore({ items: [{ ...item(1), request: {} }],
    nextBefore: null }), /item/);
  assert.throws(() => proposalIndexFromCore({ items: [], nextBefore: null,
    evidence: [] }), /page/);
  assert.throws(() => proposalIndexFromCore({ items: [item(1)], nextBefore }), /cursor/);
  assert.throws(() => proposalIndexFromCore({ items, nextBefore: { createdAt, id: id(1) } }), /cursor/);
});
