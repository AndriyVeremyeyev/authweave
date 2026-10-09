import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertCoverage } from "../tests/helpers/publication-decision-coverage-spec.mjs";

const file = process.argv[2];
assert(file, "Provide actual Core decision publication coverage samples");
const schema = JSON.parse(await readFile(new URL("../schemas/publication-decision-coverage.v1.schema.json", import.meta.url), "utf8"));
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv); const validate = ajv.compile(schema);
const samples = JSON.parse(await readFile(file, "utf8")); assert.equal(samples.length, 3);
for (const sample of samples) { assert(validate(sample), ajv.errorsText(validate.errors)); assertCoverage(sample); }
assert(samples[0].scenarios.every(s => s.beforeUnknownFindings > 0 && !s.decisionOutcomesChanged));
assert(samples[2].scenarios.some(s => s.decisionOutcomesChanged));
assert(samples.every(s => !s.storedSourceReviewsVerified && !s.publicationReady));
process.stdout.write(`Validated ${samples.length} actual Core whole-decision coverage calculations; 4 profiles / 34 inputs each, no publication authority.\n`);
