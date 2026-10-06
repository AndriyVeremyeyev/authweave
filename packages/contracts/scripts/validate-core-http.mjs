import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { auditabilityPreviewFromCore } from "../../../apps/web/src/lib/assessment/auditability-preview.ts";
import { comparisonFromCore } from "../../../apps/web/src/lib/assessment/comparison.ts";
import { lifecyclePreviewFromCore } from "../../../apps/web/src/lib/assessment/provisioning-lifecycle.ts";
import { lifecycleV2PreviewFromCore } from "../../../apps/web/src/lib/assessment/provisioning-lifecycle-v2.ts";
import { expectedAnalysis, validateArchitectureConfiguration } from "../tests/helpers/architecture-configuration-spec.mjs";
import { architectureConfigurationFromCore } from "../../../apps/web/src/lib/assessment/architecture-configuration.ts";
import { validateConfigurationRegression } from "../tests/helpers/architecture-configuration-regression-spec.mjs";

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
let auditabilityCoverageSamples = 0;
let combinedConstraintSamples = 0;
let combinedConsumerSamples = 0;
let lifecycleSamples = 0;
let lifecycleV2Samples = 0;
let architectureConfigurationSamples = 0;
const architectureConfigurationRequests = new Map(samples.filter(s => s.schema === "architecture-configuration-request" && s.valid)
  .map(s => [s.name.slice(0, -"-request".length), s.payload]));
const architectureConfigurationSaved = new Map(samples.filter(s => s.valid && s.name.startsWith("architecture-config-saved-")
  && /^assessment-response(?:\.v[0-9]+)?$/.test(s.schema)).map(s => [s.name.slice("architecture-config-saved-".length), s.payload]));
const architectureConfigurationPreflights = new Map(samples.filter(s => s.valid && s.schema === "architecture-pattern-preflight"
  && s.name.startsWith("architecture-config-preflight-")).map(s => [s.name.slice("architecture-config-preflight-".length), s.payload]));
function validateArchitectureConfigurationSample(payload, name) {
  const input = architectureConfigurationRequests.get(name);
  const binding = name === "architecture-config-empty" ? "empty" : name === "architecture-config-v6" ? "v6"
    : name.slice("architecture-config-".length).match(/^(SELECTED|NOT_SELECTED|UNKNOWN)-(BFF_SESSION|SERVER_SIDE_SESSION|SPA_CODE_PKCE|NATIVE_CODE_PKCE|M2M_CLIENT_CREDENTIALS)-/)
      ?.slice(1).join("-");
  assert.ok(binding, `${name}: original request binding must be explicit`);
  const saved = architectureConfigurationSaved.get(binding);
  validateArchitectureConfiguration(payload, input, saved, architectureConfigurationPreflights.get(binding));
  const consumerBinding = { workspaceId: saved.workspaceId, assessmentId: saved.id, input,
    context: { clients: saved.profile.application.clients, browserTokenExposureMinimization: saved.profile.security.browserTokenExposureMinimization } };
  const consumer = architectureConfigurationFromCore(payload, consumerBinding);
  assert.deepEqual(consumer.analysis, payload.analysis, "Actual Core settings must pass the strict personal BFF consumer too.");
  return consumerBinding;
}
const lifecycleV2Requests = new Map(samples.filter(s => s.schema === "provisioning-lifecycle-request.v2" && s.valid)
  .map(s => [s.name.slice(0, -"-request".length), s.payload]));
const lifecycleV2Saved = new Map(samples.filter(s => s.valid && /^lifecycle-v2-(?:saved-|v6-saved$)/.test(s.name)
  && /^assessment-response(?:\.v[0-9]+)?$/.test(s.schema)).map(s => [s.name, s.payload]));
const lifecycleRequests = new Map(samples.filter(s => s.schema === "provisioning-lifecycle-request" && s.valid)
  .map(s => [s.name.slice(0, -"-request".length), s.payload]));
const savedProvisioning = new Map(samples.filter(s => /^assessment-response(?:\.v[0-9]+)?$/.test(s.schema) && s.valid)
  .map(s => [JSON.stringify([s.payload.workspaceId, s.payload.id, s.payload.version]), s.payload.profile.provisioning]));
