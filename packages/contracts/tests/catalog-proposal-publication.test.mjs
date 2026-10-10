import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { verificationGaps } from "./helpers/publication-decision-coverage-spec.mjs";
import { manifestSha256 } from "./helpers/publication-proposal-coverage-spec.mjs";

const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const validateRequest = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-publication-request.v1.schema.json");
const validateReceipt = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-publication.v1.schema.json");
const id = "00000000-0000-4000-8000-000000000001";
const before = { snapshotId: id, catalogVersion: "fictional-root", snapshotSha256: "1".repeat(64) };
const proposal = { revision: { proposalId: id, version: 0, proposalSha256: "2".repeat(64), requestSha256: "3".repeat(64) },
  reviewThroughNumber: 34, reviewSetSha256: "4".repeat(64), auditability: null };
const request = () => ({ schemaVersion: 1, publicationId: id, before: structuredClone(before), proposal: structuredClone(proposal), confirmation: "PUBLISH_REVIEWED_PROPOSAL" });
const receipt = () => ({ snapshot: { snapshotId: id, catalogVersion: "fictional-child", snapshotSha256: "5".repeat(64) }, decisionId: id,
  publishedAt: "2026-10-10T02:00:00Z", before: structuredClone(before), proposal: structuredClone(proposal), policyVersion: "catalog-proposal-publication-1",
  proofSha256: "6".repeat(64), coverageManifestSha256: manifestSha256, coverageScope: "DECLARED_DECISION_RULES_ONLY", verificationGaps: structuredClone(verificationGaps),
  publicationRecorded: true, evaluationReady: false, externalSourceVerificationPerformed: false });
test("successor request has exact parent/revision/latest-review pins and explicit human action", () => {
  assert(validateRequest(request()), ajv.errorsText(validateRequest.errors));
  for (const field of Object.keys(request())) { const p = request(); delete p[field]; assert.equal(validateRequest(p), false, field); }
  for (const field of ["manifest", "coverage", "verdicts", "evaluatedAt", "actor", "approvalGranted"]) assert.equal(validateRequest({ ...request(), [field]: {} }), false);
  const missing = request(); delete missing.proposal.auditability; assert.equal(validateRequest(missing), false);
  assert.equal(validateRequest({ ...request(), schemaVersion: 2 }), false); assert.equal(validateRequest({ ...request(), confirmation: "APPROVE" }), false);
});
test("optional supplement must use its own exact pin; numbers and digests are bounded", () => {
  const p = request(); p.proposal.auditability = { reviewId: id, reviewSha256: "7".repeat(64), decisionSupplementSha256: "8".repeat(64) };
  assert(validateRequest(p));
  for (const field of Object.keys(p.proposal.auditability)) { const q = structuredClone(p); delete q.proposal.auditability[field]; assert.equal(validateRequest(q), false); }
  for (const value of [-1, 9007199254740992, 1.5]) { const q = request(); q.proposal.reviewThroughNumber = value; assert.equal(validateRequest(q), false); }
  const q = request(); q.proposal.revision.requestSha256 = "not-a-digest"; assert.equal(validateRequest(q), false);
});
test("publication receipt exposes no source bodies or live verification/evaluation authority", () => {
  assert(validateReceipt(receipt()), ajv.errorsText(validateReceipt.errors));
  for (const field of Object.keys(receipt())) { const p = receipt(); delete p[field]; assert.equal(validateReceipt(p), false, field); }
  for (const field of ["actor", "sourceUrl", "candidate", "accessToken", "scenarios"]) assert.equal(validateReceipt({ ...receipt(), [field]: "private" }), false);
  for (const flag of ["evaluationReady", "externalSourceVerificationPerformed"]) assert.equal(validateReceipt({ ...receipt(), [flag]: true }), false);
  const p = receipt(); p.verificationGaps.pop(); assert.equal(validateReceipt(p), false);
  assert.equal(validateReceipt({ ...receipt(), policyVersion: "catalog-bootstrap-publication-1" }), false);
});
