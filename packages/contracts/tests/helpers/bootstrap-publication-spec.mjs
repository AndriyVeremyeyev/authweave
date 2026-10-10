import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { assertCoverage, orderedHash, manifestSha256, verificationGaps } from "./publication-decision-coverage-spec.mjs";

function unordered(value) {
  if (Array.isArray(value)) return value.map(unordered).sort((a, b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(k => [k, unordered(value[k])]));
  return value;
}
export const draftHash = value => createHash("sha256").update(JSON.stringify(unordered(value))).digest("hex");
export function assertBootstrapPublication(sample) {
  const { proof, proofSha256, snapshot, receipt, candidate, auditabilitySupplement } = sample;
  assert.deepEqual(Object.keys(proof).sort(), ["policyVersion", "request", "requestSha256", "snapshot", "decisionId", "publishedAt", "coverage"].sort());
  assert.equal(proof.policyVersion, "catalog-bootstrap-publication-1"); assert.equal(receipt.policyVersion, proof.policyVersion);
  assert.equal(proofSha256, orderedHash(proof)); assert.equal(receipt.proofSha256, proofSha256);
  assert.equal(proof.requestSha256, orderedHash(proof.request)); assert.equal(proof.request.publicationId, snapshot.snapshotId);
  assert.equal(proof.request.schemaVersion, 1); assert.equal(proof.request.confirmation, "PUBLISH_REVIEWED_BOOTSTRAP");
  assert.deepEqual(proof.snapshot, receipt.snapshot);
  assert.deepEqual(proof.snapshot, { snapshotId: snapshot.snapshotId, catalogVersion: snapshot.catalog.catalogVersion, snapshotSha256: snapshot.snapshotSha256 });
  assert.equal(proof.decisionId, snapshot.publication.decisionId); assert.equal(receipt.decisionId, proof.decisionId);
  assert.equal(proof.publishedAt, snapshot.publication.publishedAt); assert.equal(receipt.publishedAt, proof.publishedAt);
  assert.equal(snapshot.previousSnapshot, null); assert.equal(snapshot.contentSha256, draftHash(snapshot.catalog));
  const { snapshotSha256, ...manifestPayload } = snapshot; assert.equal(snapshotSha256, draftHash(manifestPayload));
  assert.equal(proof.request.source.decisionCatalogSha256, orderedHash(candidate));
  assert.deepEqual(candidate, { ...snapshot.catalog, kind: "PROVIDER_CATALOG_DRAFT" });
  assert.deepEqual(receipt.source, proof.request.source);
  assert.deepEqual(proof.coverage.before, proof.request.source); assert.deepEqual(proof.coverage.after, proof.request.source);
  assert.equal(proof.coverage.evaluatedAt, proof.publishedAt); assertCoverage(proof.coverage);
  assert.equal(proof.coverage.decisionScopeCoverageComplete, true); assert.equal(proof.coverage.storedSourceReviewsVerified, true);
  assert.equal(receipt.coverageManifestSha256, manifestSha256); assert.deepEqual(receipt.verificationGaps, verificationGaps);
  assert.equal(receipt.coverageScope, "DECLARED_DECISION_RULES_ONLY"); assert.equal(receipt.publicationRecorded, true);
  assert.equal(receipt.evaluationReady, false); assert.equal(receipt.externalSourceVerificationPerformed, false);
  for (const scenario of proof.coverage.scenarios) {
    assert.equal(scenario.beforeResultSha256, scenario.afterResultSha256); assert.equal(scenario.beforeInputSha256, scenario.afterInputSha256);
    assert.equal(scenario.beforeOptions, scenario.afterOptions); assert.equal(scenario.decisionOutcomesChanged, false);
  }
  if (proof.request.source.auditability === null) assert.equal(auditabilitySupplement, null);
  else assert.equal(proof.request.source.auditability.decisionSupplementSha256, orderedHash(auditabilitySupplement));
  function fresh(value) {
    if (!value || typeof value !== "object") return;
    if (value.evidence) {
      const age = Date.parse(proof.publishedAt) - Date.parse(value.evidence.observedAt);
      assert(age >= 0 && age <= 90 * 86400000, "Publication evidence is checked at the server-owned publication time");
    }
    for (const child of Object.values(value)) fresh(child);
  }
  fresh(candidate); fresh(auditabilitySupplement);
}
