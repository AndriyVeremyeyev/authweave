import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { suite, profileFor, hash } from "../tests/helpers/decision-result-spec.mjs";
import { assertCandidateCalculation } from "../tests/helpers/assessment-decision-result-spec.mjs";

// Java exports actual calculations. This offline independent checker never evaluates a vendor,
// manufactures a decision from expectations, fetches a source, or grants review/publication authority.
const inputSpec = JSON.parse(await readFile(new URL("../decision-core/inputs.v1.json", import.meta.url), "utf8"));
const samples = JSON.parse(await readFile(process.argv[2], "utf8"));
assert.equal(inputSpec.caseSetVersion, suite.caseSetVersion);
assert.equal(samples.length, 18); assert.equal(new Set(samples.map(s => s.id)).size, 18);
assert.deepEqual(samples.map(s => s.id).sort(), suite.cases.map(c => c.id).sort());
assert.deepEqual(inputSpec.cases.map(c => c.id).sort(), suite.cases.map(c => c.id).sort());

function verify(sample, expected = suite.cases.find(c => c.id === sample.id), weights = expected.weights) {
  assert(expected); const { input: i, result: d } = sample;
  assert.deepEqual(i.profile, profileFor(expected));
  assert.equal(i.catalog.catalogVersion, suite.catalogVersion);
  const requested = { mode: Object.keys(weights).length ? "EXPLICIT" : "NONE", values: Object.keys(weights).map(capability => ({ capability, weight: weights[capability] })) };
  assert.deepEqual(i.weights.values.toSorted((a, b) => a.capability.localeCompare(b.capability)), requested.values.toSorted((a, b) => a.capability.localeCompare(b.capability)));
  assert.equal(i.weights.mode, requested.mode);
  const source = { catalog: i.catalog, assertions: i.assertions, auditability: i.auditability };
  assertCandidateCalculation(d, i.profile, i.weights, source, suite.evaluatedAt);
  assert.equal(i.assertions.candidateSha256, hash(i.catalog)); assert.equal(i.auditability.candidateSha256, hash(i.catalog));
  assert.equal(d.status, expected.expected.status);
  assert.deepEqual(d.candidates.map(c => c.hardChecks.hardVerdict), expected.expected.verdicts);
  assert.deepEqual(d.shortlist, expected.expected.shortlist);
  assert.deepEqual(d.rankGroups, expected.expected.ranks.map((optionIds, index) => ({ rank: index + 1, optionIds })));
  for (const candidate of d.candidates) {
    const { optionId } = candidate.hardChecks, bounds = expected.expected.bounds[optionId];
    assert.deepEqual(candidate.score === null ? null : [candidate.score.lowerBound, candidate.score.upperBound, candidate.score.unknownWeight], bounds ?? null);
    for (const contribution of candidate.score?.contributions ?? [])
      assert.equal(contribution.outcome, expected.preferences[optionId][contribution.capability]);
    for (const finding of candidate.hardChecks.findings) if (finding.evidence) assert(new URL(finding.evidence.sourceUrl).hostname.endsWith(".invalid"));
  }
  for (const [optionId, path, criticality, outcome, reason] of expected.kernelChecks ?? expected.checks) {
    const findings = d.candidates.find(c => c.hardChecks.optionId === optionId).hardChecks.findings;
    assert(findings.some(f => (path === "security.auditabilityRequirements.minimumRetentionDays"
      ? f.factPath === "auditabilitySupplement.AUDIT_LOG_RETENTION" : f.profilePath === path)
      && f.criticality === criticality && f.outcome === outcome && f.reasonCode === reason), `${sample.id}: missing actual reason ${path}/${reason}`);
  }
  const recommended = d.architecture.patterns.filter(p => p.choice.disposition === "RECOMMENDED");
  if (expected.expected.pattern === null) assert.equal(recommended.length, 0);
  else assert(recommended.some(p => p.choice.id === expected.expected.pattern));
  if (sample.laterCatalog) {
    assert.equal(sample.laterCatalog.catalogVersion, expected.laterCatalogVersion);
    assert.notEqual(hash(sample.laterCatalog), d.binding.inputs.hardChecks.catalogSha256);
    assert.equal(d.binding.inputs.hardChecks.catalogVersion, suite.catalogVersion);
  }
}

for (const sample of samples) {
  verify(sample);
  for (const change of [s => s.result.binding.inputs.hardChecks.profileSha256 = "0".repeat(64),
    s => s.result.binding.inputs.hardChecks.catalogVersion = "a-newer-catalog",
    s => s.result.binding.inputs.hardChecks.evaluatedAt = "2026-10-10T12:00:00Z",
    s => s.result.candidates[0].hardChecks.findings.find(f => f.evidence).evidence.claimSha256 = "0".repeat(64),
    s => s.result.sourceAuthorityVerified = true, s => s.result.publicationReady = true,
    s => s.result.shortlist.push("unrequested-option")]) {
    const forged = structuredClone(sample); change(forged); assert.throws(() => verify(forged));
  }
  if (sample.sensitivity) {
    const definition = suite.cases.find(c => c.id === sample.id), after = structuredClone(sample);
    const expected = structuredClone(definition); expected.weights = definition.sensitivity.weights;
    expected.expected.ranks = definition.sensitivity.ranks; expected.expected.bounds = definition.sensitivity.bounds;
    after.input.weights = sample.sensitivity.after.weights;
    after.result = sample.weightedResult;
    verify(after, expected, expected.weights);
    assert.deepEqual(sample.sensitivity.before.candidates, sample.result.candidates);
    assert.deepEqual(sample.sensitivity.before.binding.hardChecks, sample.sensitivity.after.binding.hardChecks);
    assert.deepEqual(sample.sensitivity.before.candidates.map(c => c.hardChecks), sample.sensitivity.after.candidates.map(c => c.hardChecks));
    assert.equal(sample.sensitivity.writesPerformed, false);
  }
}
const scoreProbes = inputSpec.cases.filter(c => c.rejectForgedScore); assert.equal(scoreProbes.length, 1);
const scored = samples.find(s => s.id === scoreProbes[0].id); assert(scored);
for (const change of [s => s.result.candidates[0].score.lowerBound++, s => s.result.candidates[0].score.contributions[0].earnedPoints++,
  s => s.result.rankGroups.reverse(), s => s.input.weights.values.reverse()]) {
  const forged = structuredClone(scored); change(forged); assert.throws(() => verify(forged));
}
const tied = samples.find(s => s.id === "normal-b2b-shared-tie"), forgedTie = structuredClone(tied);
forgedTie.result.rankGroups = [{ rank: 1, optionIds: ["alpha"] }, { rank: 2, optionIds: ["beta"] }];
assert.throws(() => verify(forgedTie));
console.log("Verified all 18 actual golden engine calculations: exact decisions/reasons/evidence, bound profile/catalog/weights/clock, no-preference/unknown ranges/shared ties, sensitivity and forged-output rejection. Fictional assertions are not source approval or owner acceptance.");
