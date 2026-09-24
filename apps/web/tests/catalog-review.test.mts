import assert from "node:assert/strict";
import { test } from "node:test";

import { proposalReviewFromCore, sourceDetails } from "../src/lib/catalog/proposal-review.ts";

const proposalId = "90000000-0000-4000-8000-000000000001";
const digest = "a".repeat(64);
const source = { sourceUrl: "https://docs.example.invalid/identity/plan",
  observedAt: "2026-09-12T12:00:00Z", summary: "Fictional source, not verified." };
const snapshot = {
  proposalId, version: 2, state: "PROPOSED", requestSchemaVersion: 1,
  proposalSha256: digest, recordedAt: "2026-09-23T12:00:00Z",
  request: { schemaVersion: 1, proposalId, rationale: "Check the SCIM claim.",
    base: { catalogVersion: "example-1" }, candidate: { catalogVersion: "example-2" } },
  preview: { proposalId, proposalSha256: digest, rationale: "Check the SCIM claim.",
    proposalState: "PROPOSED", status: "REVIEW_REQUIRED", diffComputed: true,
    baselineVerified: false, sourceVerificationPerformed: false, approvalGranted: false,
    writesPerformed: false, evaluationReady: false, impactAnalysisPerformed: false,
    blockers: [], affectedOptionIds: ["example-managed-eu"], optionChanges: [],
    factChanges: [{ optionId: "example-managed-eu", path: "facts.SCIM", factKind: "CAPABILITY",
      changeType: "MODIFIED", aspects: ["CLAIM"],
      before: { availability: "OPTIONAL", evidence: source },
      after: { availability: "UNAVAILABLE", evidence: source } }] },
};

test("stored proposal review exposes diff and inert unverified provenance", () => {
  const review = proposalReviewFromCore(snapshot, proposalId);
  assert.equal(review.version, 2);
  assert.equal(review.rationale, "Check the SCIM claim.");
  assert.deepEqual(review.affectedOptionIds, ["example-managed-eu"]);
  assert.equal(review.factChanges[0].path, "facts.SCIM");
  assert.deepEqual(sourceDetails(review.factChanges[0].after), source);
  assert.equal(sourceDetails(null), null);
  assert.equal(sourceDetails({ evidence: { sourceUrl: "bad" } }), null);
});

test("review fails closed for a forged or mismatched approval boundary", () => {
  for (const invalid of [
    { ...snapshot, proposalId: "90000000-0000-4000-8000-000000000002" },
    { ...snapshot, proposalSha256: "b".repeat(64) },
    { ...snapshot, state: "APPROVED" },
    { ...snapshot, preview: { ...snapshot.preview, approvalGranted: true } },
    { ...snapshot, preview: { ...snapshot.preview, sourceVerificationPerformed: true } },
    { ...snapshot, preview: { ...snapshot.preview, status: "BLOCKED" } },
    { ...snapshot, preview: { ...snapshot.preview, blockers: ["BASE_DRAFT_INVALID"] } },
    { ...snapshot, preview: { ...snapshot.preview, factChanges: [{
      ...snapshot.preview.factChanges[0], changeType: "ADDED", before: snapshot.preview.factChanges[0].before,
    }] } },
  ]) assert.throws(() => proposalReviewFromCore(invalid, proposalId), /invalid/);
});
