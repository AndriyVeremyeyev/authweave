import assert from "node:assert/strict";
import { orderedHash as hash, componentVersions, decisionPolicy, decisionPolicySha256, profileSchemaSha256,
  canonicalization, verificationGaps } from "./publication-decision-coverage-spec.mjs";

// Independent artifact/binding invariants. Not DB authentication, an alternative Java engine or source truth.
export const resultPolicy = {
  evaluationProfileSchemaVersion: 6, profileSchemaSha256, decisionPolicySha256,
  profileProjectionVersion: "stored-profile-to-v6-unknown-projection-1",
  componentVersions: { ...componentVersions, bootstrapPublicationLoading: "published-bootstrap-loading-1", proposalPublicationLoading: "published-proposal-loading-1" },
};
const get = (value, path) => path.split(".").reduce((v, key) => v?.[key], value);
export function assertAssessmentResult(sample) {
  const { receipt, decisionInputs: source } = sample, body = receipt.result, request = body.request, d = body.decision;
  assert.equal(receipt.reference.resultId, request.resultId); assert.equal(receipt.reference.version, body.version);
  assert.equal(receipt.reference.resultSha256, hash(body)); assert.equal(receipt.historicalReplayVerified, true);
  assert.equal(body.scope, "PINNED_ASSESSMENT_DECISION_ADVICE"); assert.equal(body.schemaVersion, 1);
  assert.equal(body.resultVersion, "assessment-decision-result-1"); assert.equal(body.canonicalization, canonicalization);
  assert.equal(body.requestSha256, hash({ workspaceId: body.workspaceId, assessmentId: body.assessmentId, request }));
  assert.equal(body.profileSha256, hash(body.profile)); assert.equal(body.evaluationProfileSha256, hash(body.evaluationProfile));
  assert.deepEqual(body.policy, resultPolicy); assert.equal(body.policySha256, hash(resultPolicy));
  assert.equal(request.previousResult === null ? body.version === 1 : request.previousResult.version === body.version - 1, true);
  assert.equal(request.confirmation, body.version === 1 ? "RECORD_DECISION_RESULT" : "REEVALUATE_DECISION_RESULT");
  assert.deepEqual(body.catalog.reference, request.catalog); assert.equal(body.catalog.decisionInputsSha256, hash(source));
  assert.deepEqual(body.catalog.verificationGaps, verificationGaps);
  assert.equal(body.catalog.publicationPolicyVersion, body.catalog.loaderVersion === "published-bootstrap-loading-1" ? "catalog-bootstrap-publication-1" : "catalog-proposal-publication-1");
  assert.equal(body.historicalPublicationWorkflowVerified, true);
  for (const flag of ["externalSourceVerificationPerformed", "configurationVerified", "complianceVerified", "decisionApproved"]) assert.equal(body[flag], false);
  const elapsed = Date.parse(receipt.recordedAt) - Date.parse(body.evaluatedAt); assert(elapsed >= -30000 && elapsed <= 300000);
  assert.equal(d.scope, "UNVERIFIED_CANDIDATE_DECISION_CALCULATION"); assert.equal(d.kernelVersion, componentVersions.composition);
  assert.equal(d.policyVersion, decisionPolicy.policyVersion); assert.equal(d.binding.scoringVersion, componentVersions.scoring);
  assert.equal(d.binding.hardKernelVersion, componentVersions.hardChecks); assert.equal(d.binding.architectureVersion, componentVersions.architecture);
  assert.equal(d.binding.patternDefinitionsVersion, componentVersions.patterns); assert.equal(d.binding.prerequisiteVersion, componentVersions.prerequisites);
  assert.equal(d.binding.provisioningDefinitionsVersion, componentVersions.provisioning); assert.equal(d.binding.provisioningConditionsVersion, componentVersions.provisioningConditions);
  assert.equal(d.binding.auditabilityInputVersion, source.auditability === null ? null : componentVersions.auditability);
  const hard = d.binding.inputs.hardChecks;
  assert.equal(hard.profileSchemaVersion, 6); assert.equal(hard.profileSha256, hash(body.evaluationProfile));
  assert.equal(hard.catalogVersion, source.catalog.catalogVersion); assert.equal(hard.catalogSha256, hash(source.catalog));
  assert.equal(hard.sourceAssertionsSha256, hash(source.assertions)); assert.equal(hard.evaluatedAt, body.evaluatedAt); assert.equal(hard.canonicalization, canonicalization);
  assert.equal(hard.auditabilitySha256, source.auditability === null ? null : hash(source.auditability.supplement));
  assert.equal(hard.auditabilityAssertionsSha256, source.auditability === null ? null : hash(source.auditability.assertions));
  assert.equal(d.binding.inputs.weightsSha256, hash(request.weights));
  const weights = request.weights.values, preferred = decisionPolicy.inputRoutes.filter(r => r.factDependency?.startsWith("facts.")
    && get(body.evaluationProfile, r.profilePath) === "PREFERRED").map(r => r.factDependency.slice(6)).sort();
  assert.deepEqual(weights.map(w => w.capability).sort(), preferred); assert.equal(new Set(weights.map(w => w.capability)).size, weights.length);
  assert.equal(request.weights.mode, preferred.length ? "EXPLICIT" : "NONE");
  if (preferred.length) assert.equal(weights.reduce((sum, w) => sum + w.weight, 0), 100);
  // The digest preserves the caller's declared array order; only presentation contributions are sorted.
  assert.deepEqual(d.weights, request.weights);
  assert.deepEqual(d.candidates.map(c => c.hardChecks.optionId), source.catalog.options.map(o => o.id).sort());
  for (const candidate of d.candidates) {
    const c = candidate.hardChecks, option = source.catalog.options.find(o => o.id === c.optionId), findings = c.findings;
    for (const key of ["providerId", "product", "plan", "region", "deployment", "configuration"]) assert.equal(c[key], option[key]);
    const verdict = findings.some(f => f.outcome === "FAIL") ? "EXCLUDED" : findings.some(f => f.outcome === "UNKNOWN") ? "UNRESOLVED" : "ELIGIBLE";
    assert.equal(c.hardVerdict, verdict);
    for (const f of findings) if (f.evidence !== null) {
      let fact, claim, assertion;
      if (f.factPath.startsWith("auditabilitySupplement.")) {
        const criterion = f.factPath.slice("auditabilitySupplement.".length), supplement = source.auditability.supplement;
        const scoped = supplement.options.find(o => o.scope.optionId === c.optionId); fact = scoped.facts.find(fact => fact.criterion === criterion);
        claim = hash({ scope: "DECISION_AUDITABILITY_CLAIM_V1", supplementSha256: hash(supplement), optionScope: scoped.scope, fact });
        assertion = source.auditability.assertions.find(a => a.optionId === c.optionId && a.criterion === criterion)?.assertion ?? null;
      } else {
        fact = get(option, f.factPath); claim = hash({ optionId: c.optionId, optionSha256: hash(option), factPath: f.factPath, fact });
        assertion = source.assertions.facts.find(a => a.optionId === c.optionId && a.factPath === f.factPath)?.assertion ?? null;
      }
      assert.equal(f.evidence.claimSha256, claim); assert.equal(f.evidence.sourceAssertion, assertion);
      assert.equal(f.evidence.sourceUrl, fact.evidence.sourceUrl); assert.equal(f.evidence.observedAt, fact.evidence.observedAt);
      assert.deepEqual(f.evidence.conditions, fact.conditions); assert.equal(f.evidence.documentedMinimumRetentionDays, fact.documentedMinimumRetentionDays ?? null);
    }
    if (verdict !== "ELIGIBLE" || request.weights.mode === "NONE") assert.equal(candidate.score, null);
    else {
      const score = candidate.score; assert.deepEqual(score.contributions.map(c => ({ capability: c.capability, weight: c.weight })),
        d.weights.values.toSorted((a, b) => a.capability.localeCompare(b.capability)));
      assert.equal(score.lowerBound, score.contributions.reduce((sum, c) => sum + c.earnedPoints, 0));
      assert.equal(score.unknownWeight, score.contributions.filter(c => c.outcome === "UNKNOWN").reduce((sum, c) => sum + c.weight, 0));
      assert.equal(score.upperBound, score.lowerBound + score.unknownWeight);
      for (const contribution of score.contributions) assert.equal(contribution.earnedPoints, contribution.outcome === "AVAILABLE" ? contribution.weight : 0);
    }
  }
  const eligible = d.candidates.filter(c => c.hardChecks.hardVerdict === "ELIGIBLE");
  assert.deepEqual(d.shortlist, eligible.map(c => c.hardChecks.optionId));
  const rankable = eligible.length > 0 && request.weights.mode === "EXPLICIT" && eligible.every(c => c.score.unknownWeight === 0);
  const groups = [...new Set(eligible.map(c => c.score?.lowerBound))].sort((a, b) => b - a);
  assert.deepEqual(d.rankGroups, rankable ? groups.map((score, i) => ({ rank: i + 1, optionIds: eligible.filter(c => c.score.lowerBound === score).map(c => c.hardChecks.optionId) })) : []);
  assert.equal(d.status, eligible.length ? rankable ? "RANKED_SHORTLIST" : "UNRANKED_SHORTLIST"
    : d.candidates.some(c => c.hardChecks.hardVerdict === "UNRESOLVED") ? "NEEDS_INFORMATION" : "NO_ELIGIBLE_OPTIONS");
  for (const flag of ["sourceAuthorityVerified", "configurationVerified", "complianceVerified", "publicationReady", "writesPerformed"]) assert.equal(d[flag], false);
}
