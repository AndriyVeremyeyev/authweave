import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertBootstrapPublication } from "../tests/helpers/bootstrap-publication-spec.mjs";
import { orderedHash } from "../tests/helpers/publication-decision-coverage-spec.mjs";

assert.equal(process.argv.length, 3, "Pass the actual isolated Core bootstrap publication samples.");
const ajv = new Ajv2020({ allErrors: true, strict: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const samples = JSON.parse(await readFile(process.argv[2], "utf8"));
assert.equal(samples.length, 2, "Require actual base-only and base-plus-auditability publications.");
for (const sample of samples) {
  for (const [schema, value] of [["catalog-bootstrap-publication-request", sample.proof.request], ["catalog-bootstrap-publication", sample.receipt],
    ["published-provider-catalog-snapshot", sample.snapshot], ["publication-decision-coverage", sample.proof.coverage], ["provider-catalog-draft", sample.candidate]]) {
    const validate = ajv.getSchema(`https://authweave.dev/contracts/${schema}.v1.schema.json`);
    assert(validate(value), `${schema}: ${ajv.errorsText(validate.errors)}`);
  }
  assertBootstrapPublication(sample);
  // Checksummed substitutions must still fail independent originating-input bindings.
  for (const mutate of [s => s.proof.requestSha256 = "0".repeat(64), s => s.proof.request.source.reviewSha256 = "0".repeat(64),
    s => s.proof.coverage.evaluatedAt = "2026-10-10T00:00:00Z", s => s.proof.coverage.manifestSha256 = "0".repeat(64),
    s => s.proof.coverage.scenarios[0].afterResultSha256 = "0".repeat(64), s => s.snapshot.contentSha256 = "0".repeat(64),
    s => s.receipt.evaluationReady = true, s => s.receipt.verificationGaps.pop()]) {
    const forged = structuredClone(sample); mutate(forged);
    forged.proofSha256 = orderedHash(forged.proof); forged.receipt.proofSha256 = forged.proofSha256;
    assert.throws(() => assertBootstrapPublication(forged));
  }
}
assert(samples.some(s => s.auditabilitySupplement === null)); assert(samples.some(s => s.auditabilitySupplement !== null));
console.log(`Verified ${samples.length} actual atomic bootstrap publications with independent request, source, manifest, proof and fresh coverage bindings.`);
