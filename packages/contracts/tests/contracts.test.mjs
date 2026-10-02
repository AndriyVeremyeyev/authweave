import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

import {
  assertResultMatchesOperation,
  operationErrorCodes,
} from "../src/operation-result-guards.mjs";

const contractsRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const schemasRoot = path.join(contractsRoot, "schemas");
const fixturesRoot = path.join(contractsRoot, "tests", "fixtures");

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

for (const schemaFile of await readdir(schemasRoot)) {
  if (schemaFile.endsWith(".schema.json")) {
    ajv.addSchema(await readJson(path.join(schemasRoot, schemaFile)));
  }
}

const requestSchemaId =
  "https://authweave.dev/contracts/requirements-extraction-request.v1.schema.json";
const resultSchemaId =
  "https://authweave.dev/contracts/requirements-extraction-result.v1.schema.json";
const problemSchemaId =
  "https://authweave.dev/contracts/operation-problem.v1.schema.json";
const assessmentResponseSchemaId =
  "https://authweave.dev/contracts/assessment-response.v1.schema.json";
const updateAssessmentProfileSchemaId =
  "https://authweave.dev/contracts/update-assessment-profile-request.v1.schema.json";
const coreProblemSchemaId =
  "https://authweave.dev/contracts/core-problem.v1.schema.json";

const validateRequest = ajv.getSchema(requestSchemaId);
const validateResult = ajv.getSchema(resultSchemaId);
const validateProblem = ajv.getSchema(problemSchemaId);
const validateAssessmentResponse = ajv.getSchema(assessmentResponseSchemaId);
const validateUpdateAssessmentProfile = ajv.getSchema(updateAssessmentProfileSchemaId);
const validateCoreProblem = ajv.getSchema(coreProblemSchemaId);

test("profile v6 coverage contract inventories every semantic input and rejects readiness promotion or mixed unperformed state", async () => {
  const schema = await readJson(path.join(schemasRoot, "catalog-profile-impact-coverage.v1.schema.json"));
  const validate = ajv.getSchema(schema.$id), paths = new Set();
  async function walk(document, node, prefix) {
    while (node.$ref) {
      const [file, pointer] = node.$ref.split("#");
      if (file) { assert.equal(file, "./application-identity-profile.v5.schema.json"); document = await readJson(path.join(schemasRoot, file)); }
      node = pointer.split("/").slice(1).reduce((value, part) => value[part.replaceAll("~1", "/").replaceAll("~0", "~")], document);
    }
    if (node.properties && prefix !== "operations.usagePlanning.volumes")
      await Promise.all(Object.entries(node.properties).map(([key, value]) => walk(document, value, prefix ? `${prefix}.${key}` : key)));
    else paths.add(prefix);
  }
  const profile = await readJson(path.join(schemasRoot, "application-identity-profile.v6.schema.json"));
  await walk(profile, profile, ""); assert.equal(paths.size, 34);
  assert.deepEqual([...paths].sort(), [...schema.$defs.profilePath.enum].sort());
  const empty = Object.fromEntries(Object.entries(schema.properties).filter(([, value]) => Object.hasOwn(value, "const")).map(([key, value]) => [key, value.const]));
  Object.assign(empty, { status: "NOT_CHECKED", evaluatedAt: null, baseScenarioSetSha256: null, scenarioSetSha256: null, catalogCoverageSha256: null,
    auditabilityRegression: null, dimensions: [], verificationGaps: [], unexercisedFactPaths: [],
    checkedDimensions: 0, conditionalDimensions: 0, patternDimensions: 0, auditabilityDimensions: 0, scopeOnlyDimensions: 0, deferredDimensions: 0, missingRules: 0 });
  assert.equal(validate(empty), true, validationMessage(validate));
  for (const key of schema.required) { const invalid = structuredClone(empty); delete invalid[key]; assert.equal(validate(invalid), false, key); }
  for (const flag of ["candidateAuditabilityChangesEvaluated", "coverageComplete", "configurationVerified", "complianceVerified", "storedReportVerified", "sourceVerificationPerformed",
    "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady"]) {
    assert.equal(empty[flag], false); assert.equal(validate({ ...empty, [flag]: true }), false);
  }
  for (const mutate of [s => { s.status = "COMPLETE"; }, s => { s.status = "INCOMPLETE"; }, s => { s.evaluatedAt = "2026-09-12T12:00:00Z"; },
    s => { s.catalogCoverageSha256 = "2".repeat(64); }, s => { s.profileSchemaVersion = 5; }, s => { s.checkedDimensions = 1; },
    s => { s.declaredProfileInputs = 32; }, s => { s.actor = "synthetic"; }, s => { s.candidate = {}; }]) {
    const invalid = structuredClone(empty); mutate(invalid); assert.equal(validate(invalid), false);
  }
  const dimension = ajv.getSchema(`${schema.$id}#/$defs/dimension`);
  const row = { scenarioId: "b2b-saas-scoped", profilePath: "security.auditabilityRequirements.minimumRetentionDays", boundary: "AUDITABILITY",
    state: "SYNTHETIC_AUDITABILITY_RULE_PRESENT", ruleCount: 1, activeFactRuleCount: 0, activeAuditabilityRuleCount: 1, factPaths: [], evidenceCriteria: ["AUDIT_LOG_RETENTION"] };
  assert.equal(dimension(row), true, validationMessage(dimension));
  for (const mutate of [r => { r.factPaths = ["facts.SCIM"]; }, r => { r.activeFactRuleCount = 1; }, r => { r.evidenceCriteria = ["AUDIT_LOG_EXPORT"]; },
    r => { r.state = "CONDITIONAL_RULE_PRESENT"; }, r => { r.scenarioId = "foreign"; }, r => { r.observedAt = "2026-09-12T12:00:00Z"; }]) {
    const invalid = structuredClone(row); mutate(invalid); assert.equal(dimension(invalid), false);
  }
  assert.equal(dimension({ ...row, state: "SCOPE_GUARD_ONLY", activeAuditabilityRuleCount: 0, evidenceCriteria: [] }), true);
});

test("supplemental auditability scenarios bind frozen profiles and compile explicit valid v6 inputs without migration", async () => {
  const sourceRoot = path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog");
  const suite = await readJson(path.join(sourceRoot, "scoped-auditability-scenarios.v1.json"));
  const base = await readJson(path.join(sourceRoot, "scoped-impact-scenarios.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-scoped-auditability-scenarios.v1.schema.json");
  const profile = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v6.schema.json");
  const ordered = value => Array.isArray(value) ? value.map(ordered).sort((a, b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0) :
    value && typeof value === "object" ? Object.fromEntries(Object.keys(value).sort().map(key => [key, ordered(value[key])])) : value;
  assert.equal(validate(suite), true, validationMessage(validate));
  assert.equal(suite.baseScenarioSetSha256, createHash("sha256").update(JSON.stringify(ordered(base))).digest("hex"));
  const before = structuredClone(base), exercised = new Set();
  for (const input of suite.scenarios) {
    const original = base.find(s => s.id === input.scenarioId); assert.equal(original.profileSchemaVersion, 5);
    assert.equal(original.profile.security.auditability, "REQUIRED"); assert.equal(original.profile.security.auditabilityRequirements, undefined);
    const compiled = structuredClone(original.profile);
    compiled.security.auditabilityRequirements = { selectedCriteria: input.selectedCriteria, minimumRetentionDays: input.minimumRetentionDays };
    assert.equal(profile(compiled), true, validationMessage(profile));
    input.selectedCriteria.forEach(criterion => exercised.add(criterion));
  }
  assert.equal(exercised.size, 6); assert.deepEqual(base, before);
  for (const mutate of [s => s.scenarios.pop(), s => s.scenarios.push(structuredClone(s.scenarios[0])),
    s => { s.scenarios[1].scenarioId = s.scenarios[0].scenarioId; }, s => { s.schemaVersion = 2; },
    s => { s.baseScenarioSetSha256 = "bad"; }, s => { s.baseScenarioSetVersion = "other"; },
    s => { s.scenarios[0].selectedCriteria = []; }, s => { s.scenarios[0].selectedCriteria.push(s.scenarios[0].selectedCriteria[0]); },
    s => { s.scenarios[0].minimumRetentionDays = null; }, s => { s.scenarios[1].minimumRetentionDays = 30; },
    s => { s.scenarios[0].minimumRetentionDays = 36501; }, s => { s.coverageComplete = true; }]) {
    const invalid = structuredClone(suite); mutate(invalid); assert.equal(validate(invalid), false);
  }
});

test("auditability regression summary is bounded, body-free, canonically reasoned and cannot promote authority", async () => {
  const schema = await readJson(path.join(schemasRoot, "catalog-auditability-regression-check.v1.schema.json"));
  const validate = ajv.getSchema(schema.$id);
  const summary = Object.fromEntries(Object.entries(schema.properties).filter(([, property]) => Object.hasOwn(property, "const")).map(([key, property]) => [key, property.const]));
  const codes = ["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED", "REQUIREMENT_UNKNOWN", "AUDIT_INTENT_UNCLEAR", "AUDIT_SCOPE_UNKNOWN", "CRITERION_NOT_SELECTED",
    "EVIDENCE_MISSING", "EVIDENCE_UNREVIEWED", "EVIDENCE_FROM_FUTURE", "EVIDENCE_STALE", "CAPABILITY_UNKNOWN", "CAPABILITY_UNAVAILABLE",
    "DOCUMENTED_CAPABILITY_AVAILABLE", "RETENTION_DURATION_UNKNOWN", "RETENTION_BELOW_MINIMUM", "RETENTION_MEETS_MINIMUM"];
  const counts = [0, 0, 0, 0, 0, 15, 3, 19, 0, 0, 0, 4, 25, 0, 4, 2];
  const criteria = ["AUTHENTICATION_SUCCESS_EVENTS", "AUTHENTICATION_FAILURE_EVENTS", "ADMINISTRATIVE_CHANGE_EVENTS", "PROVISIONING_CHANGE_EVENTS", "AUDIT_LOG_EXPORT", "AUDIT_LOG_RETENTION"];
  Object.assign(summary, { evaluatedAt: "2026-09-12T12:00:00Z", scenarioSetSha256: "1".repeat(64), evidenceSha256: "2".repeat(64), analysisSha256: "3".repeat(64),
    definitionsSha256: "4".repeat(64), baseCatalogVersion: "synthetic-2026-09-12.4", evidenceVersion: "synthetic-auditability-2026-09-12.1", checkedScopes: 3,
    checkedCases: 12, checkedCriteria: 72, outcomes: { pass: 27, fail: 8, unknown: 22, notApplied: 15 },
    candidates: { matchesCheckedRequirements: 3, doesNotMatch: 5, needsInformation: 4, notApplied: 0 },
    reasons: codes.map((reasonCode, index) => ({ reasonCode, checks: counts[index] })), exercisedRequiredCriteria: criteria, allDeclaredCriteriaExercised: true });
  assert.equal(validate(summary), true, validationMessage(validate));
  assert.equal(summary.policyVersion, "catalog-auditability-regression-1"); assert.equal(summary.profileSchemaVersion, 6);
  for (const flag of ["coverageComplete", "candidateChangesEvaluated", "configurationVerified", "complianceVerified", "sourceVerificationPerformed", "storedReportVerified",
    "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady"]) {
    assert.equal(summary[flag], false); assert.equal(validate({ ...summary, [flag]: true }), false, flag);
  }
  for (const field of schema.required) { const invalid = structuredClone(summary); delete invalid[field]; assert.equal(validate(invalid), false, field); }
  for (const mutate of [s => s.reasons.reverse(), s => s.reasons.pop(), s => { s.reasons[0].checks = -1; },
    s => { s.outcomes.unknown = 2401; }, s => { s.candidates.needsInformation = 401; }, s => { s.checkedScopes = 101; },
    s => { s.checkedCases = 13; }, s => { s.checkedCriteria = 73; }, s => { s.exercisedRequiredCriteria = []; },
    s => { s.profileSchemaVersion = 5; }, s => { s.deferredBoundaries.pop(); }, s => { s.sourceUrl = "https://example.invalid"; }, s => { s.actor = "synthetic"; }]) {
    const invalid = structuredClone(summary); mutate(invalid); assert.equal(validate(invalid), false);
  }
});

test("synthetic auditability evidence is separately versioned, scoped and never a publication", async () => {
  const evidence = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog/auditability-evidence.v1.json"));
  const base = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog/synthetic.v4.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-auditability-catalog.v1.schema.json");
  assert.equal(validate(evidence), true, validationMessage(validate));
  assert.equal(evidence.baseCatalogVersion, base.catalogVersion);
  assert.equal(evidence.options.length, base.options.length);
  for (const option of evidence.options) {
    const target = base.options.find(candidate => candidate.id === option.scope.optionId);
    assert.equal(option.scope.plan, target.plan); assert.equal(option.scope.region, target.region);
    assert.equal(new Set(option.facts.map(fact => fact.criterion)).size, option.facts.length);
    for (const fact of option.facts) assert.deepEqual(fact.scope, option.scope);
  }
  for (const patch of [{ emitter: "APPLICATION" }, { criterion: "BUSINESS_EVENTS" }, { support: "OPTIONAL" },
    { sourceUrl: "https://provider.example.com/logs" }, { sourceUrl: "https://user:secret@provider.example.invalid/logs" },
    { observedAt: "yesterday" }, { documentedMinimumRetentionDays: 30 }, { configurationVerified: true }]) {
    const invalid = structuredClone(evidence); Object.assign(invalid.options[0].facts[0], patch);
    assert.equal(validate(invalid), false, JSON.stringify(patch));
  }
  for (const field of Object.keys(evidence.options[0].facts[0])) {
    const invalid = structuredClone(evidence); delete invalid.options[0].facts[0][field]; assert.equal(validate(invalid), false, field);
  }
  assert.equal(validate({ ...evidence, kind: "PUBLISHED" }), false);
  assert.equal(validate({ ...evidence, approvalGranted: true }), false);
  for (const field of ["plan", "region", "configuration"]) {
    const invalid = structuredClone(evidence); invalid.options[0].scope[field] = " padded "; assert.equal(validate(invalid), false);
  }
});

test("auditability preview contract forbids verified claims, inconsistent reasons, status and criterion order", async () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/auditability-capability-preflight.v1.schema.json");
  const catalog = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog/auditability-evidence.v1.json"));
  const scope = catalog.options[0].scope, evaluatedAt = "2026-09-12T12:00:00Z";
  const criteria = catalog.options[0].facts.map(fact => fact.criterion);
  const requirements = { selectedCriteria: [], minimumRetentionDays: null };
  const analysis = { optionScope: scope, criticality: "UNKNOWN", requirements, evaluatedAt,
    checks: criteria.map(criterion => ({ criterion, outcome: "UNKNOWN", reasonCode: "REQUIREMENT_UNKNOWN", documentedMinimumRetentionDays: null })),
    status: "NEEDS_INFORMATION", policyVersion: "auditability-capability-preflight-1",
    analysisBasis: "SYNTHETIC_SCOPED_PROVIDER_CAPABILITY_EVIDENCE",
    deferredBoundaries: ["eventRecordContentAndScope", "auditRecordIntegrity", "auditAccessControls", "loggingFailureHandling",
      "exportDeliveryAndRetrieval", "deployedLoggingConfiguration", "complianceEvidence"],
    configurationVerified: false, complianceVerified: false, recommendationReady: false };
  const preview = { workspaceId: "70000000-0000-4000-8000-000000000001", assessmentId: "80000000-0000-4000-8000-000000000001",
    assessmentVersion: 0, baseCatalogVersion: catalog.baseCatalogVersion, evidenceVersion: catalog.evidenceVersion, catalogKind: "SYNTHETIC",
    evaluatedAt, criticality: "UNKNOWN", requirements, candidates: [{ displayName: "Fictional Complete", analysis, evidence: catalog.options[0].facts }],
    policyVersion: analysis.policyVersion, scope: "SYNTHETIC_AUDITABILITY_CAPABILITY_PREFLIGHT",
    checkedPaths: ["security.auditability", "security.auditabilityRequirements"], sourceVerificationPerformed: false, recommendationReady: false };
  assert.equal(validate(preview), true, validationMessage(validate));
  for (const field of ["sourceVerificationPerformed", "recommendationReady"]) assert.equal(validate({ ...preview, [field]: true }), false);
  for (const patch of [{ configurationVerified: true }, { complianceVerified: true }, { recommendationReady: true },
    { status: "MATCHES_CHECKED_REQUIREMENTS" }, { checks: [...analysis.checks].reverse() },
    { checks: analysis.checks.slice(1) }, { criticality: "REQUIRED" }, { analysisBasis: "OBSERVED_LOGS" }]) {
    const invalid = structuredClone(preview); Object.assign(invalid.candidates[0].analysis, patch);
    assert.equal(validate(invalid), false, JSON.stringify(patch));
  }
  for (const patch of [{ outcome: "PASS" }, { documentedMinimumRetentionDays: 90 },
    { reasonCode: "RETENTION_DURATION_UNKNOWN" }, { reasonCode: "DOCUMENTED_CAPABILITY_AVAILABLE" }]) {
    const invalid = structuredClone(preview); Object.assign(invalid.candidates[0].analysis.checks[0], patch); assert.equal(validate(invalid), false);
  }
});

test("temporary architecture declarations accept only one pattern's typed conditions without caller authority", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/architecture-prerequisite-request.v1.schema.json");
  const preview = ajv.getSchema("https://authweave.dev/contracts/architecture-prerequisite-preview.v1.schema.json");
  assert.equal(preview({}), false); // Compile the complete strict response schema, even without HTTP samples.
  const groups = {
    BFF_SESSION: ["BFF_BACKEND_API_PROXY", "BFF_SESSION_DEFENSES"],
    SERVER_SIDE_SESSION: ["SERVER_SESSION_RESOURCE_ACCESS", "DIRECT_BROWSER_API_ACCESS_ASSESSED"],
    SPA_CODE_PKCE: ["SPA_PUBLIC_PKCE_BROWSER_ENDPOINTS", "SPA_TOKEN_THREAT_MODEL"],
    NATIVE_CODE_PKCE: ["NATIVE_EXTERNAL_AGENT_REDIRECT_PKCE", "NATIVE_STORAGE_API_AUTHORIZATION"],
    M2M_CLIENT_CREDENTIALS: ["WORKLOAD_CONFIDENTIAL_CLIENT", "WORKLOAD_AUTHORIZATION_CONTEXT", "WORKLOAD_GRANT_API_PERMISSIONS"],
  };
  for (const [patternId, ids] of Object.entries(groups)) {
    for (const declaration of ["SATISFIED", "NOT_SATISFIED", "UNKNOWN"]) {
      const input = { expectedVersion: 0, patternId, declarations: Object.fromEntries(ids.map(id => [id, declaration])) };
      assert.equal(validate(input), true, validationMessage(validate));
      assert.equal(validate({ ...input, declarations: {} }), true, validationMessage(validate));
      for (const foreign of Object.values(groups).flat().filter(id => !ids.includes(id))) {
        assert.equal(validate({ ...input, declarations: { [foreign]: declaration } }), false);
      }
      for (const invalid of [{ ...input, clientScope: "SELECTED" }, { ...input, approvalGranted: true },
        { ...input, configurationVerified: true }, { ...input, expectedVersion: "0" }, { ...input, expectedVersion: -1 },
        { ...input, expectedVersion: 9007199254740992 }, { ...input, declarations: { [ids[0]]: null } },
        { ...input, declarations: { [ids[0]]: "VERIFIED" } }]) assert.equal(validate(invalid), false);
    }
  }
});

