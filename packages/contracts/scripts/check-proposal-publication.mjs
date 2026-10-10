import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertProposalPublication } from "../tests/helpers/proposal-publication-spec.mjs";
import { orderedHash } from "../tests/helpers/publication-decision-coverage-spec.mjs";

assert.equal(process.argv.length, 3, "Pass actual isolated Core successor publication samples.");
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const samples = JSON.parse(await readFile(process.argv[2], "utf8")); assert.equal(samples.length, 2);
for (const sample of samples) {
  for (const [schema, value] of [["catalog-proposal-publication-request", sample.proof.request], ["catalog-proposal-publication", sample.receipt],
    ["published-provider-catalog-snapshot", sample.snapshot], ["publication-proposal-decision-coverage", sample.proof.coverage]]) {
    const validate = ajv.getSchema(`https://authweave.dev/contracts/${schema}.v1.schema.json`); assert(validate(value), ajv.errorsText(validate.errors));
  }
  assertProposalPublication(sample);
  for (const change of [s => s.proof.requestSha256 = "0".repeat(64), s => s.proof.request.proposal.reviewSetSha256 = "0".repeat(64),
    s => s.proof.coverage.beforeProofSha256 = "0".repeat(64), s => s.proof.coverage.scenarios[0].impactSha256 = "0".repeat(64),
    s => s.snapshot.contentSha256 = "0".repeat(64), s => s.snapshot.previousSnapshot.snapshotSha256 = "0".repeat(64),
    s => s.receipt.evaluationReady = true, s => s.receipt.verificationGaps.pop()]) {
    const forged = structuredClone(sample); change(forged); forged.proofSha256 = orderedHash(forged.proof); forged.receipt.proofSha256 = forged.proofSha256;
    assert.throws(() => assertProposalPublication(forged));
  }
}
assert(samples.some(s => s.calculation.after.auditability === null)); assert(samples.some(s => s.calculation.after.auditability !== null));
console.log(`Verified ${samples.length} actual first successor publications: immutable parent/proposal/review pins, manifest/proof hashes and full decision coverage.`);
