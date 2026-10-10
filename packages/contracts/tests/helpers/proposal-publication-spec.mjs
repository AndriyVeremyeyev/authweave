import assert from "node:assert/strict";
import { draftHash } from "./bootstrap-publication-spec.mjs";
import { orderedHash, verificationGaps } from "./publication-decision-coverage-spec.mjs";
import { assertProposalCalculation, manifestSha256 } from "./publication-proposal-coverage-spec.mjs";

// Independent artifact binding, not DB authentication, source verification or a second Java decision engine.
export function assertProposalPublication(sample) {
  const { proof, proofSha256, receipt, snapshot, calculation } = sample;
  assert.deepEqual(Object.keys(proof).sort(), ["policyVersion", "request", "requestSha256", "snapshot", "decisionId", "publishedAt", "coverage"].sort());
  assert.equal(proof.policyVersion, "catalog-proposal-publication-1"); assert.equal(receipt.policyVersion, proof.policyVersion);
  assert.equal(proofSha256, orderedHash(proof)); assert.equal(receipt.proofSha256, proofSha256);
  assert.equal(proof.requestSha256, orderedHash(proof.request)); assert.equal(proof.request.publicationId, snapshot.snapshotId);
  assert.equal(proof.request.schemaVersion, 1); assert.equal(proof.request.confirmation, "PUBLISH_REVIEWED_PROPOSAL");
  assert.deepEqual(proof.snapshot, receipt.snapshot);
  assert.deepEqual(proof.snapshot, { snapshotId: snapshot.snapshotId, catalogVersion: snapshot.catalog.catalogVersion, snapshotSha256: snapshot.snapshotSha256 });
  assert.equal(proof.decisionId, snapshot.publication.decisionId); assert.equal(receipt.decisionId, proof.decisionId);
  assert.equal(proof.publishedAt, snapshot.publication.publishedAt); assert.equal(receipt.publishedAt, proof.publishedAt);
  assert.deepEqual(snapshot.previousSnapshot, proof.request.before); assert.deepEqual(receipt.before, proof.request.before);
  assert.deepEqual(receipt.proposal, proof.request.proposal); assert.notEqual(snapshot.snapshotId, snapshot.previousSnapshot.snapshotId);
  assert.notEqual(snapshot.catalog.catalogVersion, snapshot.previousSnapshot.catalogVersion);
  assert.equal(snapshot.contentSha256, draftHash(snapshot.catalog));
  const { snapshotSha256, ...unsigned } = snapshot; assert.equal(snapshotSha256, draftHash(unsigned));
  assert.deepEqual(calculation.after.catalog, { ...snapshot.catalog, kind: "PROVIDER_CATALOG_DRAFT" });
  assert.deepEqual(proof.coverage, calculation.check); assert.deepEqual(proof.coverage.before, proof.request.before);
  assert.deepEqual(proof.coverage.after, proof.request.proposal); assert.equal(proof.coverage.evaluatedAt, proof.publishedAt);
  assertProposalCalculation(calculation); assert.equal(proof.coverage.decisionScopeCoverageComplete, true);
  assert.equal(proof.coverage.candidateClaims.allRecordedClaimsSupportedAndCurrent, true);
  assert.equal(receipt.coverageManifestSha256, manifestSha256); assert.deepEqual(receipt.verificationGaps, verificationGaps);
  assert.equal(receipt.coverageScope, "DECLARED_DECISION_RULES_ONLY"); assert.equal(receipt.publicationRecorded, true);
  assert.equal(receipt.evaluationReady, false); assert.equal(receipt.externalSourceVerificationPerformed, false);
  assert(snapshot.factEvidenceStatuses.every(s => s.evidenceStatus === "REVIEWED"));
}