test("additional scoped regression profiles independently validate against profile v5 without replacing frozen v1", async () => {
  const resourceRoot = path.resolve(contractsRoot, "../../services/core-api/src/main/resources/catalog");
  const scoped = await readJson(path.join(resourceRoot, "scoped-impact-scenarios.v1.json"));
  const frozen = await readJson(path.join(resourceRoot, "impact-scenarios.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v5.schema.json");
  assert.equal(scoped.length, 4);
  assert.equal(frozen.length, 3);
  assert.equal(new Set(scoped.map((item) => item.id)).size, 4);
  for (const item of scoped) {
    assert.equal(item.profileSchemaVersion, 5);
    assert.equal(validate(item.profile), true, validationMessage(validate));
    assert.equal(frozen.some((old) => old.id === item.id), false);
  }
});

test("bootstrap review contracts require a separate explicit assertion and cannot claim approval or disclose actors", async () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-bootstrap-review-request.v1.schema.json");
  const validateReceipt = ajv.getSchema("https://authweave.dev/contracts/catalog-bootstrap-review.v1.schema.json");
  const candidate = await readJson(path.join(fixturesRoot, "provider-catalog-draft.valid.json"));
  const request = { schemaVersion: 1, reviewId: "90000000-0000-4000-8000-000000000001",
    expectedCandidateSha256: "a".repeat(64), candidate,
    observations: [{ optionId: "example-eu", factPath: "facts.SCIM", verdict: "SOURCE_SUPPORTS_CLAIM" }],
    confirmation: "MANUAL_BOOTSTRAP_SOURCE_REVIEW" };
  assert.equal(validate(request), true, validationMessage(validate));
  for (const invalid of [{ ...request, schemaVersion: "1" }, { ...request, confirmation: "MANUAL_SOURCE_REVIEW" },
    { ...request, observations: [] }, { ...request, observations: Array(6801).fill(request.observations[0]) },
    { ...request, expectedCandidateSha256: "A".repeat(64) }, { ...request, actorSubject: "private" },
    { ...request, approvalGranted: true }, { ...request, expectedVersion: 0 },
    { ...request, observations: [{ ...request.observations[0], verdict: "APPROVED" }] }]) assert.equal(validate(invalid), false);
  const receipt = { reviewId: request.reviewId, candidateSha256: request.expectedCandidateSha256, reviewSha256: "b".repeat(64),
    catalogVersion: candidate.catalogVersion, factCount: 1, counts: { supporting: 1, contradicting: 0, insufficient: 0 },
    recordedAt: "2026-09-30T12:00:00Z", policyVersion: "catalog-bootstrap-source-review-1", kind: "HUMAN_BOOTSTRAP_SOURCE_REVIEW",
    sourceVerificationPerformed: false, approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false };
  assert.equal(validateReceipt(receipt), true, validationMessage(validateReceipt));
  for (const field of ["sourceVerificationPerformed", "approvalGranted", "catalogWritesPerformed", "factTrustChanged"]) {
    assert.equal(validateReceipt({ ...receipt, [field]: true }), false);
  }
  for (const invalid of [{ ...receipt, actorSubject: "private" }, { ...receipt, candidate },
    { ...receipt, factCount: 0 }, { ...receipt, kind: "CURATED_BOOTSTRAP" }]) assert.equal(validateReceipt(invalid), false);
});

test("candidate evidence pages preserve scope and forbid verification or approval claims", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-evidence-page.v1.schema.json");
  const item = { optionId: "example-eu", path: "facts.SCIM", scope: { providerId: "example",
    product: "Example Identity", plan: "Example Enterprise", deployment: "MANAGED", region: "EU",
    configuration: "Pilot" }, evidenceStatus: "UNREVIEWED", freshness: "STALE", conditions: [],
    evidence: { sourceUrl: "https://docs.example.invalid/identity", observedAt: "2026-01-01T00:00:00Z",
      summary: "Fictional source paraphrase." } };
  const page = { proposalId: "90000000-0000-4000-8000-000000000001", proposalVersion: 0,
    proposalSha256: "a".repeat(64), catalogVersion: "example-1", evaluatedAt: "2026-09-30T12:00:00Z",
    policyVersion: "catalog-proposal-evidence-review-1", maxEvidenceAgeDays: 90,
    sourceVerificationPerformed: false, approvalGranted: false, writesPerformed: false, evaluationReady: false,
    factCount: 1, freshness: { current: 0, stale: 1, future: 0 }, offset: 0, items: [item], nextOffset: null };
  assert.equal(validate(page), true, validationMessage(validate));
  for (const invalid of [{ ...page, approvalGranted: true }, { ...page, sourceVerificationPerformed: true },
    { ...page, offset: -1 }, { ...page, items: Array(21).fill(item) },
    { ...page, items: [{ ...item, evidenceStatus: "REVIEWED" }] }]) assert.equal(validate(invalid), false);
});

