import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const samplePaths = process.argv.slice(2);
assert.ok(samplePaths.length > 0, "Pass the samples exported by the current Core API integration test run.");
const schemasRoot = fileURLToPath(new URL("../schemas/", import.meta.url));
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
for (const file of await readdir(schemasRoot)) {
  if (file.endsWith(".schema.json")) {
    ajv.addSchema(JSON.parse(await readFile(path.join(schemasRoot, file), "utf8")));
  }
}

const samples = (await Promise.all(samplePaths.map(async (file) => {
  const values = JSON.parse(await readFile(file, "utf8"));
  assert.ok(Array.isArray(values) && values.length > 0, `${file}: HTTP samples must not be empty.`);
  return values;
}))).flat();
assert.ok(Array.isArray(samples) && samples.length > 0, "HTTP contract samples must not be empty.");
const covered = new Set();
for (const { name, schema, valid, payload } of samples) {
  assert.equal(typeof valid, "boolean", `${name}: expected validity is required`);
  const versionedName = /\.v[0-9]+$/.test(schema) ? schema : `${schema}.v1`;
  const validate = ajv.getSchema(`https://authweave.dev/contracts/${versionedName}.schema.json`);
  assert.ok(validate, `${name}: unknown schema ${schema}`);
  assert.equal(validate(payload), valid,
    `${name} (${schema}): ${ajv.errorsText(validate.errors, { separator: "\n" })}`);
  covered.add(`${schema}:${valid}`);
}
for (const required of ["assessment-response:true", "core-problem:true",
  "catalog-bootstrap-review-request:true", "catalog-bootstrap-review-request:false",
  "catalog-bootstrap-review:true", "catalog-bootstrap-review:false",
  "catalog-fact-review-request:true", "catalog-fact-review-request:false",
  "catalog-fact-review:true", "catalog-fact-review:false",
  "catalog-fact-review-page:true", "catalog-fact-review-page:false",
  "catalog-fact-review-summary-page:true", "catalog-fact-review-summary-page:false",
  "catalog-proposal-evidence-page.v2:true", "catalog-proposal-evidence-page.v2:false",
  "provider-catalog-draft:true", "provider-catalog-draft:false",
  "catalog-draft-validation:true", "catalog-draft-validation:false",
  "catalog-change-preview-request:true", "catalog-change-preview-request:false",
  "catalog-change-preview:true", "catalog-change-preview:false",
  "catalog-proposal-snapshot:true", "catalog-proposal-snapshot:false",
  "catalog-proposal-revision-page:true", "catalog-proposal-revision-page:false",
  "catalog-proposal-event-page:true", "catalog-proposal-event-page:false",
  "catalog-impact-preview:true", "catalog-impact-preview:false",
  "catalog-scenario-impact:true", "catalog-scenario-impact:false",
  "catalog-impact-report:true", "catalog-impact-report:false",
  "catalog-impact-report-page:true", "catalog-impact-report-page:false",
  "catalog-impact-report-event:true", "catalog-impact-report-event:false",
  "capability-preflight:true", "eligibility-preflight:true", "architecture-pattern-preflight:true",
  "architecture-prerequisite-request:true", "architecture-prerequisite-request:false",
  "architecture-prerequisite-preview:true", "architecture-prerequisite-preview:false",
  "eligibility-preflight.v2:true", "eligibility-preflight.v2:false",
  "assessment-revision-page:true", "assessment-event-page:true",
  "assessment-response.v2:true", "assessment-revision-page.v2:true",
  "assessment-response.v3:true", "assessment-revision-page.v3:true",
  "update-assessment-profile-request.v3:true", "update-assessment-profile-request.v3:false",
  "eligibility-preflight.v3:true", "eligibility-preflight.v3:false",
  "assessment-response.v4:true", "assessment-revision-page.v4:true",
  "update-assessment-profile-request.v4:true", "update-assessment-profile-request.v4:false",
  "eligibility-preflight.v4:true", "eligibility-preflight.v4:false",
  "assessment-response.v5:true", "assessment-revision-page.v5:true",
  "assessment-response.v6:true", "assessment-response.v6:false",
  "assessment-revision-page.v6:true", "assessment-revision-page.v6:false",
  "update-assessment-profile-request.v6:true", "update-assessment-profile-request.v6:false",
  "assessment-list-page:true",
  "hard-constraint-preflight:true",
  "synthetic-comparison:true",
  "weighted-comparison-request:true", "weighted-comparison-preview:true",
  "weight-sensitivity-request:true", "weight-sensitivity-preview:true",
  "update-assessment-profile-request.v5:true", "update-assessment-profile-request.v5:false",
  "usage-planning-preflight:true", "usage-planning-preflight:false",
  "auditability-capability-preflight:true", "auditability-capability-preflight:false",
  "update-assessment-profile-request.v2:true", "update-assessment-profile-request.v2:false",
  "update-assessment-profile-request:true", "update-assessment-profile-request:false"]) {
  assert.ok(covered.has(required), `Missing HTTP contract coverage: ${required}`);
}
console.log(`Validated ${samples.length} actual HTTP request/response samples against JSON Schema.`);