const lifecycleCommon = ["TENANT_AND_SUBJECT_CORRELATION", "ATTRIBUTE_OWNERSHIP_AND_MAPPING", "OFFBOARDING_AND_ACCESS_REVOCATION", "FAILURE_RECOVERY_AND_RECONCILIATION"];
const lifecycleConditions = { SCIM_PUSH: [...lifecycleCommon, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS"],
  JIT_LOGIN: [...lifecycleCommon, "JIT_TRUSTED_LOGIN_AND_LINKING"],
  SCIM_AND_JIT: [...lifecycleCommon, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS", "JIT_TRUSTED_LOGIN_AND_LINKING", "SCIM_JIT_COLLISION_POLICY"] };

function validateLifecycle(payload, name) {
  const input = lifecycleRequests.get(name), analysis = payload.analysis;
  assert.ok(input, `${name}: exact original preview request is required`);
  assert.equal(payload.assessmentVersion, input.expectedVersion);
  assert.equal(analysis.patternId, input.patternId); assert.deepEqual(analysis.declarations, input.declarations);
  const requirements = savedProvisioning.get(JSON.stringify([payload.workspaceId, payload.assessmentId, payload.assessmentVersion]));
  assert.ok(requirements, `${name}: exact stored workspace/assessment/version is required`);
  assert.deepEqual(analysis.requirements, requirements, "Temporary declarations cannot replace saved criticalities.");
  const consumer = lifecyclePreviewFromCore(payload, { workspaceId: payload.workspaceId, assessmentId: payload.assessmentId, input, requirements });
  assert.deepEqual(consumer.analysis, analysis, "Actual Core lifecycle responses must also pass the independent personal BFF consumer.");
  const satisfies = ["REQUIRED_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT", "DECLARED_CONDITION_SATISFIED"];
  const violates = ["REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED", "DECLARED_CONDITION_NOT_SATISFIED"];
  const outcome = reason => satisfies.includes(reason) ? "CONDITIONALLY_SATISFIED" : violates.includes(reason) ? "CONDITIONALLY_NOT_SATISFIED"
    : ["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED"].includes(reason) ? "NOT_APPLIED" : "UNKNOWN";
  const checks = Object.entries(requirements).map(([key, criticality]) => {
    const planned = key === "scim" ? input.patternId !== "JIT_LOGIN" : input.patternId !== "SCIM_PUSH";
    const reasonCode = criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT"
      : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED" : key === "groupSynchronization"
        ? criticality === "REQUIRED" ? "GROUP_LIFECYCLE_UNASSESSED" : "GROUP_PROHIBITION_UNASSESSED"
        : criticality === "REQUIRED" ? planned ? "REQUIRED_MECHANISM_PLANNED" : "REQUIRED_MECHANISM_ABSENT"
          : planned ? "FORBIDDEN_MECHANISM_PLANNED" : "FORBIDDEN_MECHANISM_ABSENT";
    return { profilePath: `provisioning.${key}`, criticality, reasonCode, outcome: outcome(reasonCode) };
  });
  const orderedChecks = ["scim", "justInTimeProvisioning", "groupSynchronization"].map(key => checks.find(c => c.profilePath === `provisioning.${key}`));
  assert.deepEqual(analysis.requirementChecks, orderedChecks, "Exact saved hard-requirement checks; no JIT-for-SCIM substitution or group promotion.");
  const conditionChecks = lifecycleConditions[input.patternId].map(conditionId => {
    const declared = input.declarations[conditionId] ?? "UNKNOWN";
    const reasonCode = declared === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : declared === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN";
    return { conditionId, reasonCode, outcome: outcome(reasonCode) };
  });
  assert.deepEqual(analysis.conditionChecks, conditionChecks, "Exact scoped declaration inventory, including omissions.");
  const all = [...orderedChecks, ...conditionChecks].map(c => c.outcome);
  assert.equal(analysis.status, all.includes("CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" : all.includes("UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES");
  assert.deepEqual(payload.patterns.map(p => p.patternId), Object.keys(lifecycleConditions));
  for (const pattern of payload.patterns) {
    assert.deepEqual(pattern.conditions, lifecycleConditions[pattern.patternId]);
    assert.equal(pattern.scimPlanned, pattern.patternId !== "JIT_LOGIN"); assert.equal(pattern.jitPlanned, pattern.patternId !== "SCIM_PUSH");
  }
  assert.deepEqual(payload.conditionDefinitions.map(c => c.conditionId), lifecycleConditions.SCIM_AND_JIT);
}

const lifecycleV2Common = ["TENANT_AND_SUBJECT_CORRELATION", "ATTRIBUTE_OWNERSHIP_AND_MAPPING", "ACCOUNT_DISABLE_AND_LOGIN_BLOCK",
  "APPLICATION_SESSION_INVALIDATION", "TOKEN_REVOCATION_OR_BOUNDED_EXPIRY", "FAILURE_RECOVERY_AND_RECONCILIATION"];
const lifecycleV2Patterns = { SCIM_PUSH: [...lifecycleV2Common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS"],
  JIT_LOGIN: [...lifecycleV2Common, "JIT_TRUSTED_LOGIN_AND_LINKING"],
  SCIM_AND_JIT: [...lifecycleV2Common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS", "JIT_TRUSTED_LOGIN_AND_LINKING", "SCIM_JIT_COLLISION_POLICY"] };
const lifecycleV2GroupCommon = ["GROUP_SOURCE_AND_MEMBERSHIP_MAPPING", "GROUP_CHANGE_DELIVERY_AND_RECONCILIATION", "GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT", "GROUP_REMOVAL_AND_ACCESS_RECHECK"];
const lifecycleV2Groups = { UNKNOWN: [], NONE: [], SCIM_GROUPS: [...lifecycleV2GroupCommon, "SCIM_GROUP_OPERATIONS"],
  APPLICATION_BRIDGE: [...lifecycleV2GroupCommon, "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY"] };
function validateLifecycleV2(payload, name) {
  const input = lifecycleV2Requests.get(name), a = payload.analysis;
  assert.ok(input, `${name}: exact V2 request is required`);
  const saved = lifecycleV2Saved.get(name === "lifecycle-v2-v6-current" ? "lifecycle-v2-v6-saved" : `lifecycle-v2-saved-${name.split("-")[2]}`);
  assert.ok(saved, `${name}: original HTTP path must bind the exact saved assessment, not a response-selected identity`);
  assert.equal(payload.workspaceId, saved.workspaceId); assert.equal(payload.assessmentId, saved.id);
  assert.equal(payload.assessmentVersion, saved.version);
  assert.equal(payload.assessmentVersion, input.expectedVersion);
  assert.equal(a.patternId, input.patternId); assert.equal(a.groupStrategy, input.groupStrategy); assert.deepEqual(a.declarations, input.declarations);
  const requirements = savedProvisioning.get(JSON.stringify([payload.workspaceId, payload.assessmentId, payload.assessmentVersion]));
  assert.ok(requirements, `${name}: V2 requires an exact saved workspace/assessment/version`);
  assert.deepEqual(a.requirements, requirements);
  const consumer = lifecycleV2PreviewFromCore(payload, { workspaceId: saved.workspaceId, assessmentId: saved.id, input, requirements });
  assert.deepEqual(consumer.analysis, a, "Actual Core v2 responses must pass the personal BFF consumer, not only the schema.");
  const satisfied = ["REQUIRED_MECHANISM_PLANNED", "FORBIDDEN_MECHANISM_ABSENT", "DECLARED_CONDITION_SATISFIED", "GROUP_TRANSPORT_PLANNED"];
  const failed = ["REQUIRED_MECHANISM_ABSENT", "FORBIDDEN_MECHANISM_PLANNED", "DECLARED_CONDITION_NOT_SATISFIED", "SCIM_GROUPS_REQUIRE_SCIM_PATTERN"];
  const outcome = reason => satisfied.includes(reason) ? "CONDITIONALLY_SATISFIED" : failed.includes(reason) ? "CONDITIONALLY_NOT_SATISFIED"
    : ["PREFERENCE_NOT_SCORED", "NO_REQUIREMENT", "NO_GROUP_TRANSPORT_PLANNED"].includes(reason) ? "NOT_APPLIED" : "UNKNOWN";
  const checks = ["scim", "justInTimeProvisioning", "groupSynchronization"].map(key => {
    const criticality = requirements[key], planned = key === "scim" ? input.patternId !== "JIT_LOGIN" : key === "justInTimeProvisioning"
      ? input.patternId !== "SCIM_PUSH" : input.groupStrategy === "UNKNOWN" ? null : input.groupStrategy !== "NONE";
    const reasonCode = criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED"
      : criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT" : planned === null ? "GROUP_STRATEGY_UNKNOWN"
        : criticality === "REQUIRED" ? planned ? "REQUIRED_MECHANISM_PLANNED" : "REQUIRED_MECHANISM_ABSENT"
          : planned ? "FORBIDDEN_MECHANISM_PLANNED" : "FORBIDDEN_MECHANISM_ABSENT";
    return { profilePath: `provisioning.${key}`, criticality, outcome: outcome(reasonCode), reasonCode };
  });
  assert.deepEqual(a.requirementChecks, checks, "V2 cannot replace saved criticalities or use a group bridge as SCIM.");
  const reasonCode = input.groupStrategy === "UNKNOWN" ? "GROUP_STRATEGY_UNKNOWN" : input.groupStrategy === "NONE" ? "NO_GROUP_TRANSPORT_PLANNED"
    : input.groupStrategy === "SCIM_GROUPS" && input.patternId === "JIT_LOGIN" ? "SCIM_GROUPS_REQUIRE_SCIM_PATTERN" : "GROUP_TRANSPORT_PLANNED";
  const designChecks = [{ boundary: "groupTransport", outcome: outcome(reasonCode), reasonCode }];
  assert.deepEqual(a.designChecks, designChecks);
  const conditionChecks = [...lifecycleV2Patterns[input.patternId], ...lifecycleV2Groups[input.groupStrategy]].map(conditionId => {
    const value = input.declarations[conditionId] ?? "UNKNOWN";
    const reasonCode = value === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : value === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN";
    return { conditionId, outcome: outcome(reasonCode), reasonCode };
  });
  assert.deepEqual(a.conditionChecks, conditionChecks, "V2 complete scoped groups, application-session and token conditions.");
  const all = [...checks, ...designChecks, ...conditionChecks].map(c => c.outcome);
  assert.equal(a.status, all.includes("CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" : all.includes("UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES");
  assert.deepEqual(payload.groupStrategies.map(g => g.groupStrategy), Object.keys(lifecycleV2Groups));
  for (const group of payload.groupStrategies) assert.deepEqual(group.conditions, lifecycleV2Groups[group.groupStrategy]);
  assert.deepEqual(payload.conditionDefinitions.map(c => c.conditionId), [...lifecycleV2Patterns.SCIM_AND_JIT, ...lifecycleV2GroupCommon,
    "SCIM_GROUP_OPERATIONS", "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY"]);
}
const constraintKey = value => JSON.stringify([value.workspaceId, value.assessmentId, value.assessmentVersion,
  value.catalogVersion, value.catalogKind, value.evaluatedAt]);
const legacyConstraints = new Map(samples.filter(s => s.schema === "hard-constraint-preflight" && s.valid)
  .map(s => [constraintKey(s.payload), s.payload]));

function validateCombinedConstraints(payload) {
  const comparison = payload.comparison ?? payload;
  const legacy = legacyConstraints.get(constraintKey(comparison));
  assert.ok(legacy, "Combined constraints require an exact legacy HTTP baseline, not a caller-selected verdict.");
  const rawAudit = comparison.auditability;
  const audit = auditabilityPreviewFromCore(rawAudit, { workspaceId: comparison.workspaceId,
    assessmentId: comparison.assessmentId, expectedVersion: comparison.assessmentVersion,
    values: { criticality: rawAudit.criticality, selectedCriteria: rawAudit.requirements.selectedCriteria,
      minimumRetentionDays: rawAudit.requirements.minimumRetentionDays } });
  assert.equal(audit.baseCatalogVersion, comparison.catalogVersion, "Combined base catalog binding");
  assert.equal(audit.evaluatedAt, comparison.evaluatedAt, "Combined evaluation instant binding");
  assert.equal(audit.candidates.length, comparison.candidates.length, "Combined option inventory");
  const ids = new Set();
  for (const [index, candidate] of comparison.candidates.entries()) {
    const base = legacy.candidates[index];
    assert.ok(base, "Preserve the legacy Core option order.");
    for (const key of ["optionId", "displayName", "plan", "region"]) assert.equal(candidate[key], base[key]);
    assert.ok(!ids.has(candidate.optionId), "Unique combined option IDs"); ids.add(candidate.optionId);
    const bound = audit.candidates.filter(c => c.scope.optionId === candidate.optionId);
    assert.equal(bound.length, 1, "One unambiguous audit configuration per base option");
    const scoped = bound[0];
    assert.equal(scoped.scope.plan, candidate.plan); assert.equal(scoped.scope.region, candidate.region);
    assert.equal(scoped.displayName, candidate.displayName);
    const expectedExcluded = [...base.exclusionReasons];
    const expectedGaps = base.informationGaps.filter(f => !(scoped.checks.some(c => c.outcome === "PASS") &&
      f.dimension === "COVERAGE" && f.reasonCode === "NO_AFFIRMATIVE_CHECKS"));
    for (const check of scoped.checks) {
      if (!["FAIL", "UNKNOWN"].includes(check.outcome)) continue;
      const expected = check.outcome === "FAIL" ? expectedExcluded : expectedGaps;
      const actual = (check.outcome === "FAIL" ? candidate.exclusionReasons : candidate.informationGaps)[expected.length];
      assert.ok(actual, "Each audit failure/unknown must produce a finding.");
      assert.equal(actual.dimension, "AUDITABILITY"); assert.equal(actual.reasonCode, check.reasonCode);
      assert.equal(actual.profilePath, ["REQUIREMENT_UNKNOWN", "AUDIT_INTENT_UNCLEAR"].includes(check.reasonCode)
        ? "security.auditability" : "security.auditabilityRequirements");
      assert.ok(actual.explanation.startsWith(`${check.criterion}: `), "Name the exact audit criterion.");
      if (check.reasonCode === "RETENTION_BELOW_MINIMUM") {
        assert.ok(actual.explanation.includes(`retention of ${check.documentedMinimumRetentionDays} days`));
        assert.ok(actual.explanation.includes(`requested ${audit.values.minimumRetentionDays} days`));
      }
      expected.push(actual);
    }
    assert.deepEqual(candidate.exclusionReasons, expectedExcluded, "Do not hide failures or invent exclusions.");
    assert.deepEqual(candidate.informationGaps, expectedGaps, "Do not hide unknown evidence or invent gaps.");
    const verdict = expectedExcluded.length ? "EXCLUDED" : expectedGaps.length ? "UNRESOLVED" : "PASSES_CHECKED_REQUIREMENTS";
    assert.equal(candidate.hardVerdict ?? candidate.verdict, verdict, "Hard failure precedence and auditability aggregate");
  }
  function scoresFor(weights) {
    return comparison.candidates.map(candidate => {
      const status = candidate.hardVerdict === "EXCLUDED" ? "EXCLUDED" : candidate.hardVerdict === "UNRESOLVED"
        ? "UNRESOLVED_HARD_CONSTRAINTS" : candidate.capabilityPreferences.some(p => p.outcome === "UNKNOWN")
          ? "UNKNOWN_PREFERENCE_EVIDENCE" : "SCORED";
      const contributions = status !== "SCORED" ? [] : Object.entries(weights).map(([capability, weight]) => {
        const preference = candidate.capabilityPreferences.find(p => p.capability === capability);
        assert.ok(preference, "Weights bind explicit preferences.");
        return { capability, weight, outcome: preference.outcome, earnedPoints: preference.outcome === "AVAILABLE" ? weight : 0 };
      });
      return { optionId: candidate.optionId, status, score: status === "SCORED" ? contributions.reduce((n, c) => n + c.earnedPoints, 0) : null, contributions };
    });
  }
  if (payload.scores) assert.deepEqual(payload.scores, scoresFor(payload.weights), "Auditability exclusions/unknowns withhold scores.");
  if (payload.deltas) {
    const baseline = scoresFor(payload.baseline.weights), alternative = scoresFor(payload.alternative.weights);
    assert.deepEqual(payload.baseline.scores, baseline); assert.deepEqual(payload.alternative.scores, alternative);
    const deltas = baseline.map((before, index) => {
      const after = alternative[index];
      return { optionId: before.optionId, status: before.status, scoreDelta: before.status === "SCORED" ? after.score - before.score : null,
        capabilityDeltas: before.contributions.map((c, i) => ({ capability: c.capability, baselineWeight: c.weight,
          alternativeWeight: after.contributions[i].weight, outcome: c.outcome, pointChange: after.contributions[i].earnedPoints - c.earnedPoints })) };
    });
    assert.deepEqual(payload.deltas, deltas, "No sensitivity delta for excluded or unresolved options.");
  }
}
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
function validateAuditabilityImpact(payload, name) {
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
}
function validateAuditabilityCoverage(payload, name) {
  const structural = payload.profileCoverage, impact = payload.candidateImpact;
  validateAuditabilityImpact(impact, name);
  assert.equal(structural.status, "INCOMPLETE");
  assert.equal(payload.evaluatedAt, structural.evaluatedAt); assert.equal(payload.evaluatedAt, impact.evaluatedAt);
  assert.equal(structural.scenarioSetSha256, impact.scenarioSetSha256);
  assert.equal(structural.auditabilityRegression.evaluatedAt, payload.evaluatedAt);
  assert.equal(structural.dimensions.length, 136);
  assert.equal(structural.candidateAuditabilityChangesEvaluated, false, "Historical structural semantics stay unchanged.");
  const gaps = impactDefinitions.flatMap(d => [
    { scenarioId: d.scenarioId, profilePath: "security.browserTokenExposureMinimization", boundary: "ARCHITECTURE_CONFIGURATION" },
    { scenarioId: d.scenarioId, profilePath: "provisioning", boundary: "PROVISIONING_LIFECYCLE" },
    { scenarioId: d.scenarioId, profilePath: "security.authenticationControls", boundary: "CONFIGURED_AUTHENTICATION_FLOW" },
    ...impact.deferredBoundaries.map(boundary => ({ scenarioId: d.scenarioId, profilePath: "security.auditability", boundary })),
  ]);
  assert.deepEqual(ordered(structural.verificationGaps), ordered(gaps), "Do not hide verification boundaries.");
  const expected = impact.scenarios.flatMap(scenario => structural.dimensions.filter(d => d.scenarioId === scenario.scenarioId && d.boundary === "AUDITABILITY").map(d => {
    const checks = scenario.checks.filter(c => d.evidenceCriteria.includes(c.criterion));
    const selected = scenario.checks.filter(c => scenario.requirements.selectedCriteria.includes(c.criterion) &&
      (!d.profilePath.endsWith("minimumRetentionDays") || c.criterion === "AUDIT_LOG_RETENTION")).map(c => c.criterion);
    assert.deepEqual(d.evidenceCriteria, selected, "Candidate inputs bind exact structural dependencies.");
    const outcomes = side => ({ wouldSatisfy: checks.filter(c => c[side].conditionalOutcome === "WOULD_SATISFY").length,
      wouldViolate: checks.filter(c => c[side].conditionalOutcome === "WOULD_VIOLATE").length,
      indeterminate: checks.filter(c => c[side].conditionalOutcome === "INDETERMINATE").length });
    return { scenarioId: scenario.scenarioId, profileSha256: scenario.profileSha256, optionScope: scenario.optionScope, profilePath: d.profilePath,
      evidenceCriteria: selected, before: outcomes("before"), after: outcomes("after"), changedFacts: checks.filter(c => c.factChanged).length,
      changedChecks: checks.filter(c => c.conditionalResultChanged).length,
      state: selected.length ? "CONDITIONAL_CANDIDATE_CHECKS_PRESENT" : "SCOPE_GUARD_ONLY" };
  }));
  assert.equal(expected.length, impact.checkedCases * 3);
  assert.deepEqual(payload.dimensions, expected, "Exact candidate scope/input matrix, selected counts and change attribution.");
  assert.equal(payload.checkedAuditabilityDimensions, expected.length);
  assert.deepEqual(payload.structuralOnlyDimensions, structural.dimensions.filter(d => d.boundary !== "AUDITABILITY"));
  assert.equal(payload.structuralOnlyDimensions.length, 124);
  const manifest = digest([payload.policyVersion, structural.manifestSha256, impact.policyVersion, impact.profileSchemaSha256,
    ["security.auditability", "security.auditabilityRequirements.selectedCriteria", "security.auditabilityRequirements.minimumRetentionDays"]]);
  assert.equal(payload.manifestSha256, manifest);
  assert.equal(payload.profileCoverageSha256, digest(structural)); assert.equal(payload.candidateImpactSha256, impact.analysisSha256);
  assert.equal(payload.analysisSha256, digest([payload.policyVersion, payload.evaluatedAt, manifest,
    digest(structural), impact.analysisSha256, payload.dimensions, payload.structuralOnlyDimensions]));
}
for (const { name, schema, valid, payload } of samples) {
  assert.equal(typeof valid, "boolean", `${name}: expected validity is required`);
  const versionedName = /\.v[0-9]+$/.test(schema) ? schema : `${schema}.v1`;
  const validate = ajv.getSchema(`https://authweave.dev/contracts/${versionedName}.schema.json`);
  assert.ok(validate, `${name}: unknown schema ${schema}`);
  assert.equal(validate(payload), valid,
    `${name} (${schema}): ${ajv.errorsText(validate.errors, { separator: "\n" })}`);
  covered.add(`${schema}:${valid}`);
  if (schema === "catalog-architecture-configuration-regression-check" && valid) {
    validateConfigurationRegression(payload);
    for (const mutate of [r => { r.outcomes.conditionallySatisfied--; r.outcomes.conditionallyNotSatisfied++; },
      r => { r.results.conditionallyMatches--; r.results.needsInformation++; }, r => { r.selectedCases--; r.unknownClientCases++; },
      r => { r.reasons[0].checks--; r.reasons[1].checks++; }, r => r.savedInputNeedsInformation--,
      r => r.scenarioSetSha256 = "0".repeat(64), r => r.definitionsSha256 = "0".repeat(64), r => r.analysisSha256 = "0".repeat(64), r => r.evaluatedAt = "2026-09-12T12:00:01Z"]) {
      const forged = structuredClone(payload); mutate(forged); assert.equal(validate(forged), true);
      assert.throws(() => validateConfigurationRegression(forged), undefined, "Shape-valid balanced counts or binding substitutions cannot pass independent fixed-clock replay.");
    }
  }
  if (schema === "architecture-configuration-preview" && valid) {
    const binding = validateArchitectureConfigurationSample(payload, name);
    const forgeries = [value => { value.preflight.workspaceId = "00000000-0000-4000-8000-000000000001"; },
      value => { value.preflight.assessmentId = "00000000-0000-4000-8000-000000000002"; },
      value => { value.preflight.assessmentVersion++; }, value => { value.preflight.evaluatedAt = "2026-10-05T00:00:00Z"; },
      value => { value.analysis.settings.OAUTH_FLOW = value.analysis.settings.OAUTH_FLOW === "UNKNOWN" ? "IMPLICIT" : "UNKNOWN"; },
      value => { value.analysis = expectedAnalysis(value.analysis.patternId, value.analysis.clientScope === "SELECTED" ? "UNKNOWN" : "SELECTED", value.analysis.settings); },
      value => { value.settingDefinitions[0].description = "Synthetic untrusted replacement."; },
      value => { value.settingDefinitions[0].references = ["https://synthetic.example.test"]; }];
    for (const mutate of forgeries) {
      const forged = structuredClone(payload); mutate(forged);
      assert.equal(validate(forged), true, "A substituted binding or setting can retain valid JSON shape.");
      assert.throws(() => validateArchitectureConfigurationSample(forged, name), undefined, "Independent replay rejects shape-valid settings or context substitutions.");
      // The BFF checks timestamp format, not equality with an earlier independently fetched preflight.
      if (forged.preflight.evaluatedAt === payload.preflight.evaluatedAt)
        assert.throws(() => architectureConfigurationFromCore(forged, binding), undefined, "The actual BFF consumer also rejects shape-valid binding and settings substitutions.");
    }
    architectureConfigurationSamples++;
  }
  if (schema === "provisioning-lifecycle-preview.v2" && valid) {
    validateLifecycleV2(payload, name);
    const forgeries = [value => { value.workspaceId = "00000000-0000-4000-8000-000000000001"; }, value => { value.assessmentId = "00000000-0000-4000-8000-000000000002"; },
      value => { value.assessmentVersion++; }, value => { value.analysis.requirements.scim = value.analysis.requirements.scim === "REQUIRED" ? "NOT_REQUIRED" : "REQUIRED"; },
      value => { value.analysis.declarations.TENANT_AND_SUBJECT_CORRELATION = value.analysis.declarations.TENANT_AND_SUBJECT_CORRELATION === "SATISFIED" ? "UNKNOWN" : "SATISFIED"; }];
    for (const mutate of forgeries) {
      const forged = structuredClone(payload); mutate(forged);
      assert.equal(validate(forged), true, "Foreign version, requirements or declarations can retain valid wire shape.");
      assert.throws(() => validateLifecycleV2(forged, name), undefined, "V2 binds the exact saved profile and original request before replay.");
    }
    lifecycleV2Samples++;
  }
  if (schema === "provisioning-lifecycle-preview" && valid) {
    validateLifecycle(payload, name);
    const forged = structuredClone(payload); forged.assessmentVersion++;
    assert.equal(validate(forged), true, "A foreign version can retain valid shape.");
    assert.throws(() => validateLifecycle(forged, name), undefined, "Reject a foreign assessment version.");
    const altered = structuredClone(payload);
    altered.analysis.requirements.scim = altered.analysis.requirements.scim === "REQUIRED" ? "NOT_REQUIRED" : "REQUIRED";
    assert.equal(validate(altered), true, "Altered saved criticalities can retain valid shape.");
    assert.throws(() => validateLifecycle(altered, name), undefined, "Caller-selected criticalities cannot replace the saved profile.");
    const rewritten = structuredClone(payload);
    rewritten.analysis.declarations.TENANT_AND_SUBJECT_CORRELATION = rewritten.analysis.declarations.TENANT_AND_SUBJECT_CORRELATION === "SATISFIED" ? "UNKNOWN" : "SATISFIED";
    assert.equal(validate(rewritten), true, "Altered declared conditions can retain valid shape.");
    assert.throws(() => validateLifecycle(rewritten, name), undefined, "Bind exact request declarations, not substituted answers.");
    lifecycleSamples++;
  }
  if (["hard-constraint-preflight.v2", "synthetic-comparison.v2", "weighted-comparison-preview.v2", "weight-sensitivity-preview.v2"].includes(schema) && valid) {
    validateCombinedConstraints(payload);
    // Schema-valid but semantically foreign evidence must not pass the independent binding guard.
    const forged = structuredClone(payload), comparison = forged.comparison ?? forged;
    comparison.auditability.assessmentVersion++;
    assert.throws(() => validateCombinedConstraints(forged), undefined, `${name}: reject foreign audit version`);
    combinedConstraintSamples++;
    if (schema !== "hard-constraint-preflight.v2") {
      const combined = payload.comparison ?? payload;
      const binding = { workspaceId: combined.workspaceId, assessmentId: combined.assessmentId,
        expectedVersion: combined.assessmentVersion, values: { criticality: combined.auditability.criticality,
          selectedCriteria: combined.auditability.requirements.selectedCriteria,
          minimumRetentionDays: combined.auditability.requirements.minimumRetentionDays } };
      const projected = comparisonFromCore(combined, binding);
      assert.deepEqual(projected.candidates.map(c => c.hardVerdict), combined.candidates.map(c => c.hardVerdict));
      assert.throws(() => comparisonFromCore((forged.comparison ?? forged), binding), undefined, `${name}: BFF rejects foreign audit version`);
      combinedConsumerSamples++;
    }
  }
  if (schema === "catalog-auditability-impact" && valid) {
    validateAuditabilityImpact(payload, name);
    auditabilityImpactSamples++;
  }
  if (schema === "catalog-auditability-impact-coverage" && valid) {
    validateAuditabilityCoverage(payload, name);
    const forged = structuredClone(payload);
    forged.dimensions[0].after.wouldSatisfy++; forged.dimensions[0].after.indeterminate--;
    forged.analysisSha256 = digest([forged.policyVersion, forged.evaluatedAt, forged.manifestSha256,
      forged.profileCoverageSha256, forged.candidateImpactSha256, forged.dimensions, forged.structuralOnlyDimensions]);
    assert.equal(validate(forged), true, `${name}: altered counts retain valid shape and a recomputed digest`);
    assert.throws(() => validateAuditabilityCoverage(forged, name), undefined, "Correct hashes cannot disguise wrong candidate counts.");
    auditabilityCoverageSamples++;
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
  "provisioning-lifecycle-request:true", "provisioning-lifecycle-request:false", "provisioning-lifecycle-preview:true", "provisioning-lifecycle-preview:false",
  "provisioning-lifecycle-request.v2:true", "provisioning-lifecycle-request.v2:false", "provisioning-lifecycle-preview.v2:true", "provisioning-lifecycle-preview.v2:false",
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
  "catalog-auditability-impact-coverage:true", "catalog-auditability-impact-coverage:false",
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
  "architecture-configuration-request:true", "architecture-configuration-request:false",
  "architecture-configuration-preview:true", "architecture-configuration-preview:false",
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
  "hard-constraint-preflight.v2:true", "hard-constraint-preflight.v2:false",
  "synthetic-comparison.v2:true", "synthetic-comparison.v2:false",
  "weighted-comparison-preview.v2:true", "weight-sensitivity-preview.v2:true",
  "weighted-comparison-request:true", "weighted-comparison-preview:true",
  "weight-sensitivity-request:true", "weight-sensitivity-preview:true",
  "update-assessment-profile-request.v5:true", "update-assessment-profile-request.v5:false",
  "usage-planning-preflight:true", "usage-planning-preflight:false",
  "auditability-capability-preflight:true", "auditability-capability-preflight:false",
  "catalog-auditability-regression-check:true", "catalog-auditability-regression-check:false",
  "catalog-architecture-configuration-regression-check:true", "catalog-architecture-configuration-regression-check:false",
  "catalog-profile-impact-coverage:true", "catalog-profile-impact-coverage:false",
  "update-assessment-profile-request.v2:true", "update-assessment-profile-request.v2:false",
  "update-assessment-profile-request:true", "update-assessment-profile-request:false"]) {
  assert.ok(covered.has(required), `Missing HTTP contract coverage: ${required}`);
}
console.log(`Validated ${samples.length} actual HTTP request/response samples against JSON Schema.`);
assert.ok(architectureConfigurationSamples > 0, "Actual architecture settings must reach independent request, saved-scope and conditional outcome checks.");
console.log(`Verified ${architectureConfigurationSamples} proposed architecture configurations with independent version, scope, settings, metadata and preflight guards.`);
assert.ok(combinedConstraintSamples > 0, "Combined v6 HTTP responses must reach independent auditability/aggregate/scoring checks.");
console.log(`Verified ${combinedConstraintSamples} combined constraint responses with independent auditability, binding and score guards.`);
assert.ok(combinedConsumerSamples > 0, "Actual v6 comparison/weighted/sensitivity HTTP responses must reach the BFF consumer guard.");
console.log(`Verified ${combinedConsumerSamples} combined comparison HTTP responses through the strict BFF consumer guard.`);
assert.ok(auditabilityConsumerSamples > 0, "Actual auditability HTTP samples must reach the strict BFF consumer.");
console.log(`Validated ${auditabilityConsumerSamples} actual auditability HTTP responses with the BFF evidence-policy guard.`);
assert.ok(auditabilityDraftSamples > 0, "Actual auditability draft responses must reach independent digest checks.");
console.log(`Verified ${auditabilityDraftSamples} actual auditability draft reports with independent target binding checks.`);
assert.ok(auditabilityReviewSamples > 0, "Actual immutable auditability reviews must reach independent request/target/receipt checks.");
console.log(`Verified ${auditabilityReviewSamples} actual auditability review receipts with independent request and target binding checks.`);
assert.ok(auditabilityImpactSamples > 0, "Actual conditional auditability comparisons must reach independent source/scenario/outcome checks.");
console.log(`Verified ${auditabilityImpactSamples} conditional auditability impact reports with independent scenario, source, outcome and hash checks.`);
assert.ok(auditabilityCoverageSamples > 0, "Actual candidate coverage reports must reach independent binding, dependency, matrix and gap checks.");
console.log(`Verified ${auditabilityCoverageSamples} candidate auditability coverage reports with independent impact, selected-input and gap guards.`);
assert.ok(lifecycleSamples > 0, "Actual provisioning previews must reach independent saved-requirement, version and condition guards.");
console.log(`Verified ${lifecycleSamples} provisioning lifecycle previews with independent saved-profile, scoped-condition and hard-requirement guards.`);
assert.ok(lifecycleV2Samples > 0, "Actual V2 group and offboarding previews must reach independent saved-profile and design replay guards.");
console.log(`Verified ${lifecycleV2Samples} V2 group-delivery and offboarding design previews with independent saved-profile, scoped-condition and cross-transport guards.`);