test("candidate evidence v2 binds each typed claim to its fact family without promoting trust", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-evidence-page.v2.schema.json");
  const item = { optionId: "example-eu", path: "facts.SCIM",
    claim: { kind: "CAPABILITY", availability: "UNKNOWN" }, scope: { providerId: "example",
      product: "Example Identity", plan: "Example Enterprise", deployment: "MANAGED", region: "EU", configuration: "Pilot" },
    evidenceStatus: "UNREVIEWED", freshness: "CURRENT", conditions: [], evidence: {
      sourceUrl: "https://example.invalid/source", observedAt: "2026-09-30T12:00:00Z", summary: "Fictional claim." } };
  const page = { proposalId: "90000000-0000-4000-8000-000000000001", proposalVersion: 0,
    proposalSha256: "a".repeat(64), catalogVersion: "example-1", evaluatedAt: "2026-09-30T12:00:00Z",
    policyVersion: "catalog-proposal-evidence-review-2", maxEvidenceAgeDays: 90,
    sourceVerificationPerformed: false, approvalGranted: false, writesPerformed: false, evaluationReady: false,
    factCount: 1, freshness: { current: 1, stale: 0, future: 0 }, offset: 0, items: [item], nextOffset: null };
  for (const [path, claim] of [["facts.SCIM", { kind: "CAPABILITY", availability: "UNKNOWN" }],
    ["facts.OAUTH2_APIS", { kind: "CAPABILITY", availability: "OPTIONAL" }],
    ["compatibility.applications.B2B_SAAS", { kind: "COMPATIBILITY", support: "SUPPORTED" }],
    ["compatibility.clients.BROWSER", { kind: "COMPATIBILITY", support: "UNSUPPORTED" }],
    ["residency.USER_PROFILES", { kind: "RESIDENCY", coverage: "PARTIAL", storageCountries: ["DE"] }],
    ["authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE",
      { kind: "AUTHENTICATION_CONTROL", availability: "SUPPORTED", enforcement: "UNKNOWN" }]]) {
    assert.equal(validate({ ...page, items: [{ ...item, path, claim }] }), true, validationMessage(validate));
  }
  for (const claim of [undefined, { kind: "CAPABILITY", availability: "SUPPORTED" },
    { kind: "CAPABILITY", availability: "OPTIONAL", sourceVerified: true },
    { kind: "COMPATIBILITY", support: "SUPPORTED" }]) {
    assert.equal(validate({ ...page, items: [{ ...item, claim }] }), false);
  }
  assert.equal(validate({ ...page, policyVersion: "catalog-proposal-evidence-review-1" }), false);
  assert.equal(validate({ ...page, approvalGranted: true }), false);
});

test("manual source-review contracts bind a fact observation without granting trust", () => {
  const request = ajv.getSchema("https://authweave.dev/contracts/catalog-fact-review-request.v1.schema.json");
  const response = ajv.getSchema("https://authweave.dev/contracts/catalog-fact-review.v1.schema.json");
  const input = { reviewId: "90000000-0000-4000-8000-000000000001", expectedVersion: 0,
    expectedSha256: "a".repeat(64), optionId: "example-managed-eu", factPath: "compatibility.applications.B2B_SAAS",
    verdict: "INSUFFICIENT_EVIDENCE", confirmation: "MANUAL_SOURCE_REVIEW" };
  assert.equal(request(input), true, ajv.errorsText(request.errors));
  const receipt = { reviewId: input.reviewId, proposalId: "90000000-0000-4000-8000-000000000002",
    proposalVersion: 0, proposalSha256: input.expectedSha256, reviewNumber: 1, optionId: input.optionId,
    factPath: input.factPath, verdict: input.verdict, recordedAt: "2026-09-30T12:00:00Z",
    kind: "HUMAN_SOURCE_REVIEW_OBSERVATION", sourceVerificationPerformed: false, approvalGranted: false,
    catalogWritesPerformed: false, factTrustChanged: false };
  assert.equal(response(receipt), true, ajv.errorsText(response.errors));
  const history = ajv.getSchema("https://authweave.dev/contracts/catalog-fact-review-page.v1.schema.json");
  const page = { proposalId: receipt.proposalId, proposalVersion: receipt.proposalVersion,
    proposalSha256: receipt.proposalSha256, afterReviewNumber: 0, items: [receipt], nextAfterReviewNumber: null };
  assert.equal(history(page), true, ajv.errorsText(history.errors));
  assert.equal(history({ ...page, items: [] }), true);
  for (const change of [{ items: Array(21).fill(receipt) }, { afterReviewNumber: -1 },
    { nextAfterReviewNumber: 0 }, { approved: true }, { items: [{ ...receipt, actorSubject: "private" }] }]) {
    assert.equal(history({ ...page, ...change }), false);
  }
  const summarize = ajv.getSchema("https://authweave.dev/contracts/catalog-fact-review-summary-page.v1.schema.json");
  const summary = { proposalId: receipt.proposalId, proposalVersion: 0, proposalSha256: receipt.proposalSha256,
    policyVersion: "catalog-fact-review-summary-1", reviewThroughNumber: 1, factCount: 2,
    counts: { noObservation: 1, sourceSupportsClaim: 0, sourceDoesNotSupportClaim: 0, insufficientEvidence: 1 },
    offset: 0, items: [{ optionId: receipt.optionId, factPath: receipt.factPath, latestObservation: receipt },
      { optionId: receipt.optionId, factPath: "facts.SCIM", latestObservation: null }], nextOffset: null,
    sourceVerificationPerformed: false, approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false };
  assert.equal(summarize(summary), true, ajv.errorsText(summarize.errors));
  for (const change of [{ sourceVerificationPerformed: true }, { approvalGranted: true }, { factTrustChanged: true },
    { reviewThroughNumber: -1 }, { reviewThroughNumber: 9007199254740992 }, { approved: true },
    { counts: { ...summary.counts, noObservation: "1" } }, { items: Array(21).fill(summary.items[0]) },
    { items: [{ ...summary.items[0], latestObservation: { ...receipt, actorSubject: "private" } }] },
    { items: [{ ...summary.items[0], evidenceStatus: "REVIEWED" }] }]) {
    assert.equal(summarize({ ...summary, ...change }), false);
  }
  for (const change of [{ actorSubject: "forged" }, { confirmation: "AUTOMATIC" }, { verdict: "VERIFIED" },
    { expectedVersion: "0" }, { expectedVersion: 9007199254740992 }, { factPath: "request.candidate" }]) {
    assert.equal(request({ ...input, ...change }), false);
  }
  const missing = { ...input }; delete missing.confirmation; assert.equal(request(missing), false);
  for (const change of [{ sourceVerificationPerformed: true }, { approvalGranted: true },
    { catalogWritesPerformed: true }, { factTrustChanged: true }, { reviewNumber: 0 }, { actorSubject: "private" }]) {
    assert.equal(response({ ...receipt, ...change }), false);
  }
});

test("curator rejection contracts bind one revision and exclude approval or free text", () => {
  const request = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-rejection-request.v1.schema.json");
  const response = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-rejection.v1.schema.json");
  const id = "90000000-0000-4000-8000-000000000001";
  const digest = "a".repeat(64);
  const validRequest = { expectedVersion: 2, expectedSha256: digest, reasonCode: "INACCURATE_FACTS" };
  const validResponse = { decisionId: id, proposalId: id, proposalVersion: 2,
    proposalSha256: digest, decision: "REJECTED", reasonCode: "INACCURATE_FACTS",
    recordedAt: "2026-09-23T12:00:00Z" };
  assert.equal(request(validRequest), true, validationMessage(request));
  assert.equal(response(validResponse), true, validationMessage(response));
  for (const invalid of [{ ...validRequest, reasonCode: "APPROVED" },
    { ...validRequest, rationale: "untrusted text" },
    { ...validRequest, expectedSha256: "bad" },
    { ...validRequest, expectedVersion: 9007199254740992 }]) {
    assert.equal(request(invalid), false);
  }
  assert.equal(response({ ...validResponse, decision: "APPROVED" }), false);
  assert.equal(response({ ...validResponse, actorSubject: "private" }), false);
});

test("curator review index is bounded and excludes proposal bodies and approval claims", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-review-page.v1.schema.json");
  const id = "90000000-0000-4000-8000-000000000001";
  const at = "2026-09-22T12:00:00.123456Z";
  const item = { proposalId: id, version: 0, proposalSha256: "a".repeat(64),
    createdAt: at, updatedAt: at, rejectionRecorded: false };
  assert.equal(validate({ items: [item], nextBefore: null }), true, validationMessage(validate));
  assert.equal(validate({ items: Array(21).fill(item), nextBefore: { createdAt: at, id } }), false);
  for (const extra of [{ request: {} }, { evidence: [] }, { approved: true }]) {
    assert.equal(validate({ items: [{ ...item, ...extra }], nextBefore: null }), false);
  }
  assert.equal(validate({ items: [item], nextBefore: { createdAt: at } }), false);
});

const validRequest = await readJson(
  path.join(fixturesRoot, "requirements-extraction-request.valid.json"),
);
const validResult = await readJson(
  path.join(fixturesRoot, "requirements-extraction-result.valid.json"),
);
const validProblem = await readJson(
  path.join(fixturesRoot, "operation-problem.valid.json"),
);
const validAssessmentResponse = await readJson(
  path.join(fixturesRoot, "assessment-response.valid.json"),
);
const validCoreProblem = await readJson(
  path.join(fixturesRoot, "core-problem.valid.json"),
);

function validationMessage(validate) {
  return ajv.errorsText(validate.errors, { separator: "\n" });
}

test("valid fixtures satisfy their JSON Schemas", () => {
  assert.equal(validateRequest(validRequest), true, validationMessage(validateRequest));
  assert.equal(validateResult(validResult), true, validationMessage(validateResult));
  assert.equal(validateProblem(validProblem), true, validationMessage(validateProblem));
});

test("Core API fixtures satisfy their JSON Schemas", () => {
  const updateRequest = {
    expectedVersion: validAssessmentResponse.version,
    profile: validAssessmentResponse.profile,
  };

  assert.equal(
    validateAssessmentResponse(validAssessmentResponse),
    true,
    validationMessage(validateAssessmentResponse),
  );
  assert.equal(
    validateUpdateAssessmentProfile(updateRequest),
    true,
    validationMessage(validateUpdateAssessmentProfile),
  );
  assert.equal(
    validateCoreProblem(validCoreProblem),
    true,
    validationMessage(validateCoreProblem),
  );
});

test("assessment list pages are bounded summaries with a valid cursor", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/assessment-list-page.v1.schema.json");
  const item = {
    id: validAssessmentResponse.id, status: "DRAFT", version: 0,
    createdAt: validAssessmentResponse.createdAt, updatedAt: validAssessmentResponse.updatedAt,
  };
  assert.equal(validate({ items: [item], nextBeforeId: item.id }), true, validationMessage(validate));
  assert.equal(validate({ items: [], nextBeforeId: null }), true, validationMessage(validate));
  assert.equal(validate({ items: [{ ...item, profile: validAssessmentResponse.profile }], nextBeforeId: null }), false);
  assert.equal(validate({ items: [item], nextBeforeId: "not-a-uuid" }), false);
  assert.equal(validate({ items: Array(51).fill(item), nextBeforeId: null }), false);
});

test("hard-constraint summary cannot claim a winner or hide its synthetic scope", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/hard-constraint-preflight.v1.schema.json");
  const finding = {
    dimension: "CAPABILITY", profilePath: "provisioning.scim",
    reasonCode: "REQUIRED_CAPABILITY_UNAVAILABLE", explanation: "SCIM is unavailable in this fictional plan.",
  };
  const candidate = {
    optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo", region: "Synthetic region",
    verdict: "EXCLUDED", exclusionReasons: [finding], informationGaps: [],
  };
  const payload = {
    workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id,
    assessmentVersion: 0, catalogVersion: "synthetic-test", catalogKind: "SYNTHETIC",
    policyVersion: "hard-constraint-preflight-1", evaluatedAt: validAssessmentResponse.createdAt,
    scope: "SYNTHETIC_HARD_CONSTRAINT_PREFLIGHT", recommendationReady: false,
    deferredPaths: ["security.browserTokenExposureMinimization", "security.auditability",
      "security.assurance", "security.complianceTargets", "operations"], candidates: [candidate],
  };
  assert.equal(validate(payload), true, validationMessage(validate));
  assert.equal(validate({ ...payload, recommendationReady: true }), false);
  assert.equal(validate({ ...payload, winnerId: candidate.optionId }), false);
  assert.equal(validate({ ...payload, candidates: [{ ...candidate, score: 100 }] }), false);
  assert.equal(validate({ ...payload, candidates: [{ ...candidate, exclusionReasons: [{ ...finding, reasonCode: "guess" }] }] }), false);
});

test("synthetic comparison keeps preferences unweighted and preserves excluded options", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-comparison.v1.schema.json");
  const candidate = {
    optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo", region: "Synthetic region",
    hardVerdict: "EXCLUDED", exclusionReasons: [{
      dimension: "CAPABILITY", profilePath: "provisioning.scim",
      reasonCode: "REQUIRED_CAPABILITY_UNAVAILABLE", explanation: "SCIM is unavailable.",
    }], informationGaps: [], capabilityPreferences: [{
      capability: "SOCIAL_LOGIN", profilePath: "protocols.socialLogin", outcome: "AVAILABLE",
      reasonCode: "PREFERRED_CAPABILITY_AVAILABLE", explanation: "The plan offers social login.",
      evidence: { availability: "OPTIONAL", evidenceStatus: "REVIEWED",
        sourceUrl: "https://example.invalid/social", observedAt: "2026-09-12T00:00:00Z" },
    }],
  };
  const payload = {
    workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id,
    assessmentVersion: 0, catalogVersion: "synthetic-test", catalogKind: "SYNTHETIC",
    policyVersion: "synthetic-comparison-1", hardConstraintPolicyVersion: "hard-constraint-preflight-1",
    preferencePolicyVersion: "capability-preference-1", evaluatedAt: validAssessmentResponse.createdAt,
    scope: "SYNTHETIC_UNRANKED_COMPARISON", recommendationReady: false, rankingPerformed: false,
    deferredPaths: ["security.browserTokenExposureMinimization", "security.auditability",
      "security.assurance", "security.complianceTargets", "operations"], candidates: [candidate],
  };
  assert.equal(validate(payload), true, validationMessage(validate));
  assert.equal(validate({ ...payload, rankingPerformed: true }), false);
  assert.equal(validate({ ...payload, winnerId: candidate.optionId }), false);
  assert.equal(validate({ ...payload, candidates: [{ ...candidate, score: 1 }] }), false);
  assert.equal(validate({ ...payload, candidates: [{ ...candidate,
    capabilityPreferences: [{ ...candidate.capabilityPreferences[0], outcome: "WINNER" }] }] }), false);
});

