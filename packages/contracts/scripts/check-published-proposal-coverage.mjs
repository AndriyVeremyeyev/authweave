import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertProposalCalculation } from "../tests/helpers/publication-proposal-coverage-spec.mjs";

const file = process.argv[2]; assert(file, "Provide actual Core published/proposal coverage samples");
const schemas = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const name of await readdir(schemas)) if (name.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(name, schemas), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/publication-proposal-decision-coverage.v1.schema.json");
const samples = JSON.parse(await readFile(file, "utf8")); assert.equal(samples.length, 4);
for (const sample of samples) {
  assert(validate(sample.check), ajv.errorsText(validate.errors)); assertProposalCalculation(sample);
  // Correct-looking checksum substitutions still must match originating inputs and actual calculations.
  for (const change of [s => s.check.beforeProofSha256 = "0".repeat(64), s => s.check.after.revision.requestSha256 = "0".repeat(64),
    s => s.check.scenarios[0].impactSha256 = "0".repeat(64), s => s.check.scenarios[0].beforeUnknownFindings++,
    s => s.check.afterCatalogSha256 = "0".repeat(64), s => s.check.candidateClaims.supporting++]) {
    const changed = structuredClone(sample); change(changed); assert.throws(() => assertProposalCalculation(changed));
  }
}
assert(samples[0].check.candidateClaims.allRecordedClaimsSupportedAndCurrent); assert(samples[0].check.scenarios.some(s => s.decisionOutcomesChanged));
assert(samples[1].check.candidateClaims.contradicted > 0); assert(samples[2].check.candidateClaims.unreviewed > 0);
assert.equal(samples[3].check.candidateClaims.stale, samples[3].check.candidateClaims.recorded);
process.stdout.write(`Validated ${samples.length} actual published/proposal calculations: exact source pins, 4 profiles / 34 routes, full impact hashes and original claim freshness; no publication authority.\n`);
