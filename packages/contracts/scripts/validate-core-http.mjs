import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { auditabilityPreviewFromCore } from "../../../apps/web/src/lib/assessment/auditability-preview.ts";

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
let auditabilityConsumerSamples = 0;
let auditabilityDraftSamples = 0;
let auditabilityReviewSamples = 0;
// Independent implementation of the documented unordered-collection canonicalization.
const ordered = value => Array.isArray(value) ? value.map(ordered).sort((a, b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0) :
  value && typeof value === "object" ? Object.fromEntries(Object.keys(value).sort().map(key => [key, ordered(value[key])])) : value;
const digest = value => createHash("sha256").update(JSON.stringify(ordered(value))).digest("hex");
const reviewRequests = new Map(samples.filter(s => s.schema === "catalog-auditability-review-request" && s.valid).map(s => [digest(s.payload), s.payload]));
for (const { name, schema, valid, payload } of samples) {
  assert.equal(typeof valid, "boolean", `${name}: expected validity is required`);
  const versionedName = /\.v[0-9]+$/.test(schema) ? schema : `${schema}.v1`;
  const validate = ajv.getSchema(`https://authweave.dev/contracts/${versionedName}.schema.json`);
  assert.ok(validate, `${name}: unknown schema ${schema}`);
  assert.equal(validate(payload), valid,
    `${name} (${schema}): ${ajv.errorsText(validate.errors, { separator: "\n" })}`);
  covered.add(`${schema}:${valid}`);
  if (schema === "catalog-auditability-review" && valid) {
    const request = reviewRequests.get(payload.reviewSha256);
    assert.ok(request, `${name}: receipt must bind an actual supplied request`);
    assert.equal(payload.reviewId, request.reviewId, `${name}: review key binding`);
    const baseHash = digest(request.candidate.baseDraft), supplementHash = digest(request.candidate.auditabilityDraft);
    assert.equal(baseHash, request.expectedBaseContentSha256, `${name}: independent base digest`);
    assert.equal(baseHash, payload.baseContentSha256, `${name}: receipt base binding`);
    assert.equal(supplementHash, request.expectedAuditabilityContentSha256, `${name}: independent supplement digest`);
    assert.equal(supplementHash, payload.auditabilityContentSha256, `${name}: receipt supplement binding`);
    const bindings = request.candidate.auditabilityDraft.options.flatMap(option => option.facts.map(fact => ({ scope: "AUDITABILITY_SOURCE_REVIEW_TARGET_V1",
      baseContentSha256: baseHash, auditabilityContentSha256: supplementHash, optionScope: option.scope, fact })));
    assert.equal(digest(bindings), payload.targetSetSha256, `${name}: complete target-set binding`);
    assert.equal(payload.targetSetSha256, request.expectedTargetSetSha256, `${name}: expected target-set binding`);
    const expected = new Set(bindings.map(binding => `${binding.optionScope.optionId}:${binding.fact.criterion}:${digest(binding)}`));
    const actual = request.observations.map(o => `${o.optionId}:${o.criterion}:${o.expectedTargetSha256}`);
    assert.equal(actual.length, new Set(actual).size, `${name}: no duplicated observations`);
    assert.deepEqual(new Set(actual), expected, `${name}: exact manual target inventory`);
    assert.equal(payload.factCount, actual.length, `${name}: receipt fact count`);
    assert.equal(payload.optionCount, request.candidate.auditabilityDraft.options.length, `${name}: receipt option count`);
    assert.equal(payload.catalogVersion, request.candidate.baseDraft.catalogVersion, `${name}: base version`);
    assert.equal(payload.evidenceVersion, request.candidate.auditabilityDraft.evidenceVersion, `${name}: evidence version`);
    const counts = verdict => request.observations.filter(o => o.verdict === verdict).length;
    assert.deepEqual(payload.counts, { supporting: counts("SOURCE_SUPPORTS_CLAIM"), contradicting: counts("SOURCE_DOES_NOT_SUPPORT_CLAIM"), insufficient: counts("INSUFFICIENT_EVIDENCE") }, `${name}: observed verdict parity`);
    auditabilityReviewSamples++;
  }
  if (schema === "catalog-auditability-draft-validation" && valid) {
    assert.equal(payload.targetCount, payload.targets.length, `${name}: target count parity`);
    assert.equal(payload.evaluatedAt, payload.baseValidation.evaluatedAt, `${name}: base clock binding`);
    const bindings = payload.targets.map(target => {
      assert.equal(target.baseContentSha256, payload.baseValidation.contentSha256, `${name}: target base digest binding`);
      assert.equal(target.auditabilityContentSha256, payload.contentSha256, `${name}: target supplement digest binding`);
      assert.equal(target.factPath, `auditability.${target.fact.criterion}`, `${name}: target path binding`);
      const binding = { scope: "AUDITABILITY_SOURCE_REVIEW_TARGET_V1", baseContentSha256: target.baseContentSha256,
        auditabilityContentSha256: target.auditabilityContentSha256, optionScope: target.scope, fact: target.fact };
      assert.equal(target.targetSha256, digest(binding), `${name}: independently recomputed target digest`);
      return binding;
    });
    assert.equal(payload.reviewTargetSetSha256, payload.status === "VALID_DRAFT" ? digest(bindings) : null, `${name}: exact target set digest`);
    auditabilityDraftSamples++;
  }
  if (schema === "auditability-capability-preflight" && valid) {
    const preview = auditabilityPreviewFromCore(payload, { workspaceId: payload.workspaceId,
      assessmentId: payload.assessmentId, expectedVersion: payload.assessmentVersion,
      values: { criticality: payload.criticality, ...payload.requirements } });
    assert.equal(preview.candidates.length, payload.candidates.length, `${name}: BFF candidate parity`);
    auditabilityConsumerSamples++;
  }
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
  "catalog-auditability-draft:true", "catalog-auditability-draft:false",
  "catalog-auditability-draft-validation-request:true", "catalog-auditability-draft-validation-request:false",
  "catalog-auditability-draft-validation:true", "catalog-auditability-draft-validation:false",
  "catalog-auditability-review-request:true", "catalog-auditability-review-request:false",
  "catalog-auditability-review:true", "catalog-auditability-review:false",
  "catalog-auditability-review-problem:true", "catalog-auditability-review-problem:false",
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
  "catalog-auditability-regression-check:true", "catalog-auditability-regression-check:false",
  "catalog-profile-impact-coverage:true", "catalog-profile-impact-coverage:false",
  "update-assessment-profile-request.v2:true", "update-assessment-profile-request.v2:false",
  "update-assessment-profile-request:true", "update-assessment-profile-request:false"]) {
  assert.ok(covered.has(required), `Missing HTTP contract coverage: ${required}`);
}
console.log(`Validated ${samples.length} actual HTTP request/response samples against JSON Schema.`);
assert.ok(auditabilityConsumerSamples > 0, "Actual auditability HTTP samples must reach the strict BFF consumer.");
console.log(`Validated ${auditabilityConsumerSamples} actual auditability HTTP responses with the BFF evidence-policy guard.`);
assert.ok(auditabilityDraftSamples > 0, "Actual auditability draft responses must reach independent digest checks.");
console.log(`Verified ${auditabilityDraftSamples} actual auditability draft reports with independent target binding checks.`);
assert.ok(auditabilityReviewSamples > 0, "Actual immutable auditability reviews must reach independent request/target/receipt checks.");
console.log(`Verified ${auditabilityReviewSamples} actual auditability review receipts with independent request and target binding checks.`);