function scoredSyntheticFixture() {
  const request = { weights: { SOCIAL_LOGIN: 60, JIT: 40 } };
  const preference = {
    capability: "SOCIAL_LOGIN", profilePath: "protocols.socialLogin", outcome: "AVAILABLE",
    reasonCode: "PREFERRED_CAPABILITY_AVAILABLE", explanation: "The fictional plan offers social login.",
    evidence: { availability: "OPTIONAL", evidenceStatus: "REVIEWED",
      sourceUrl: "https://example.invalid/social", observedAt: "2026-09-12T00:00:00Z" },
  };
  const comparison = {
    workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id,
    assessmentVersion: 0, catalogVersion: "synthetic-test", catalogKind: "SYNTHETIC",
    policyVersion: "synthetic-comparison-1", hardConstraintPolicyVersion: "hard-constraint-preflight-1",
    preferencePolicyVersion: "capability-preference-1", evaluatedAt: validAssessmentResponse.createdAt,
    scope: "SYNTHETIC_UNRANKED_COMPARISON", recommendationReady: false, rankingPerformed: false,
    deferredPaths: ["security.browserTokenExposureMinimization", "security.auditability",
      "security.assurance", "security.complianceTargets", "operations"], candidates: [{
      optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo", region: "Synthetic region",
      hardVerdict: "PASSES_CHECKED_REQUIREMENTS", exclusionReasons: [], informationGaps: [],
      capabilityPreferences: [preference, { ...preference, capability: "JIT", profilePath: "provisioning.justInTimeProvisioning" }],
    }],
  };
  const scored = {
    comparison, scoringPolicyVersion: "explicit-capability-weights-1", weights: request.weights,
    rankingPerformed: false, recommendationReady: false, scores: [{
      optionId: "fictional-plan", status: "SCORED", score: 100,
      contributions: [
        { capability: "SOCIAL_LOGIN", weight: 60, outcome: "AVAILABLE", earnedPoints: 60 },
        { capability: "JIT", weight: 40, outcome: "AVAILABLE", earnedPoints: 40 },
      ],
    }],
  };
  return scored;
}

test("weighted preview requires explicit dimensions and cannot turn withheld scores into recommendations", () => {
  const validateRequest = ajv.getSchema("https://authweave.dev/contracts/weighted-comparison-request.v1.schema.json");
  const validatePreview = ajv.getSchema("https://authweave.dev/contracts/weighted-comparison-preview.v1.schema.json");
  const request = { weights: { SOCIAL_LOGIN: 60, JIT: 40 } };
  assert.equal(validateRequest(request), true, validationMessage(validateRequest));
  assert.equal(validateRequest({ weights: { SOCIAL_LOGIN: -1 } }), false);
  assert.equal(validateRequest({ weights: { INVENTED: 100 } }), false);
  assert.equal(validateRequest({ ...request, defaultWeight: 50 }), false);
  const scored = scoredSyntheticFixture();
  assert.equal(validatePreview(scored), true, validationMessage(validatePreview));
  assert.equal(validatePreview({ ...scored, recommendationReady: true }), false);
  assert.equal(validatePreview({ ...scored, winnerId: "fictional-plan" }), false);
  assert.equal(validatePreview({ ...scored, scores: [{ ...scored.scores[0], score: null }] }), false);
  const withheld = { ...scored, scores: [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
    score: null, contributions: [] }] };
  assert.equal(validatePreview(withheld), true, validationMessage(validatePreview));
  assert.equal(validatePreview({ ...withheld, scores: [{ ...withheld.scores[0], score: 100 }] }), false);
});

test("sensitivity preview keeps paired what-if deltas bounded and never claims a winner", () => {
  const validateRequest = ajv.getSchema("https://authweave.dev/contracts/weight-sensitivity-request.v1.schema.json");
  const validatePreview = ajv.getSchema("https://authweave.dev/contracts/weight-sensitivity-preview.v1.schema.json");
  const baseline = scoredSyntheticFixture();
  const alternative = {
    weights: { SOCIAL_LOGIN: 20, JIT: 80 },
    scores: [{ optionId: "fictional-plan", status: "SCORED", score: 100, contributions: [
      { capability: "SOCIAL_LOGIN", weight: 20, outcome: "AVAILABLE", earnedPoints: 20 },
      { capability: "JIT", weight: 80, outcome: "AVAILABLE", earnedPoints: 80 },
    ] }],
  };
  const request = { baselineWeights: baseline.weights, alternativeWeights: alternative.weights };
  assert.equal(validateRequest(request), true, validationMessage(validateRequest));
  assert.equal(validateRequest({ ...request, selectedWinner: "fictional-plan" }), false);
  assert.equal(validateRequest({ ...request, alternativeWeights: { INVENTED: 100 } }), false);
  const preview = {
    comparison: baseline.comparison, scoringPolicyVersion: "explicit-capability-weights-1",
    sensitivityPolicyVersion: "explicit-weight-sensitivity-1",
    baseline: { weights: baseline.weights, scores: baseline.scores }, alternative,
    deltas: [{ optionId: "fictional-plan", status: "SCORED", scoreDelta: 0, capabilityDeltas: [
      { capability: "SOCIAL_LOGIN", baselineWeight: 60, alternativeWeight: 20,
        outcome: "AVAILABLE", pointChange: -40 },
      { capability: "JIT", baselineWeight: 40, alternativeWeight: 80,
        outcome: "AVAILABLE", pointChange: 40 },
    ] }], rankingPerformed: false, recommendationReady: false,
  };
  assert.equal(validatePreview(preview), true, validationMessage(validatePreview));
  assert.equal(validatePreview({ ...preview, rankingPerformed: true }), false);
  assert.equal(validatePreview({ ...preview, winnerId: "fictional-plan" }), false);
  assert.equal(validatePreview({ ...preview, deltas: [{ ...preview.deltas[0], scoreDelta: null }] }), false);
  const withheld = { ...preview, deltas: [{ optionId: "fictional-plan", status: "EXCLUDED",
    scoreDelta: null, capabilityDeltas: [] }] };
  assert.equal(validatePreview(withheld), true, validationMessage(validatePreview));
  assert.equal(validatePreview({ ...withheld, deltas: [{ ...withheld.deltas[0], scoreDelta: 10 }] }), false);
});

test("AI proposals and profiles share the canonical criticality vocabulary", () => {
  for (const criticality of ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN"]) {
    const result = structuredClone(validResult);
    result.proposal.requirements[0].criticalityProposal.value = criticality;
    assert.equal(validateResult(result), true, validationMessage(validateResult));

    const request = { expectedVersion: 0, profile: structuredClone(validAssessmentResponse.profile) };
    request.profile.protocols.socialLogin = criticality;
    assert.equal(validateUpdateAssessmentProfile(request), true, validationMessage(validateUpdateAssessmentProfile));
  }
});

test("legacy AI criticality labels cannot silently enter a canonical profile", () => {
  for (const value of ["hard-requirement", "important", "preference"]) {
    const result = structuredClone(validResult);
    result.proposal.requirements[0].criticalityProposal.value = value;
    assert.equal(validateResult(result), false);
  }
});

test("Core API contracts reject stale-shape and unknown-field inputs", () => {
  const staleUpdate = {
    expectedVersion: -1,
    profile: validAssessmentResponse.profile,
  };
  const responseWithUnknownField = {
    ...validAssessmentResponse,
    internalNote: "must not cross the API boundary",
  };

  assert.equal(validateUpdateAssessmentProfile(staleUpdate), false);
  assert.equal(validateAssessmentResponse(responseWithUnknownField), false);
});

test("history contracts accept snapshots and minimal events but reject profile values in events", () => {
  const revisions = ajv.getSchema("https://authweave.dev/contracts/assessment-revision-page.v1.schema.json");
  const events = ajv.getSchema("https://authweave.dev/contracts/assessment-event-page.v1.schema.json");
  const base = { workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id, version: 0 };
  const revision = {
    ...base, status: "DRAFT", profileSchemaVersion: 1, profile: validAssessmentResponse.profile,
    origin: "CREATED", recordedAt: validAssessmentResponse.createdAt,
  };
  const event = {
    ...base, id: "22222222-2222-4222-8222-222222222222", previousVersion: null,
    action: "assessment.created", actorType: "SERVICE", actorId: "core-api",
    correlationId: "33333333-3333-4333-8333-333333333333", outcome: "SUCCEEDED",
    changedSections: ["application"], occurredAt: validAssessmentResponse.createdAt,
  };
  assert.equal(revisions({ items: [revision], nextAfterVersion: 0 }), true, validationMessage(revisions));
  assert.equal(events({ items: [event], nextAfterVersion: null }), true, validationMessage(events));
  assert.equal(revisions({ items: [{ ...revision, origin: "BASELINE", version: 7 }], nextAfterVersion: null }), true);
  for (const validate of [revisions, events]) {
    assert.equal(validate({ items: [], nextAfterVersion: null }), true);
    assert.equal(validate({ items: [], nextAfterVersion: -1 }), false);
    assert.equal(validate({ items: [], nextAfterVersion: 9007199254740992 }), false);
    assert.equal(validate({ items: [] }), false);
  }
  assert.equal(events({ items: [{ ...event, profile: revision.profile }], nextAfterVersion: null }), false);
  assert.equal(events({ items: [{ ...event, changedSections: ["application", "application"] }], nextAfterVersion: null }), false);
  assert.equal(events({ items: [{ ...event, changedSections: ["sensitive-raw-value"] }], nextAfterVersion: null }), false);
  assert.equal(events({ items: [{ ...event, outcome: "FAILED" }], nextAfterVersion: null }), false);
  assert.equal(revisions({ items: [{ ...revision, profileSchemaVersion: 2 }], nextAfterVersion: null }), false);
  assert.equal(revisions({ items: [{ ...revision, origin: "INVENTED" }], nextAfterVersion: null }), false);
});

test("profile v2 requires explicit residency details without silently expanding v1", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/update-assessment-profile-request.v2.schema.json");
  const validateResponse = ajv.getSchema("https://authweave.dev/contracts/assessment-response.v2.schema.json");
  const profile = structuredClone(validAssessmentResponse.profile);
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false, "Missing details must not be defaulted by the v2 API.");
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  assert.equal(validate(request), true, validationMessage(validate));
  assert.equal(validateUpdateAssessmentProfile(request), false, "v1 must reject the new field, even when empty.");
  const response = { ...validAssessmentResponse, profileSchemaVersion: 2, profile };
  assert.equal(validateResponse(response), true, validationMessage(validateResponse));
  assert.equal(validateResponse({ ...response, profileSchemaVersion: 1 }), false);
  for (const details of [null, {}, { allowedCountries: ["de"], dataCategories: [] },
    { allowedCountries: ["DE", "DE"], dataCategories: [] },
    { allowedCountries: ["DE"], dataCategories: ["BACKUPS", "BACKUPS"] },
    { allowedCountries: ["DE"], dataCategories: ["ALL"] },
    { allowedCountries: ["DE"], dataCategories: [], compliant: true }]) {
    profile.security.dataResidencyDetails = details;
    assert.equal(validate(request), false);
  }
  profile.security.dataResidencyDetails = { allowedCountries: ["DE", "FR"], dataCategories: ["USER_PROFILES", "BACKUPS"] };
  assert.equal(validate(request), true, validationMessage(validate));
});

test("v2 history preserves mixed schema versions and rejects mislabeled snapshots", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/assessment-revision-page.v2.schema.json");
  const old = { workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id,
    version: 0, status: "DRAFT", profileSchemaVersion: 1, profile: validAssessmentResponse.profile,
    origin: "CREATED", recordedAt: validAssessmentResponse.createdAt };
  const expanded = structuredClone(old);
  expanded.version = 1;
  expanded.origin = "UPDATED";
  expanded.profileSchemaVersion = 2;
  expanded.profile.security.dataResidencyDetails = { allowedCountries: ["DE"], dataCategories: ["BACKUPS"] };
  const page = { items: [old, expanded], nextAfterVersion: null };
  assert.equal(validate(page), true, validationMessage(validate));
  for (const revision of [{ ...old, profileSchemaVersion: 2 }, { ...expanded, profileSchemaVersion: 1 },
    { ...expanded, profileSchemaVersion: 3 }]) {
    assert.equal(validate({ items: [revision], nextAfterVersion: null }), false);
  }
  assert.equal(validate({ items: [], nextAfterVersion: null }), true);
});

