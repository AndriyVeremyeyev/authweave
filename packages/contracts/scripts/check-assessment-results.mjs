import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertAssessmentResult } from "../tests/helpers/assessment-decision-result-spec.mjs";
import { orderedHash as hash } from "../tests/helpers/publication-decision-coverage-spec.mjs";

assert.equal(process.argv.length, 3, "Pass actual isolated Core assessment result samples.");
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result.v1.schema.json");
const samples = JSON.parse(await readFile(process.argv[2], "utf8")); assert(samples.length >= 3);
for (const sample of samples) {
  assert(validate(sample.receipt), ajv.errorsText(validate.errors));
  const stored = ajv.getSchema(`https://authweave.dev/contracts/application-identity-profile.v${sample.receipt.result.profileSchemaVersion}.schema.json`);
  assert(stored(sample.receipt.result.profile), ajv.errorsText(stored.errors)); assertAssessmentResult(sample);
  for (const change of [s => s.receipt.result.policy.componentVersions.hardChecks = "wrong-kernel", s => s.receipt.result.profileSha256 = "0".repeat(64),
    s => s.receipt.result.policySha256 = "0".repeat(64), s => s.receipt.result.requestSha256 = "0".repeat(64),
    s => s.receipt.result.catalog.reference.snapshotSha256 = "0".repeat(64), s => s.receipt.result.catalog.verificationGaps.pop(),
    s => s.receipt.result.decision.binding.inputs.weightsSha256 = "0".repeat(64), s => s.receipt.result.decision.shortlist.push("forged-option"),
    s => s.receipt.result.decisionApproved = true]) {
    const forged = structuredClone(sample); change(forged); forged.receipt.reference.resultSha256 = hash(forged.receipt.result);
    assert.throws(() => assertAssessmentResult(forged));
  }
}
const successor = samples.find(s => s.receipt.reference.version === 2); assert(successor);
const parent = samples.find(s => s.receipt.reference.resultId === successor.receipt.result.request.previousResult.resultId); assert(parent);
assert.deepEqual(successor.receipt.result.request.previousResult, parent.receipt.reference);
assert.notEqual(successor.receipt.result.request.catalog.snapshotId, parent.receipt.result.request.catalog.snapshotId);
assert.notEqual(successor.receipt.result.profileSha256, parent.receipt.result.profileSha256);
assert(samples.some(s => s.receipt.result.profileSchemaVersion === 1));
assert(samples.some(s => s.decisionInputs.auditability === null)); assert(samples.some(s => s.decisionInputs.auditability !== null));
console.log(`Verified ${samples.length} actual assessment results: owned profile/catalog pins, policy/weights/clock hashes, scoring/evidence invariants and explicit result history.`);
