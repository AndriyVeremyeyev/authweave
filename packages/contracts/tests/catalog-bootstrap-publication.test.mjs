import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { verificationGaps, manifestSha256 } from "./helpers/publication-decision-coverage-spec.mjs";

const ajv = new Ajv2020({ allErrors: true, strict: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const requestSchema = ajv.getSchema("https://authweave.dev/contracts/catalog-bootstrap-publication-request.v1.schema.json");
const receiptSchema = ajv.getSchema("https://authweave.dev/contracts/catalog-bootstrap-publication.v1.schema.json");
const id = "00000000-0000-4000-8000-000000000001";
const source = { reviewId: id, reviewSha256: "1".repeat(64), decisionCatalogSha256: "2".repeat(64), auditability: null };
const request = () => ({ schemaVersion: 1, publicationId: id, source: structuredClone(source), confirmation: "PUBLISH_REVIEWED_BOOTSTRAP" });
const receipt = () => ({ snapshot: { snapshotId: id, catalogVersion: "fictional-published-1", snapshotSha256: "3".repeat(64) },
  decisionId: id, publishedAt: "2026-10-09T20:00:00Z", source: structuredClone(source), policyVersion: "catalog-bootstrap-publication-1",
  proofSha256: "4".repeat(64), coverageManifestSha256: manifestSha256, coverageScope: "DECLARED_DECISION_RULES_ONLY",
  verificationGaps: structuredClone(verificationGaps), publicationRecorded: true, evaluationReady: false, externalSourceVerificationPerformed: false });

test("bootstrap request is an exact source reference and human command, not a transported approval token", () => {
  assert(requestSchema(request()), ajv.errorsText(requestSchema.errors));
  const audit = request(); audit.source.auditability = { reviewId: id, reviewSha256: "5".repeat(64), decisionSupplementSha256: "6".repeat(64) };
  assert(requestSchema(audit));
  for (const field of ["snapshot", "coverage", "observations", "evaluatedAt", "role", "publicationReady"]) assert.equal(requestSchema({ ...request(), [field]: true }), false, field);
  for (const field of ["schemaVersion", "publicationId", "source", "confirmation"]) { const value = request(); delete value[field]; assert.equal(requestSchema(value), false, field); }
  assert.equal(requestSchema({ ...request(), confirmation: "APPROVE" }), false);
  assert.equal(requestSchema({ ...request(), schemaVersion: 2 }), false);
  const unpinned = request(); delete unpinned.source.decisionCatalogSha256; assert.equal(requestSchema(unpinned), false);
});
test("recorded publication keeps live verification/evaluation closed and all 22 gaps explicit", () => {
  assert(receiptSchema(receipt()), ajv.errorsText(receiptSchema.errors));
  for (const field of Object.keys(receipt())) { const value = receipt(); delete value[field]; assert.equal(receiptSchema(value), false, field); }
  for (const flag of ["evaluationReady", "externalSourceVerificationPerformed"]) assert.equal(receiptSchema({ ...receipt(), [flag]: true }), false);
  for (const field of ["actorSubject", "actorIssuer", "candidate", "sourceUrl", "accessToken", "scenarios"]) assert.equal(receiptSchema({ ...receipt(), [field]: "private" }), false);
  assert.equal(receiptSchema({ ...receipt(), publicationRecorded: false }), false);
  assert.equal(receiptSchema({ ...receipt(), policyVersion: "future" }), false);
  assert.equal(receiptSchema({ ...receipt(), coverageScope: "FULL_DEPLOYMENT_VERIFIED" }), false);
  const hiddenGap = receipt(); hiddenGap.verificationGaps.pop(); assert.equal(receiptSchema(hiddenGap), false);
});