test("runnable synthetic seeds match the canonical profile contract", async () => {
  const seeds = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/seed/assessments.v1.json"));
  assert.equal(seeds.length, 3);
  assert.equal(new Set(seeds.map(seed => seed.id)).size, 3);
  assert.deepEqual(new Set(seeds.map(seed => seed.key)),
    new Set(["b2b-saas", "public-sector-portal", "internal-workforce"]));
  const validate = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v1.schema.json");
  for (const seed of seeds) {
    assert.equal(validate(seed.profile), true, `${seed.key}: ${validationMessage(validate)}`);
  }
});

test("synthetic catalog requires dated, scoped provenance without claiming real vendor evidence", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v1.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.equal(new Set(catalog.options.map(option => option.id)).size, catalog.options.length);
  for (const field of ["sourceUrl", "observedAt", "evidenceStatus"]) {
    const invalid = structuredClone(catalog);
    delete invalid.options[0].facts.SCIM[field];
    assert.equal(validate(invalid), false, "Missing " + field + " must be rejected");
  }
  for (const field of ["plan", "region"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0][field] = " ";
    assert.equal(validate(invalid), false);
  }
  for (const url of ["https://vendor.example.com/facts", "https://user:password@example.invalid/scim", "file:///etc/passwd"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].facts.SCIM.sourceUrl = url;
    assert.equal(validate(invalid), false);
  }
  const unknown = structuredClone(catalog);
  unknown.options[0].facts.SCIM.availability = "MAYBE";
  assert.equal(validate(unknown), false);
  const missing = structuredClone(catalog);
  delete missing.options[0].facts.SCIM;
  assert.equal(validate(missing), true, "A missing fact is allowed; the evaluator must not infer support.");
});

test("catalog v2 requires explicit context evidence and rejects unclassified categories", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v2.json"));
  const legacy = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v2.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.equal(validate(legacy), false);
  assert.deepEqual(catalog.options.map(({ compatibility, ...option }) => option), legacy.options,
    "Catalog v2 must preserve every existing capability fact and option identity.");
  for (const field of ["support", "sourceUrl", "observedAt", "evidenceStatus"]) {
    const invalid = structuredClone(catalog);
    delete invalid.options[0].compatibility.applications.B2B_SAAS[field];
    assert.equal(validate(invalid), false, "Missing " + field + " must be rejected");
  }
  for (const [group, key] of [["applications", "OTHER"], ["applications", "UNKNOWN"],
    ["clients", "WEB"], ["populations", "EVERYONE"], ["tenancy", "UNKNOWN"], ["membership", "UNKNOWN"]]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].compatibility[group][key] = catalog.options[0].compatibility.applications.B2B_SAAS;
    assert.equal(validate(invalid), false);
  }
  for (const value of ["MAYBE", null, true]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].compatibility.applications.B2B_SAAS.support = value;
    assert.equal(validate(invalid), false);
  }
  for (const url of ["https://vendor.example.com/facts", "https://user:secret@example.invalid/facts"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].compatibility.applications.B2B_SAAS.sourceUrl = url;
    assert.equal(validate(invalid), false);
  }
  const empty = structuredClone(catalog);
  for (const group of Object.keys(empty.options[0].compatibility)) {
    empty.options[0].compatibility[group] = {};
  }
  assert.equal(validate(empty), true, "Absence of facts must be representable without inferring lack of support.");
  delete empty.options[0].compatibility.clients;
  assert.equal(validate(empty), false, "All context groups must be explicit, even when empty.");
});

test("catalog v3 preserves old facts and requires category-scoped residency evidence", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v3.json"));
  const legacy = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v2.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v3.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.deepEqual(catalog.options.map(({ residency, ...option }) => option), legacy.options,
    "Residency evidence must not rewrite existing capability or context facts.");
  assert.equal(validate(legacy), false);
  const original = catalog.options[0].residency.USER_PROFILES;
  for (const field of ["coverage", "storageCountries", "evidenceStatus", "sourceUrl", "observedAt"]) {
    const invalid = structuredClone(catalog);
    delete invalid.options[0].residency.USER_PROFILES[field];
    assert.equal(validate(invalid), false, field);
  }
  for (const changes of [{ coverage: "COMPLETE", storageCountries: [] },
    { coverage: "PARTIAL", storageCountries: [] }, { coverage: "UNKNOWN", storageCountries: ["DE"] },
    { storageCountries: ["DE", "DE"] }, { storageCountries: ["de"] },
    { sourceUrl: "https://vendor.example.com/residency" }, { coverage: "MAYBE" }, { compliant: true }]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].residency.USER_PROFILES = { ...original, ...changes };
    assert.equal(validate(invalid), false, JSON.stringify(changes));
  }
  const missing = structuredClone(catalog);
  missing.options[0].residency = {};
  assert.equal(validate(missing), true, "Missing categories must remain representable as unknown evidence.");
  missing.options[0].residency.ALL = original;
  assert.equal(validate(missing), false);
  delete missing.options[0].residency;
  assert.equal(validate(missing), false, "The v3 category map must be explicit, even when empty.");
});

test("profile v3 requires independent controls and preserves exact mixed history", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const validate = schema("update-assessment-profile-request.v3");
  const profile = structuredClone(validAssessmentResponse.profile);
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false);
  const fields = ["phishingResistance", "nonExportableKeys", "stepUpAuthentication"];
  profile.security.authenticationControls = Object.fromEntries(fields.map(f => [f, "UNKNOWN"]));
  assert.equal(validate(request), true, validationMessage(validate));
  assert.equal(schema("update-assessment-profile-request.v2")(request), false);
  for (const field of fields) {
    for (const criticality of ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN"]) {
      profile.security.authenticationControls[field] = criticality;
      assert.equal(validate(request), true, validationMessage(validate));
    }
    for (const invalid of [null, true, 3, "AAL3", "MAYBE"]) {
      profile.security.authenticationControls[field] = invalid;
      assert.equal(validate(request), false);
    }
    delete profile.security.authenticationControls[field];
    assert.equal(validate(request), false);
    profile.security.authenticationControls[field] = "UNKNOWN";
  }
  const response = { ...validAssessmentResponse, profile, profileSchemaVersion: 3 };
  assert.equal(schema("assessment-response.v3")(response), true);
  const old = { workspaceId: response.workspaceId, assessmentId: response.id, version: 0,
    status: "DRAFT", profileSchemaVersion: 1, profile: validAssessmentResponse.profile,
    origin: "CREATED", recordedAt: response.createdAt };
  const middle = structuredClone(old);
  middle.version = 1; middle.profileSchemaVersion = 2;
  middle.profile.security.dataResidencyDetails = { allowedCountries: ["DE"], dataCategories: ["BACKUPS"] };
  const latest = { ...old, version: 2, profileSchemaVersion: 3, profile };
  const page = { items: [old, middle, latest], nextAfterVersion: null };
  assert.equal(schema("assessment-revision-page.v3")(page), true);
  assert.equal(schema("assessment-revision-page.v2")(page), false);
  for (const revision of [old, middle, latest]) {
    for (const version of [1, 2, 3, 4].filter(v => v !== revision.profileSchemaVersion)) {
      assert.equal(schema("assessment-revision-page.v3")({ items: [{ ...revision, profileSchemaVersion: version }], nextAfterVersion: null }), false);
    }
  }
});

test("catalog v4 freezes older facts and scopes enforceable human authentication", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v4.json"));
  const old = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v3.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v4.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.deepEqual(catalog.options.map(({ authenticationControls, ...option }) => option), old.options);
  assert.equal(validate(old), false);
  const select = c => c.options[0].authenticationControls.BROWSER.PARTNERS;
  const original = select(catalog).PHISHING_RESISTANCE;
  for (const field of ["availability", "enforcement", "evidenceStatus", "sourceUrl", "observedAt"]) {
    const invalid = structuredClone(catalog);
    delete select(invalid).PHISHING_RESISTANCE[field];
    assert.equal(validate(invalid), false, field);
  }
  for (const changes of [{ availability: "UNSUPPORTED", enforcement: "SUPPORTED" },
    { availability: "UNKNOWN", enforcement: "SUPPORTED" }, { availability: "OPTIONAL" },
    { enforcement: null }, { certified: true }, { sourceUrl: "https://vendor.example.com" },
    { sourceUrl: "https://user:secret@example.invalid" }, { observedAt: "yesterday" }]) {
    const invalid = structuredClone(catalog);
    select(invalid).PHISHING_RESISTANCE = { ...original, ...changes };
    assert.equal(validate(invalid), false, JSON.stringify(changes));
  }
  for (const client of ["MACHINE_TO_MACHINE", "WEB", "UNKNOWN"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].authenticationControls[client] = {};
    assert.equal(validate(invalid), false);
  }
  const missing = structuredClone(catalog);
  missing.options[0].authenticationControls = {};
  assert.equal(validate(missing), true);
  delete missing.options[0].authenticationControls;
  assert.equal(validate(missing), false);
  const otherPopulation = structuredClone(catalog);
  otherPopulation.options[0].authenticationControls.BROWSER.EVERYONE = select(catalog);
  assert.equal(validate(otherPopulation), false);
  const otherControl = structuredClone(catalog);
  select(otherControl).AAL3 = original;
  assert.equal(validate(otherControl), false);
});

test("profile v4 requires explicit compliance scope and preserves mixed historical formats", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const profile = structuredClone(validAssessmentResponse.profile);
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  profile.security.authenticationControls = { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" };
  const validate = schema("update-assessment-profile-request.v4");
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false);
  for (const status of ["UNKNOWN", "NONE_IDENTIFIED", "TARGETS_IDENTIFIED"]) {
    profile.security.complianceScopeStatus = status;
    assert.equal(validate(request), true, validationMessage(validate));
    // Target-list consistency is a domain invariant (422), not silent schema defaulting.
    for (const api of [1, 2, 3]) assert.equal(schema(`update-assessment-profile-request.v${api}`)(request), false);
  }
  for (const value of [null, true, 1, "COMPLIANT", "NOT_APPLICABLE", ""]) {
    profile.security.complianceScopeStatus = value;
    assert.equal(validate(request), false);
  }
  profile.security.complianceScopeStatus = "NONE_IDENTIFIED";
  profile.security.complianceTargets = [];
  const response = { ...validAssessmentResponse, profile, profileSchemaVersion: 4 };
  assert.equal(schema("assessment-response.v4")(response), true);
  assert.equal(schema("assessment-response.v4")({ ...response, profileSchemaVersion: 3 }), false);
  const latest = { workspaceId: response.workspaceId, assessmentId: response.id, version: 3,
    status: "DRAFT", profileSchemaVersion: 4, profile, origin: "UPDATED", recordedAt: response.createdAt };
  const items = [1, 2, 3, 4].map(format => {
    const item = structuredClone(latest);
    item.version = format - 1; item.profileSchemaVersion = format;
    if (format < 4) delete item.profile.security.complianceScopeStatus;
    if (format < 3) delete item.profile.security.authenticationControls;
    if (format < 2) delete item.profile.security.dataResidencyDetails;
    return item;
  });
  const page = { items, nextAfterVersion: null };
  assert.equal(schema("assessment-revision-page.v4")(page), true);
  for (const api of [1, 2, 3]) assert.equal(schema(`assessment-revision-page.v${api}`)(page), false);
  for (const revision of items) {
    for (const format of [1, 2, 3, 4, 5].filter(v => v !== revision.profileSchemaVersion)) {
      assert.equal(schema("assessment-revision-page.v4")({ items: [{ ...revision, profileSchemaVersion: format }], nextAfterVersion: null }), false);
    }
  }
});

