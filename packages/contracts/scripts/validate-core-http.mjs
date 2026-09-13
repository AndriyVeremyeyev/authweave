import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const samplesPath = process.argv[2];
assert.ok(samplesPath, "Pass the samples exported by the current Core API integration test run.");
const schemasRoot = fileURLToPath(new URL("../schemas/", import.meta.url));
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
for (const file of await readdir(schemasRoot)) {
  if (file.endsWith(".schema.json")) {
    ajv.addSchema(JSON.parse(await readFile(path.join(schemasRoot, file), "utf8")));
  }
}

const samples = JSON.parse(await readFile(samplesPath, "utf8"));
assert.ok(Array.isArray(samples) && samples.length > 0, "HTTP contract samples must not be empty.");
const covered = new Set();
for (const { name, schema, valid, payload } of samples) {
  assert.equal(typeof valid, "boolean", `${name}: expected validity is required`);
  const validate = ajv.getSchema(`https://authweave.dev/contracts/${schema}.v1.schema.json`);
  assert.ok(validate, `${name}: unknown schema ${schema}`);
  assert.equal(validate(payload), valid,
    `${name} (${schema}): ${ajv.errorsText(validate.errors, { separator: "\n" })}`);
  covered.add(`${schema}:${valid}`);
}
for (const required of ["assessment-response:true", "core-problem:true",
  "capability-preflight:true", "eligibility-preflight:true",
  "assessment-revision-page:true", "assessment-event-page:true",
  "update-assessment-profile-request:true", "update-assessment-profile-request:false"]) {
  assert.ok(covered.has(required), `Missing HTTP contract coverage: ${required}`);
}
console.log(`Validated ${samples.length} actual HTTP request/response samples against JSON Schema.`);
