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
let auditabilityImpactSamples = 0;
// Independent implementation of the documented unordered-collection canonicalization.
const ordered = value => Array.isArray(value) ? value.map(ordered).sort((a, b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0) :
  value && typeof value === "object" ? Object.fromEntries(Object.keys(value).sort().map(key => [key, ordered(value[key])])) : value;
const digest = value => createHash("sha256").update(JSON.stringify(ordered(value))).digest("hex");
const reviewRequests = new Map(samples.filter(s => s.schema === "catalog-auditability-review-request" && s.valid).map(s => [digest(s.payload), s.payload]));
const impactSuite = JSON.parse(await readFile(new URL("../../../services/core-api/src/main/resources/catalog/scoped-auditability-scenarios.v1.json", import.meta.url), "utf8"));
const impactBase = JSON.parse(await readFile(new URL("../../../services/core-api/src/main/resources/catalog/scoped-impact-scenarios.v1.json", import.meta.url), "utf8"));
const impactDefinitions = impactSuite.scenarios.map(input => {
  const profile = structuredClone(impactBase.find(s => s.id === input.scenarioId).profile);
  const requirements = { selectedCriteria: input.selectedCriteria, minimumRetentionDays: input.minimumRetentionDays };
  profile.security.auditabilityRequirements = requirements;
  return { scenarioId: input.scenarioId, criticality: profile.security.auditability, requirements, profileSha256: digest(profile) };
});
const nanos = value => {
  const match = /^(.*?)(?:\.(\d{1,9}))?Z$/.exec(value);
  assert.ok(match, "Use a UTC instant for independent impact freshness checks.");
  return BigInt(Date.parse(`${match[1]}Z`)) * 1_000_000n + BigInt((match[2] ?? "").padEnd(9, "0"));
};
function expectedImpactSide(request, optionId, criterion, requirements, at) {
  const option = request.candidate.auditabilityDraft.options.find(o => o.scope.optionId === optionId);
  assert.ok(option, "Impact scope must bind an actual reviewed option.");
  const fact = option.facts.find(f => f.criterion === criterion);
  const observation = request.observations.find(o => o.optionId === optionId && o.criterion === criterion);
  assert.equal(Boolean(fact), Boolean(observation), "Impact source observation completeness.");
  const freshness = !fact ? null : nanos(fact.evidence.observedAt) > nanos(at) ? "FUTURE" :
    nanos(fact.evidence.observedAt) < nanos(at) - 90n * 86400n * 1_000_000_000n ? "STALE" : "CURRENT";
  let reason, duration = null;
  if (!requirements.selectedCriteria.includes(criterion)) reason = "CRITERION_NOT_SELECTED";
  else if (!fact) reason = "FACT_MISSING";
  else if (freshness === "FUTURE") reason = "CLAIM_FROM_FUTURE";
  else if (freshness === "STALE") reason = "CLAIM_STALE";
  else if (fact.support === "UNKNOWN") reason = "CLAIM_UNKNOWN";
  else if (fact.conditions.length) reason = "CONDITIONS_UNVERIFIED";
  else if (fact.support === "UNSUPPORTED") reason = "CLAIM_UNAVAILABLE";
  else if (criterion !== "AUDIT_LOG_RETENTION") reason = "CLAIM_AVAILABLE";
  else if (fact.documentedMinimumRetentionDays === null) reason = "RETENTION_DURATION_UNKNOWN";
  else { duration = fact.documentedMinimumRetentionDays; reason = duration < requirements.minimumRetentionDays ? "RETENTION_BELOW_MINIMUM" : "RETENTION_MEETS_MINIMUM"; }
  const conditionalOutcome = reason === "CRITERION_NOT_SELECTED" ? "NOT_APPLIED" :
    ["CLAIM_UNAVAILABLE", "RETENTION_BELOW_MINIMUM"].includes(reason) ? "WOULD_VIOLATE" :
    ["CLAIM_AVAILABLE", "RETENTION_MEETS_MINIMUM"].includes(reason) ? "WOULD_SATISFY" : "INDETERMINATE";
  if (fact) assert.equal(observation.expectedTargetSha256, digest({ scope: "AUDITABILITY_SOURCE_REVIEW_TARGET_V1",
    baseContentSha256: digest(request.candidate.baseDraft), auditabilityContentSha256: digest(request.candidate.auditabilityDraft), optionScope: option.scope, fact }));
  return { conditionalOutcome, reason, factSha256: fact ? digest(fact) : null, targetSha256: observation?.expectedTargetSha256 ?? null,
    sourceVerdict: observation?.verdict ?? null, observedAt: fact?.evidence.observedAt ?? null, freshness,
    conditionsRecorded: Boolean(fact?.conditions.length), documentedMinimumRetentionDays: duration };
}
for (const { name, schema, valid, payload } of samples) {
  assert.equal(typeof valid, "boolean", `${name}: expected validity is required`);
  const versionedName = /\.v[0-9]+$/.test(schema) ? schema : `${schema}.v1`;
  const validate = ajv.getSchema(`https://authweave.dev/contracts/${versionedName}.schema.json`);
  assert.ok(validate, `${name}: unknown schema ${schema}`);
  assert.equal(validate(payload), valid,
    `${name} (${schema}): ${ajv.errorsText(validate.errors, { separator: "\n" })}`);
  covered.add(`${schema}:${valid}`);
  if (schema === "catalog-auditability-impact" && valid) {
    const before = reviewRequests.get(payload.beforeReview.reviewSha256), after = reviewRequests.get(payload.afterReview.reviewSha256);
    assert.ok(before && after, `${name}: both exact historical review requests must be supplied`);
    assert.equal(payload.beforeReview.reviewId, before.reviewId); assert.equal(payload.afterReview.reviewId, after.reviewId);
    assert.equal(digest(before.candidate.baseDraft), digest(after.candidate.baseDraft), `${name}: unchanged base scope`);
    assert.equal(payload.scenarioSetSha256, digest([payload.scenarioSetVersion, payload.profileSchemaVersion,
      payload.profileSchemaSha256, digest(impactBase), impactSuite, impactDefinitions]), `${name}: independent full scenario binding`);
    const criteria = ajv.getSchema("https://authweave.dev/contracts/catalog-auditability-draft.v1.schema.json").schema.$defs.criterion.enum;
    const inventory = new Set(), changedFacts = new Set(); let changedChecks = 0;
    for (const row of payload.scenarios) {
      const definition = impactDefinitions.find(d => d.scenarioId === row.scenarioId); assert.ok(definition);
      assert.equal(row.profileSha256, definition.profileSha256); assert.deepEqual(ordered(row.requirements), ordered(definition.requirements));
      const option = before.candidate.auditabilityDraft.options.find(o => o.scope.optionId === row.optionScope.optionId);
      assert.deepEqual(row.optionScope, option?.scope); const key = `${row.scenarioId}:${row.optionScope.optionId}`;
      assert.ok(!inventory.has(key), `${name}: no duplicated scenario scopes`); inventory.add(key);
      assert.deepEqual(row.checks.map(c => c.criterion), criteria, `${name}: full canonical criterion inventory`);
      for (const check of row.checks) {
        assert.deepEqual(check.before, expectedImpactSide(before, row.optionScope.optionId, check.criterion, row.requirements, payload.evaluatedAt));
        assert.deepEqual(check.after, expectedImpactSide(after, row.optionScope.optionId, check.criterion, row.requirements, payload.evaluatedAt));
        assert.equal(check.factChanged, check.before.factSha256 !== check.after.factSha256);
        assert.equal(check.conditionalResultChanged, check.before.conditionalOutcome !== check.after.conditionalOutcome ||
          check.before.reason !== check.after.reason || check.before.documentedMinimumRetentionDays !== check.after.documentedMinimumRetentionDays);
        if (check.factChanged) changedFacts.add(`${row.optionScope.optionId}:${check.criterion}`);
        if (check.conditionalResultChanged) changedChecks++;
      }
    }
    assert.deepEqual(inventory, new Set(impactDefinitions.flatMap(d => before.candidate.auditabilityDraft.options.map(o => `${d.scenarioId}:${o.scope.optionId}`))));
    assert.equal(payload.checkedCases, inventory.size); assert.equal(payload.checkedCriteria, inventory.size * criteria.length);
    assert.equal(payload.changedFacts, changedFacts.size); assert.equal(payload.changedChecks, changedChecks);
    assert.equal(payload.analysisSha256, digest([payload.policyVersion, payload.evaluatedAt, payload.scenarioSetVersion, payload.scenarioSetSha256,
      payload.profileSchemaVersion, payload.profileSchemaSha256, payload.beforeReview, payload.afterReview, payload.scenarios]), `${name}: independent complete analysis digest`);
    auditabilityImpactSamples++;
  }
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
  "catalog-auditability-impact-request:true", "catalog-auditability-impact-request:false",
  "catalog-auditability-impact:true", "catalog-auditability-impact:false",
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
  "assessment-context-list-page:true", "assessment-context-list-page:false",
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
assert.ok(auditabilityImpactSamples > 0, "Actual conditional auditability comparisons must reach independent source/scenario/outcome checks.");
console.log(`Verified ${auditabilityImpactSamples} conditional auditability impact reports with independent scenario, source, outcome and hash checks.`);
