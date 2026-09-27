import assert from "node:assert/strict";
import { test } from "node:test";

import { proposalReviewFromCore, sourceDetails } from "../src/lib/catalog/proposal-review.ts";
import { impactReviewFromCore } from "../src/lib/catalog/impact-review.ts";
import { storedImpactFixture } from "./fixtures/stored-impact.mts";

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

const storedImpact = storedImpactFixture(proposalId, 2, digest);

test("latest stored scenario run shows bound historical outcomes without readiness", () => {
  const impact = impactReviewFromCore(storedImpact, proposalReviewFromCore(snapshot, proposalId));
  assert.equal(impact.reportNumber, 7);
  assert.equal(impact.status, "ANALYZED");
  assert.equal(impact.scenarios[0].before, "INDETERMINATE");
  assert.equal(impact.scenarios[0].after, "WOULD_VIOLATE_CHECKED_REQUIREMENTS");
  assert.equal(impact.uncoveredChanges[0].factPath, "facts.OTHER");
});

test("blocked stored run cannot be displayed as an analyzed outcome", () => {
  const blocked = { ...storedImpact, report: { ...storedImpact.report, status: "BLOCKED",
    impactAnalysisPerformed: false, hypotheticalEvaluationPerformed: false,
    scenarios: [], uncoveredChanges: [] } };
  const impact = impactReviewFromCore(blocked, proposalReviewFromCore(snapshot, proposalId));
  assert.equal(impact.status, "BLOCKED");
  assert.deepEqual(impact.scenarios, []);
});

test("scenario review rejects another revision, forged authority and inconsistent outcomes", () => {
  const proposal = proposalReviewFromCore(snapshot, proposalId);
  for (const invalid of [
    { ...storedImpact, proposalVersion: 1 },
    { ...storedImpact, proposalSha256: "d".repeat(64) },
    { ...storedImpact, report: { ...storedImpact.report, approvalGranted: true } },
    { ...storedImpact, report: { ...storedImpact.report, coverageComplete: true } },
    { ...storedImpact, report: { ...storedImpact.report, scenarios: [{
      ...storedImpact.report.scenarios[0], conditionalStatusChanged: false }] } },
    { ...storedImpact, report: { ...storedImpact.report, status: "BLOCKED",
      impactAnalysisPerformed: false, hypotheticalEvaluationPerformed: false } },
  ]) assert.throws(() => impactReviewFromCore(invalid, proposal), /invalid/);
});