test("profile v6 requires explicit audit scope and binds bounded retention without widening older schemas", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const profile = structuredClone(validAssessmentResponse.profile);
  Object.assign(profile.security, { dataResidencyDetails: { allowedCountries: [], dataCategories: [] },
    authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" },
    complianceScopeStatus: "UNKNOWN", auditabilityRequirements: { selectedCriteria: [], minimumRetentionDays: null } });
  profile.operations.usagePlanning = { scopeDescription: "", assumptions: [], volumes: {} };
  const input = { expectedVersion: 0, profile }; const validate = schema("update-assessment-profile-request.v6");
  assert.equal(validate(input), true, validationMessage(validate));
  for (const criticality of ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "UNKNOWN", "FORBIDDEN"]) {
    profile.security.auditability = criticality;
    for (const criterion of ["AUTHENTICATION_SUCCESS_EVENTS", "AUTHENTICATION_FAILURE_EVENTS", "ADMINISTRATIVE_CHANGE_EVENTS",
      "PROVISIONING_CHANGE_EVENTS", "AUDIT_LOG_EXPORT", "AUDIT_LOG_RETENTION"]) {
      const scope = profile.security.auditabilityRequirements;
      scope.selectedCriteria = [criterion]; scope.minimumRetentionDays = criterion === "AUDIT_LOG_RETENTION" ? 1 : null;
      assert.equal(validate(input), true, validationMessage(validate));
      if (criterion === "AUDIT_LOG_RETENTION") {
        scope.minimumRetentionDays = 36500; assert.equal(validate(input), true);
      }
    }
  }
  profile.security.auditabilityRequirements = { selectedCriteria: [], minimumRetentionDays: null };
  const response = { ...validAssessmentResponse, profileSchemaVersion: 6, profile };
  assert.equal(schema("assessment-response.v6")(response), true);
  for (const api of [1, 2, 3, 4, 5]) {
    assert.equal(schema(`update-assessment-profile-request.v${api}`)(input), false);
    assert.equal(schema(`assessment-response.v${api}`)(response), false);
  }
  for (const scope of [null, {}, { selectedCriteria: [] }, { minimumRetentionDays: null },
    { selectedCriteria: null, minimumRetentionDays: null }, { selectedCriteria: "AUDIT_LOG_EXPORT", minimumRetentionDays: null },
    { selectedCriteria: ["AUDIT_LOG_EXPORT", "AUDIT_LOG_EXPORT"], minimumRetentionDays: null },
    ...[null, 0, "VERIFIED", " AUDIT_LOG_EXPORT"].map(value => ({ selectedCriteria: [value], minimumRetentionDays: null })),
    { selectedCriteria: [], minimumRetentionDays: 30 }, { selectedCriteria: ["AUDIT_LOG_EXPORT"], minimumRetentionDays: 30 },
    ...[null, 0, -1, 36501, 1.5, "30", true].map(value => ({ selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: value })),
    { selectedCriteria: [], minimumRetentionDays: null, configurationVerified: true }]) {
    profile.security.auditabilityRequirements = scope; assert.equal(validate(input), false, JSON.stringify(scope));
  }
  delete profile.security.auditabilityRequirements; assert.equal(validate(input), false);
});

test("v6 history preserves all six exact formats and rejects mislabeled or forged snapshots", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const items = [1, 2, 3, 4, 5, 6].map(format => {
    const profile = structuredClone(validAssessmentResponse.profile);
    if (format >= 2) profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
    if (format >= 3) profile.security.authenticationControls = { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" };
    if (format >= 4) profile.security.complianceScopeStatus = "UNKNOWN";
    if (format >= 5) profile.operations.usagePlanning = { scopeDescription: "", assumptions: [], volumes: {} };
    if (format >= 6) profile.security.auditabilityRequirements = { selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: 30 };
    return { workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id,
      version: format - 1, status: "DRAFT", profileSchemaVersion: format, profile, origin: "UPDATED", recordedAt: validAssessmentResponse.createdAt };
  });
  const validate = schema("assessment-revision-page.v6"); const page = { items, nextAfterVersion: null };
  assert.equal(validate(page), true, validationMessage(validate));
  for (const api of [1, 2, 3, 4, 5]) assert.equal(schema(`assessment-revision-page.v${api}`)(page), false);
  for (const item of items) {
    for (const format of [1, 2, 3, 4, 5, 6, 7].filter(value => value !== item.profileSchemaVersion))
      assert.equal(validate({ items: [{ ...item, profileSchemaVersion: format }], nextAfterVersion: null }), false);
  }
  assert.equal(validate({ ...page, nextAfterVersion: -1 }), false);
  assert.equal(validate({ items: [{ ...items[5], configurationVerified: true }], nextAfterVersion: null }), false);
});

test("profile v5 records bounded usage inputs without turning unknowns into zero", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const profile = structuredClone(validAssessmentResponse.profile);
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  profile.security.authenticationControls = { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" };
  profile.security.complianceScopeStatus = "UNKNOWN";
  const validate = schema("update-assessment-profile-request.v5");
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false);
  const usage = { scopeDescription: "", assumptions: [], volumes: {} };
  profile.operations.usagePlanning = usage;
  assert.equal(validate(request), true, validationMessage(validate));
  assert.deepEqual(usage.volumes, {});
  for (const api of [1, 2, 3, 4]) assert.equal(schema(`update-assessment-profile-request.v${api}`)(request), false);
  const metrics = ["MONTHLY_ACTIVE_USERS", "ENTERPRISE_SSO_CONNECTIONS", "MONTHLY_M2M_TOKEN_ISSUANCES", "PEAK_HUMAN_LOGINS_PER_SECOND"];
  for (const metric of metrics) {
    for (const basis of ["ASSUMED", "OBSERVED"]) {
      for (const value of [0, 9007199254740991]) {
        usage.volumes[metric] = { basis, value };
        assert.equal(validate(request), true, validationMessage(validate));
      }
    }
    for (const value of [null, {}, { basis: "ASSUMED" }, { value: 0 },
      { basis: "UNKNOWN", value: 0 }, { basis: 1, value: 0 }, { basis: "OBSERVED", value: -1 },
      { basis: "ASSUMED", value: 0.5 }, { basis: "ASSUMED", value: "0" },
      { basis: "ASSUMED", value: false }, { basis: "ASSUMED", value: 9007199254740992 },
      { basis: "OBSERVED", value: 0, price: 0 }]) {
      usage.volumes[metric] = value;
      assert.equal(validate(request), false, JSON.stringify(value));
    }
    usage.volumes[metric] = { basis: "ASSUMED", value: 0 };
  }
  for (const field of ["scopeDescription", "assumptions", "volumes"]) {
    const old = usage[field]; delete usage[field];
    assert.equal(validate(request), false);
    usage[field] = null; assert.equal(validate(request), false);
    usage[field] = old;
  }
  for (const assumptions of [[""], [" \t"], ["same", "same"], ["x".repeat(501)], [null], [1],
    Array.from({ length: 11 }, (_, i) => `Assumption ${i}`)]) {
    usage.assumptions = assumptions; assert.equal(validate(request), false);
  }
  usage.assumptions = Array.from({ length: 10 }, (_, i) => "x".repeat(499) + i);
  usage.scopeDescription = "x".repeat(500);
  assert.equal(validate(request), true);
  usage.scopeDescription += "x"; assert.equal(validate(request), false);
  usage.scopeDescription = "Synthetic pilot";
  usage.volumes.REGISTERED_USERS = { basis: "ASSUMED", value: 0 };
  assert.equal(validate(request), false); delete usage.volumes.REGISTERED_USERS;
  assert.equal(validate(request), true);
  const response = { ...validAssessmentResponse, profileSchemaVersion: 5, profile };
  assert.equal(schema("assessment-response.v5")(response), true);
  assert.equal(schema("assessment-response.v5")({ ...response, profileSchemaVersion: 4 }), false);
  const items = [1, 2, 3, 4, 5].map(format => {
    const revisionProfile = structuredClone(profile);
    if (format < 5) delete revisionProfile.operations.usagePlanning;
    if (format < 4) delete revisionProfile.security.complianceScopeStatus;
    if (format < 3) delete revisionProfile.security.authenticationControls;
    if (format < 2) delete revisionProfile.security.dataResidencyDetails;
    return { workspaceId: response.workspaceId, assessmentId: response.id, version: format - 1,
      status: "DRAFT", profileSchemaVersion: format, profile: revisionProfile, origin: "UPDATED", recordedAt: response.createdAt };
  });
  const page = { items, nextAfterVersion: null };
  assert.equal(schema("assessment-revision-page.v5")(page), true, validationMessage(schema("assessment-revision-page.v5")));
  for (const api of [1, 2, 3, 4]) assert.equal(schema(`assessment-revision-page.v${api}`)(page), false);
  for (const revision of items) {
    for (const format of [1, 2, 3, 4, 5, 6].filter(v => v !== revision.profileSchemaVersion)) {
      assert.equal(schema("assessment-revision-page.v5")({ items: [{ ...revision, profileSchemaVersion: format }], nextAfterVersion: null }), false);
    }
  }
});

test("provider catalog drafts require scoped provenance but cannot claim review or publication", async () => {
  const fixture = await readJson(path.join(fixturesRoot, "provider-catalog-draft.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/provider-catalog-draft.v1.schema.json");
  assert.equal(validate(fixture), true, validationMessage(validate));
  for (const version of [1, 2, 3, 4]) {
    assert.equal(ajv.getSchema(`https://authweave.dev/contracts/synthetic-provider-catalog.v${version}.schema.json`)(fixture), false);
  }
  for (const field of ["providerId", "product", "plan", "deployment", "region", "configuration", "compatibility", "residency", "authenticationControls"]) {
    const invalid = structuredClone(fixture); delete invalid.options[0][field];
    assert.equal(validate(invalid), false, field);
  }
  const selectors = [option => option.facts.SCIM, option => option.compatibility.clients.BROWSER,
    option => option.residency.USER_PROFILES, option => option.authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE];
  for (const select of selectors) {
    for (const field of ["conditions", "evidence"]) {
      const invalid = structuredClone(fixture); delete select(invalid.options[0])[field]; assert.equal(validate(invalid), false);
    }
    for (const field of ["sourceUrl", "observedAt", "summary"]) {
      const invalid = structuredClone(fixture); delete select(invalid.options[0]).evidence[field]; assert.equal(validate(invalid), false);
    }
    const reviewed = structuredClone(fixture); select(reviewed.options[0]).evidenceStatus = "REVIEWED";
    assert.equal(validate(reviewed), false);
    const valid = structuredClone(fixture); select(valid.options[0]).conditions = [];
    assert.equal(validate(valid), true, validationMessage(validate));
    for (const sourceUrl of ["http://example.invalid", "file:///private/example", "https://user:secret@example.invalid", "/source"]) {
      const invalid = structuredClone(fixture); select(invalid.options[0]).evidence.sourceUrl = sourceUrl;
      assert.equal(validate(invalid), false, sourceUrl);
    }
  }
  for (const forged of [{ approvedBy: "owner" }, { evidenceStatus: "REVIEWED" }, { kind: "APPROVED" }, { schemaVersion: 2 }]) {
    assert.equal(validate({ ...fixture, ...forged }), false);
  }
  for (const blank of ["", " \t", "\u00a0\u2003\ufeff"]) {
    const invalid = structuredClone(fixture); invalid.options[0].facts.SCIM.evidence.summary = blank;
    assert.equal(validate(invalid), false);
  }
  const unicode = structuredClone(fixture); unicode.options[0].facts.SCIM.evidence.summary = "\uD83D\uDD12".repeat(1000);
  assert.equal(validate(unicode), true); unicode.options[0].facts.SCIM.evidence.summary += "x"; assert.equal(validate(unicode), false);
  const empty = structuredClone(fixture); empty.options = []; assert.equal(validate(empty), false);
  const maximum = structuredClone(fixture);
  maximum.options = Array.from({ length: 100 }, (_, i) => ({ ...structuredClone(fixture.options[0]), id: `example-${i}`, configuration: `Configuration ${i}` }));
  assert.equal(validate(maximum), true, validationMessage(validate));
  maximum.options.push(structuredClone(fixture.options[0])); assert.equal(validate(maximum), false);
});

test("reserved published snapshot format is distinct from draft and synthetic catalogs and requires explicit metadata", async () => {
  const fixture = await readJson(path.join(fixturesRoot, "published-provider-catalog-snapshot.format-valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/published-provider-catalog-snapshot.v1.schema.json");
  assert.equal(validate(fixture), true, validationMessage(validate));
  for (const schema of ["provider-catalog-draft.v1", ...[1, 2, 3, 4].map(v => `synthetic-provider-catalog.v${v}`)]) {
    assert.equal(ajv.getSchema(`https://authweave.dev/contracts/${schema}.schema.json`)(fixture), false);
  }
  for (const field of Object.keys(fixture)) {
    const input = structuredClone(fixture); delete input[field]; assert.equal(validate(input), false, field);
    if (field !== "previousSnapshot") { input[field] = null; assert.equal(validate(input), false, field); }
  }
  for (const change of [{ schemaVersion: 2 }, { schemaVersion: "1" }, { kind: "SYNTHETIC" },
    { canonicalizationVersion: "other" }, { approvalGranted: true }, { curatorSubject: "private" },
    { contentSha256: "A".repeat(64) }, { snapshotSha256: "a".repeat(63) }, { publication: { approved: true } },
    { factEvidenceStatuses: [] }, { factEvidenceStatuses: Array(6801).fill(fixture.factEvidenceStatuses[0]) },
    { factEvidenceStatuses: [{ ...fixture.factEvidenceStatuses[0], evidenceStatus: "UNREVIEWED" }] },
    { factEvidenceStatuses: [{ ...fixture.factEvidenceStatuses[0], verdict: "SOURCE_SUPPORTS_CLAIM" }] },
    { catalog: { ...fixture.catalog, kind: "PROVIDER_CATALOG_DRAFT" } }]) {
    assert.equal(validate({ ...fixture, ...change }), false, JSON.stringify(change).slice(0, 150));
  }
  for (const field of ["providerId", "product", "plan", "region", "configuration"]) {
    const input = structuredClone(fixture); delete input.catalog.options[0][field]; assert.equal(validate(input), false, field);
  }
  for (const select of [o => o.facts.SCIM, o => o.compatibility.clients.BROWSER, o => o.residency.USER_PROFILES,
    o => o.authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE]) {
    for (const field of ["sourceUrl", "observedAt", "summary"]) {
      const input = structuredClone(fixture); delete select(input.catalog.options[0]).evidence[field];
      assert.equal(validate(input), false, field);
    }
    const input = structuredClone(fixture); select(input.catalog.options[0]).evidenceStatus = "REVIEWED";
    assert.equal(validate(input), false, "Declared statuses must not silently mutate draft fact shapes.");
  }
});

test("baseline references pin immutable identity and manifest digest, not just a catalog label", async () => {
  const fixture = await readJson(path.join(fixturesRoot, "published-provider-catalog-snapshot.format-valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-baseline-reference.v1.schema.json");
  const reference = { snapshotId: fixture.snapshotId, catalogVersion: fixture.catalog.catalogVersion,
    snapshotSha256: fixture.snapshotSha256 };
  assert.equal(validate(reference), true, validationMessage(validate));
  for (const field of Object.keys(reference)) {
    const input = { ...reference }; delete input[field]; assert.equal(validate(input), false, field);
  }
  for (const change of [{ snapshotId: "label" }, { snapshotSha256: "bad" }, { catalogVersion: "" },
    { approved: true }, { signature: "self-declared" }, { publicationDecisionId: fixture.publication.decisionId }]) {
    assert.equal(validate({ ...reference, ...change }), false);
  }
  const snapshot = ajv.getSchema("https://authweave.dev/contracts/published-provider-catalog-snapshot.v1.schema.json");
  assert.equal(snapshot({ ...fixture, previousSnapshot: reference }), true,
    "Self-parent and reused version are Core semantic issues, not JSON shape rules or proof of trust.");
});

test("format-valid snapshot fixture has independent canonical content and manifest hashes, neither a signature", async () => {
  const fixture = await readJson(path.join(fixturesRoot, "published-provider-catalog-snapshot.format-valid.json"));
  // Fixture timestamps are already canonical UTC strings; Core's typed inspection normalizes equivalent instants.
  const ordered = value => Array.isArray(value)
    ? value.map(ordered).sort((a, b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0)
    : value && typeof value === "object"
      ? Object.fromEntries(Object.keys(value).sort().map(key => [key, ordered(value[key])])) : value;
  const sha = value => createHash("sha256").update(JSON.stringify(ordered(value))).digest("hex");
  const { snapshotSha256, ...payload } = fixture;
  assert.equal(sha(fixture.catalog), fixture.contentSha256); assert.equal(sha(payload), snapshotSha256);
  const draft = { ...fixture.catalog, kind: "PROVIDER_CATALOG_DRAFT" };
  assert.notEqual(sha(draft), fixture.contentSha256);
  const proposal = await readJson(path.join(fixturesRoot, "catalog-change-preview-request.valid.json"));
  const validateProposal = ajv.getSchema("https://authweave.dev/contracts/catalog-change-preview-request.v1.schema.json");
  assert.equal(validateProposal({ ...proposal, base: fixture }), false);
  assert.equal(validateProposal({ ...proposal, baselineReference: { snapshotId: fixture.snapshotId,
    catalogVersion: fixture.catalog.catalogVersion, snapshotSha256 } }), false);
  const corrupt = { ...fixture, contentSha256: "0".repeat(64), snapshotSha256: "0".repeat(64) };
  assert.equal(ajv.getSchema("https://authweave.dev/contracts/published-provider-catalog-snapshot.v1.schema.json")(corrupt), true,
    "Hash equality, targets, authentic publication and trust require Core checks, not JSON Schema alone.");
});

test("catalog draft shape and semantic review are deliberately separate", async () => {
  const fixture = await readJson(path.join(fixturesRoot, "provider-catalog-draft.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/provider-catalog-draft.v1.schema.json");
  const input = structuredClone(fixture);
  input.options[0].residency.USER_PROFILES.coverage = "UNKNOWN";
  input.options[0].residency.USER_PROFILES.storageCountries = ["ZZ"];
  input.options[0].authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE.availability = "UNSUPPORTED";
  input.options.push(structuredClone(input.options[0]));
  assert.equal(validate(input), true, "The Core dry run must report these contradictions; structural validity is not approval.");
  const unknown = structuredClone(fixture);
  unknown.options[0].facts.SCIM.availability = "UNKNOWN";
  unknown.options[0].facts.SCIM.evidence.observedAt = "2099-01-01T00:00:00Z";
  assert.equal(validate(unknown), true);
  delete unknown.options[0].facts.SCIM; assert.equal(validate(unknown), true);
});

test("catalog change previews require bound inputs and cannot accept forged workflow authority", async () => {
  const input = await readJson(path.join(fixturesRoot, "catalog-change-preview-request.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-change-preview-request.v1.schema.json");
  assert.equal(validate(input), true, validationMessage(validate));
  for (const field of ["schemaVersion", "proposalId", "rationale", "expectedBaseSha256", "base", "candidate"]) {
    const invalid = structuredClone(input); delete invalid[field]; assert.equal(validate(invalid), false, field);
    invalid[field] = null; assert.equal(validate(invalid), false, field);
  }
  for (const extra of [{ proposalState: "APPROVED" }, { curatorId: "forged" }, { approvalGranted: true }]) {
    assert.equal(validate({ ...input, ...extra }), false);
  }
  for (const expectedBaseSha256 of ["", "A".repeat(64), "a".repeat(63), "a".repeat(65), 1]) {
    assert.equal(validate({ ...input, expectedBaseSha256 }), false);
  }
  assert.equal(validate({ ...input, expectedBaseSha256: "0".repeat(64) }), true, "Digest mismatch is a preview blocker, not a malformed hash.");
  for (const rationale of ["", " \t", "\u00a0\u2003", "x".repeat(1001), 1]) assert.equal(validate({ ...input, rationale }), false);
  assert.equal(validate({ ...input, rationale: "\uD83D\uDD12".repeat(1000) }), true);
  assert.equal(validate({ ...input, schemaVersion: 2 }), false);
  const invalid = structuredClone(input); invalid.candidate.options[0].facts.SCIM.evidenceStatus = "REVIEWED";
  assert.equal(validate(invalid), false);
});

test("catalog change reports preserve typed before/after values and fail closed", async () => {
  const input = await readJson(path.join(fixturesRoot, "catalog-change-preview-request.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-change-preview.v1.schema.json");
  const review = catalogVersion => ({ catalogVersion, contentSha256: "0".repeat(64), status: "VALID_DRAFT", optionCount: 1,
    factCount: 9, freshness: { current: 9, stale: 0, future: 0 }, issues: [] });
  const report = { scope: "CATALOG_CHANGE_PREVIEW", policyVersion: "catalog-change-preview-1",
    canonicalizationVersion: "catalog-draft-canonical-json-1", proposalId: input.proposalId, proposalSha256: "1".repeat(64),
    rationale: input.rationale, proposalState: "PROPOSED", evaluatedAt: "2026-09-12T12:00:00Z", status: "REVIEW_REQUIRED",
    diffComputed: true, catalogVersionChanged: true, baselineVerified: false, sourceVerificationPerformed: false,
    approvalGranted: false, writesPerformed: false, evaluationReady: false, impactAnalysisPerformed: false,
    baseReview: review(input.base.catalogVersion), candidateReview: review(input.candidate.catalogVersion), blockers: [],
    affectedOptionIds: [input.base.options[0].id], optionChanges: [], factChanges: [{ optionId: input.base.options[0].id,
      path: "facts.SCIM", factKind: "CAPABILITY", changeType: "MODIFIED", aspects: ["CLAIM"], evidenceStatus: "UNREVIEWED",
      before: input.base.options[0].facts.SCIM, after: input.candidate.options[0].facts.SCIM }] };
  assert.equal(validate(report), true, validationMessage(validate));
  for (const field of ["baselineVerified", "sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady", "impactAnalysisPerformed"]) {
    assert.equal(validate({ ...report, [field]: true }), false, field);
  }
  for (const change of [{ factKind: "RESIDENCY" }, { evidenceStatus: "REVIEWED" }, { before: null }, { after: null }, { aspects: [] }, { aspects: ["PRESENCE"] }]) {
    const invalid = structuredClone(report); Object.assign(invalid.factChanges[0], change); assert.equal(validate(invalid), false, JSON.stringify(change));
  }
  for (const changeType of ["ADDED", "REMOVED"]) {
    const valid = structuredClone(report); valid.factChanges[0].changeType = changeType; valid.factChanges[0].aspects = ["PRESENCE"];
    valid.factChanges[0][changeType === "ADDED" ? "before" : "after"] = null;
    assert.equal(validate(valid), true, validationMessage(validate));
    valid.factChanges[0].aspects = ["CLAIM"]; assert.equal(validate(valid), false);
  }
  assert.equal(validate({ ...report, status: "NO_CONTENT_CHANGES" }), false);
  assert.equal(validate({ ...report, status: "BLOCKED", blockers: ["BASE_DIGEST_MISMATCH"] }), false);
  const blocked = { ...report, status: "BLOCKED", blockers: ["BASE_DIGEST_MISMATCH"], diffComputed: false,
    affectedOptionIds: [], optionChanges: [], factChanges: [] };
  assert.equal(validate(blocked), true, validationMessage(validate));
  assert.equal(validate({ ...blocked, blockers: [] }), false);
  assert.equal(validate({ ...blocked, diffComputed: true }), false);
  assert.equal(validate({ ...blocked, proposalState: "APPROVED" }), false);
  const unchanged = { ...blocked, status: "NO_CONTENT_CHANGES", diffComputed: true, blockers: [] };
  assert.equal(validate(unchanged), true);

  const snapshot = { proposalId: input.proposalId, version: 0, state: "PROPOSED", requestSchemaVersion: 1,
    proposalSha256: report.proposalSha256, recordedAt: report.evaluatedAt, request: input, preview: report };
  const validateSnapshot = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-snapshot.v1.schema.json");
  assert.equal(validateSnapshot(snapshot), true, validationMessage(validateSnapshot));
  for (const invalid of [{ state: "APPROVED" }, { version: -1 }, { version: 9007199254740992 }, { requestSchemaVersion: 2 },
    { preview: blocked }, { preview: unchanged }, { actorId: "forged" }]) assert.equal(validateSnapshot({ ...snapshot, ...invalid }), false);
  const validatePage = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-revision-page.v1.schema.json");
  assert.equal(validatePage({ items: [snapshot], nextAfterVersion: null }), true);
  assert.equal(validatePage({ items: [], nextAfterVersion: null }), true);
  assert.equal(validatePage({ items: [snapshot], nextAfterVersion: -1 }), false);
  assert.equal(validatePage({ items: Array(101).fill(snapshot), nextAfterVersion: 0 }), false);
});

test("catalog impact remains conditional, version-bound and explicit about incomplete coverage", async () => {
  const report = await readJson(path.join(fixturesRoot, "catalog-impact-preview.valid.json"));
  const golden = await readJson(path.join(fixturesRoot, "catalog-impact-probes.expected.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-preview.v1.schema.json");
  assert.equal(validate(report), true, validationMessage(validate));
  assert.equal(report.caseSetSha256, golden.caseSetSha256);
  assert.equal(report.caseSetVersion, golden.caseSetVersion);
  assert.equal(report.caseDefinitions.length, 24);
  assert.deepEqual(report.cases.filter(c => c.conditionalResultChanged).map(c => c.caseId), ["required-scim"]);
  const stored = { ...report, storedProposalVersion: 0, storedRequestDigestVerified: true };
  assert.equal(validate(stored), true, validationMessage(validate));
  for (const field of ["coverageComplete", "baselineVerified", "sourceVerificationPerformed", "approvalGranted",
    "writesPerformed", "evaluationReady", "recommendationReady"]) {
    assert.equal(validate({ ...report, [field]: true }), false, field);
  }
  for (const change of [{ storedProposalVersion: 0 }, { storedRequestDigestVerified: true }, { impactAnalysisPerformed: false },
    { hypotheticalEvaluationPerformed: false }, { analysisBasis: "VERIFIED_EVIDENCE" }, { caseSetSha256: "unversioned" },
    { caseDefinitions: report.caseDefinitions.slice(1) }, { status: "APPROVED" }, { score: 100 }]) {
    assert.equal(validate({ ...report, ...change }), false, JSON.stringify(change));
  }
  for (const value of [-1, 9007199254740992, 1.5]) assert.equal(validate({ ...stored, storedProposalVersion: value }), false);
  for (const change of [{ conditionalOutcome: "PASS" }, { conditionalOutcome: "FAIL" }, { freshness: null },
    { factPresent: false }, { optionPresent: false }]) {
    const invalid = structuredClone(report); Object.assign(invalid.cases[0].before, change);
    assert.equal(validate(invalid), false, JSON.stringify(change));
  }
  const absent = structuredClone(report);
  Object.assign(absent.cases[0].before, { optionPresent: false, factPresent: false, conditionalOutcome: "INDETERMINATE",
    reason: "OPTION_ABSENT", freshness: null, conditionsRecorded: false });
  assert.equal(validate(absent), true, validationMessage(validate));
  const uncovered = { ...report, cases: [], uncoveredChanges: [{ optionId: "example-managed-eu",
    factPath: "compatibility.membership.SINGLE_ORGANIZATION_PER_USER", reason: "NO_PROBE_FOR_FACT_PATH" }] };
  assert.equal(validate(uncovered), true, validationMessage(validate));
  const blocked = { ...report, status: "BLOCKED", impactAnalysisPerformed: false, hypotheticalEvaluationPerformed: false,
    cases: [], uncoveredChanges: [], changePreview: { ...report.changePreview, status: "BLOCKED", diffComputed: false,
      blockers: ["BASE_DIGEST_MISMATCH"], affectedOptionIds: [], optionChanges: [], factChanges: [] } };
  assert.equal(validate(blocked), true, validationMessage(validate));
  assert.equal(validate({ ...blocked, impactAnalysisPerformed: true }), false);
  assert.equal(validate({ ...blocked, cases: report.cases }), false);
  assert.equal(validate({ ...blocked, uncoveredChanges: uncovered.uncoveredChanges }), false);
  assert.equal(validate({ ...blocked, changePreview: report.changePreview }), false);
});

test("full-profile impact scenarios preserve all three seeds with explicit unknown newer fields", async () => {
  const definitions = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog/impact-scenarios.v1.json"));
  const seeds = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/seed/assessments.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-scenario-impact.v1.schema.json#/$defs/definition");
  assert.deepEqual(definitions.map(d => d.id), seeds.map(s => s.key));
  for (const [i, definition] of definitions.entries()) {
    assert.equal(validate(definition), true, validationMessage(validate));
    const profile = structuredClone(definition.profile);
    assert.deepEqual(profile.security.dataResidencyDetails, { allowedCountries: [], dataCategories: [] });
    assert.deepEqual(profile.security.authenticationControls, { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" });
    assert.equal(profile.security.complianceScopeStatus, "UNKNOWN");
    assert.deepEqual(profile.operations.usagePlanning, { scopeDescription: "", assumptions: [], volumes: {} });
    delete profile.security.dataResidencyDetails; delete profile.security.authenticationControls;
    delete profile.security.complianceScopeStatus; delete profile.operations.usagePlanning;
    assert.deepEqual(profile, seeds[i].profile);
    const missing = structuredClone(definition); delete missing.profile.operations.usagePlanning;
    assert.equal(validate(missing), false);
    assert.equal(validate({ ...definition, profileSchemaVersion: 1 }), false);
  }
});

test("scenario impact wire shape forbids readiness claims and preserves storage and absence boundaries", async () => {
  const { caseDefinitions, cases, ...common } = await readJson(path.join(fixturesRoot, "catalog-impact-preview.valid.json"));
  const definitions = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog/impact-scenarios.v1.json"));
  const golden = await readJson(path.join(fixturesRoot, "catalog-scenario-impact.expected.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-scenario-impact.v1.schema.json");
  const check = { checkId: "provisioning.scim|facts.SCIM", profilePath: "provisioning.scim", factPath: "facts.SCIM", usesFact: true,
    factPresent: true, conditionalOutcome: "WOULD_SATISFY", reason: "REQUIRED_CLAIM_AVAILABLE", freshness: "CURRENT", conditionsRecorded: true };
  const side = { optionPresent: true, conditionalStatus: "WOULD_SATISFY_CHECKED_REQUIREMENTS", checks: [check] };
  const report = { ...common, scope: "CATALOG_PROFILE_SCENARIO_IMPACT", policyVersion: "catalog-scenario-impact-1",
    profilePolicyVersion: "eligibility-preflight-4", caseSetVersion: golden.caseSetVersion, caseSetSha256: golden.caseSetSha256,
    deferredPaths: ["security.browserTokenExposureMinimization", "security.auditability", "security.assurance", "security.complianceTargets",
      "security.authenticationControls", "provisioning", "operations"], scenarioDefinitions: definitions,
    scenarios: [{ scenarioId: "b2b-saas", optionId: "example-managed-eu", scopeChanged: false, affectedFactPaths: ["facts.SCIM"],
      conditionalStatusChanged: false, changedCheckIds: [], before: side, after: side }] };
  assert.equal(validate(report), true, validationMessage(validate));
  assert.equal(validate({ ...report, storedProposalVersion: 0, storedRequestDigestVerified: true }), true);
  for (const field of ["coverageComplete", "baselineVerified", "sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady", "recommendationReady"]) {
    assert.equal(validate({ ...report, [field]: true }), false, field);
  }
  for (const change of [{ deferredPaths: [] }, { scenarioDefinitions: [] }, { storedRequestDigestVerified: true },
    { storedProposalVersion: 0 }, { impactAnalysisPerformed: false }, { hypotheticalEvaluationPerformed: false }, { recommendation: "winner" }]) {
    assert.equal(validate({ ...report, ...change }), false, JSON.stringify(change));
  }
  for (const change of [{ conditionalOutcome: "PASS" }, { conditionalOutcome: "FAIL" }, { usesFact: false }, { freshness: null }, { factPath: null }]) {
    const invalid = structuredClone(report); Object.assign(invalid.scenarios[0].before.checks[0], change);
    assert.equal(validate(invalid), false, JSON.stringify(change));
  }
  const absent = structuredClone(report);
  Object.assign(absent.scenarios[0].before, { optionPresent: false, conditionalStatus: "OPTION_ABSENT" });
  Object.assign(absent.scenarios[0].before.checks[0], { factPresent: false, conditionalOutcome: "INDETERMINATE", reason: "OPTION_ABSENT", freshness: null, conditionsRecorded: false });
  assert.equal(validate(absent), true, validationMessage(validate));
  absent.scenarios[0].before.conditionalStatus = "WOULD_SATISFY_CHECKED_REQUIREMENTS";
  assert.equal(validate(absent), false);
  const missingFact = structuredClone(report);
  Object.assign(missingFact.scenarios[0].before.checks[0], { factPresent: false, freshness: null, conditionsRecorded: false });
  assert.equal(validate(missingFact), false);
  const unusedFact = structuredClone(report);
  Object.assign(unusedFact.scenarios[0].before.checks[0], { usesFact: false, conditionalOutcome: "NOT_APPLIED", reason: "NO_REQUIREMENT" });
  assert.equal(validate(unusedFact), true, validationMessage(validate));
  const blocked = { ...report, status: "BLOCKED", impactAnalysisPerformed: false, hypotheticalEvaluationPerformed: false,
    scenarios: [], uncoveredChanges: [], changePreview: { ...report.changePreview, status: "BLOCKED", diffComputed: false,
      blockers: ["BASE_DIGEST_MISMATCH"], affectedOptionIds: [], optionChanges: [], factChanges: [] } };
  assert.equal(validate(blocked), true, validationMessage(validate));
  assert.equal(validate({ ...blocked, scenarios: report.scenarios }), false);
  assert.equal(validate({ ...blocked, hypotheticalEvaluationPerformed: true }), false);
  const stored = { reportId: "44444444-4444-4444-8444-444444444444", reportNumber: 1,
    proposalId: report.proposalId, proposalVersion: 0, reportSchemaVersion: 1,
    canonicalizationVersion: "catalog-draft-canonical-json-1", proposalSha256: report.proposalSha256,
    reportSha256: "1".repeat(64), recordedAt: report.evaluatedAt,
    report: { ...report, storedProposalVersion: 0, storedRequestDigestVerified: true } };
  const validateStored = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-report.v1.schema.json");
  assert.equal(validateStored(stored), true, validationMessage(validateStored));
  for (const invalid of [{ reportNumber: 0 }, { reportNumber: 9007199254740992 }, { proposalVersion: -1 },
    { reportSchemaVersion: 2 }, { canonicalizationVersion: "unversioned" }, { reportSha256: "bad" }, { report }, { approved: true }]) {
    assert.equal(validateStored({ ...stored, ...invalid }), false, JSON.stringify(invalid));
  }
  assert.equal(validateStored({ ...stored, report: { ...stored.report, approvalGranted: true } }), false);
  const validatePage = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-report-page.v1.schema.json");
  assert.equal(validatePage({ items: [stored], nextAfterReportNumber: 1 }), true);
  assert.equal(validatePage({ items: [], nextAfterReportNumber: null }), true);
  assert.equal(validatePage({ items: [stored], nextAfterReportNumber: -1 }), false);
  assert.equal(validatePage({ items: Array(101).fill(stored), nextAfterReportNumber: 1 }), false);
});

test("impact recording events do not claim human authorization or embed report contents", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-report-event.v1.schema.json");
  const event = { id: "11111111-1111-4111-8111-111111111111", reportId: "44444444-4444-4444-8444-444444444444",
    proposalId: "33333333-3333-4333-8333-333333333333", proposalVersion: 0, reportSha256: "1".repeat(64),
    action: "catalog-impact.recorded", actorType: "SERVICE", actorId: "core-api-local-catalog",
    correlationId: "22222222-2222-4222-8222-222222222222", outcome: "SUCCEEDED", occurredAt: "2026-09-13T12:00:00Z" };
  assert.equal(validate(event), true, validationMessage(validate));
  for (const invalid of [{ actorType: "CURATOR" }, { actorId: "human" }, { action: "catalog-proposal.approved" },
    { report: {} }, { proposalVersion: -1 }, { reportSha256: "bad" }, { outcome: "APPROVED" }]) {
    assert.equal(validate({ ...event, ...invalid }), false, JSON.stringify(invalid));
  }
});

test("stored proposal events describe local writes without claiming authorized review", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-event.v1.schema.json");
  const event = { id: "11111111-1111-4111-8111-111111111111", proposalId: "22222222-2222-4222-8222-222222222222",
    version: 0, previousVersion: null, action: "catalog-proposal.created", actorType: "SERVICE", actorId: "core-api-local-catalog",
    correlationId: "33333333-3333-4333-8333-333333333333", outcome: "SUCCEEDED", proposalSha256: "0".repeat(64), occurredAt: "2026-09-13T12:00:00Z" };
  assert.equal(validate(event), true, validationMessage(validate));
  assert.equal(validate({ ...event, version: 1, previousVersion: 0, action: "catalog-proposal.revised" }), true);
  for (const invalid of [{ previousVersion: 0 }, { version: 1 }, { actorType: "CURATOR" }, { actorId: "forged" },
    { action: "catalog-proposal.approved" }, { rationale: "Raw input" }, { outcome: "FAILED" }, { version: 9007199254740992 }]) {
    assert.equal(validate({ ...event, ...invalid }), false);
  }
  const page = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-event-page.v1.schema.json");
  assert.equal(page({ items: [event], nextAfterVersion: 0 }), true);
  assert.equal(page({ items: [], nextAfterVersion: null }), true);
  assert.equal(page({ items: [{ ...event, actorType: "CURATOR" }], nextAfterVersion: null }), false);
});

test("request rejects unknown fields", () => {
  const request = structuredClone(validRequest);
  request.unknown = true;

  assert.equal(validateRequest(request), false);
});

test("request rejects unsupported contract versions", () => {
  const request = structuredClone(validRequest);
  request.context.contractVersion = "2.0";

  assert.equal(validateRequest(request), false);
});

test("result must remain bound to the originating operation", () => {
  assert.doesNotThrow(() => assertResultMatchesOperation(validRequest, validResult));

  const result = structuredClone(validResult);
  result.context.operationId = "66666666-6666-4666-8666-666666666666";

  assert.throws(
    () => assertResultMatchesOperation(validRequest, result),
    ({ code }) => code === operationErrorCodes.resultContextMismatch,
  );
});

test("stale assessment results are rejected", () => {
  const result = structuredClone(validResult);
  result.context.assessment.basedOnVersion = 6;

  assert.throws(
    () => assertResultMatchesOperation(validRequest, result),
    ({ code }) => code === operationErrorCodes.staleAssessmentVersion,
  );
});

test("tool invocations outside the operation allowlist are rejected", () => {
  const result = structuredClone(validResult);
  result.toolInvocations.push({
    toolName: "provider-catalog.publish",
    outcome: "succeeded",
  });

  assert.throws(
    () => assertResultMatchesOperation(validRequest, result),
    ({ code }) => code === operationErrorCodes.forbiddenTool,
  );
});
