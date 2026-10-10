import assert from "node:assert/strict";
import { assertCoverage, coverageVersion, componentVersions as baseComponents, manifestSha256 as baseManifest,
  orderedHash, scenarios, routes, verificationGaps, withheldFlags } from "./publication-decision-coverage-spec.mjs";

// Artifact binding/recount only. Java authenticates DB provenance and runs the real kernels;
// this checker does not fetch sources, authenticate curators or implement a second decision engine.
export const proposalCoverageVersion = "publication-proposal-decision-coverage-1";
export const componentVersions = { ...baseComponents, publishedBaselineLoading: "published-bootstrap-loading-1",
  proposalReviewLoading: "decision-proposal-review-loading-1", publishedProposalImpact: "decision-published-proposal-impact-1", underlyingCoverage: coverageVersion };
export const manifestSha256 = orderedHash([proposalCoverageVersion, "DECLARED_DECISION_RULES_ONLY", baseManifest, componentVersions]);
export function assertProposalCoverage(check, origin) {
  assert.equal(check.policyVersion, proposalCoverageVersion); assert.equal(check.manifestSha256, manifestSha256);
  assert.deepEqual(check.componentVersions, componentVersions); assert.equal(check.historicalPublicationWorkflowVerified, true);
  assert.equal(check.storedSourceReviewsVerified, true); assert.equal(check.assessmentResultPinned, false);
  // Reuse unchanged route/scenario/rule assertions, not fabricated bootstrap source references.
  assertCoverage({ ...check, policyVersion: coverageVersion, manifestSha256: baseManifest, componentVersions: baseComponents });
  assert.deepEqual(check.uncoveredFacts, [...new Set(check.uncoveredFacts)].sort());
  if (origin) for (const field of ["before", "beforeProofSha256", "after", "evaluatedAt"]) assert.deepEqual(check[field], origin[field], field);
  const c = check.candidateClaims;
  assert.equal(c.supporting + c.contradicted + c.insufficient + c.unreviewed, c.recorded);
  assert.equal(c.current + c.stale + c.future, c.recorded);
  assert.equal(c.allRecordedClaimsSupportedAndCurrent, c.supporting === c.recorded && c.current === c.recorded);
}
function instant(value) {
  const m = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(value);
  assert(m, "Core uses UTC Instant with exact nanoseconds"); const seconds = Date.parse(`${m[1]}Z`) / 1000;
  assert(Number.isSafeInteger(seconds)); return BigInt(seconds) * 1000000000n + BigInt((m[2] ?? "").padEnd(9, "0"));
}
function facts(catalog) {
  const result = new Map();
  for (const option of catalog.options) {
    const add = (path, fact) => {
      const key = `${option.id}|${path}`; assert(!result.has(key));
      result.set(key, { fact, option, claimSha256: orderedHash({ optionId: option.id, optionSha256: orderedHash(option), factPath: path, fact }) });
    };
    for (const [name, fact] of Object.entries(option.facts)) add(`facts.${name}`, fact);
    for (const [category, entries] of Object.entries(option.compatibility)) for (const [name, fact] of Object.entries(entries)) add(`compatibility.${category}.${name}`, fact);
    for (const [name, fact] of Object.entries(option.residency)) add(`residency.${name}`, fact);
    for (const [client, populations] of Object.entries(option.authenticationControls)) for (const [population, controls] of Object.entries(populations))
      for (const [name, fact] of Object.entries(controls)) add(`authenticationControls.${client}.${population}.${name}`, fact);
  }
  return result;
}
function claims(snapshot) {
  const base = facts(snapshot.catalog), result = new Map();
  assert.equal(snapshot.assertions.candidateSha256, orderedHash(snapshot.catalog));
  for (const [address, value] of base) result.set(address, { ...value, assertion: null });
  const selected = new Set();
  for (const observation of snapshot.assertions.facts) {
    const address = `${observation.optionId}|${observation.factPath}`, value = result.get(address);
    assert(value && !selected.has(address)); selected.add(address); assert.equal(observation.claimSha256, value.claimSha256);
    value.assertion = observation.assertion;
  }
  if (snapshot.auditability) {
    const { supplement, assertions, candidateSha256 } = snapshot.auditability;
    assert.equal(candidateSha256, orderedHash(snapshot.catalog));
    const digest = orderedHash(supplement), supplemental = new Map();
    for (const option of supplement.options) for (const fact of option.facts) {
      const address = `${option.scope.optionId}|${fact.criterion}`; assert(!supplemental.has(address));
      const value = { fact, assertion: null, claimSha256: orderedHash({ scope: "DECISION_AUDITABILITY_CLAIM_V1", supplementSha256: digest, optionScope: option.scope, fact }) };
      supplemental.set(address, value); result.set(`${option.scope.optionId}|auditabilitySupplement.${fact.criterion}`, value);
    }
    const selectedAudit = new Set();
    for (const observation of assertions) {
      const address = `${observation.optionId}|${observation.criterion}`, value = supplemental.get(address);
      assert(value && !selectedAudit.has(address)); selectedAudit.add(address); assert.equal(value.claimSha256, observation.claimSha256); value.assertion = observation.assertion;
    }
  }
  return result;
}
function recount(snapshot, at) {
  const result = { recorded: 0, supporting: 0, contradicted: 0, insufficient: 0, unreviewed: 0, current: 0, stale: 0, future: 0 }, now = instant(at);
  for (const { fact, assertion } of claims(snapshot).values()) {
    result.recorded++;
    const field = { SOURCE_SUPPORTS_CLAIM: "supporting", SOURCE_DOES_NOT_SUPPORT_CLAIM: "contradicted", INSUFFICIENT_EVIDENCE: "insufficient" }[assertion];
    assert(assertion == null || field); result[field ?? "unreviewed"]++;
    const observed = instant(fact.evidence.observedAt);
    result[observed > now ? "future" : observed < now - 90n * 86400n * 1000000000n ? "stale" : "current"]++;
  }
  return { ...result, allRecordedClaimsSupportedAndCurrent: result.supporting === result.recorded && result.current === result.recorded };
}
function outputs(route, profile, result) {
  const value = route.profilePath.split(".").reduce((v, key) => v?.[key], profile); assert.notEqual(value, undefined);
  if (route.handling === "CONDITIONAL_ARCHITECTURE") return result.architecture.patterns.length === 5 ? 5 : 0;
  if (route.handling === "EXPLICIT_LIMITATION") return result.limitations.filter(l => l.profilePath === route.profilePath && l.declaredValue === JSON.stringify(value)).length;
  const counts = result.candidates.map(c => c.hardChecks.findings.filter(f => f.profilePath === route.outputProfilePath).length);
  return counts.includes(0) ? 0 : counts.reduce((a, b) => a + b, 0);
}
const unknowns = result => result.candidates.flatMap(c => c.hardChecks.findings).filter(f => f.outcome === "UNKNOWN").length;
function consumed(result, addresses) {
  for (const c of result.candidates) for (const f of c.hardChecks.findings) if (f.factPath != null) addresses.add(`${c.hardChecks.optionId}|${f.factPath}`);
  const choice = c => c.optionChecks.forEach(o => o.capabilities.forEach(f => addresses.add(`${o.optionId}|facts.${f.capability}`)));
  result.architecture.patterns.forEach(p => choice(p.choice)); result.architecture.provisioning.forEach(choice);
  result.architecture.apiProtection.optionChecks.forEach(o => o.capabilities.forEach(f => addresses.add(`${o.optionId}|facts.${f.capability}`)));
}
export function assertProposalCalculation(sample) {
  const { check, before, after, impacts, origin } = sample; assertProposalCoverage(check, origin);
  assert.equal(check.beforeCatalogSha256, orderedHash(before.catalog)); assert.equal(check.afterCatalogSha256, orderedHash(after.catalog));
  assert.equal(check.before.catalogVersion, before.catalog.catalogVersion);
  if (check.after.auditability === null) assert.equal(after.auditability, null);
  else assert.equal(check.after.auditability.decisionSupplementSha256, orderedHash(after.auditability.supplement));
  assert.deepEqual(check.candidateClaims, recount(after, check.evaluatedAt));
  const baseClaims = claims(before), nextClaims = claims(after), consumedBefore = new Set(), consumedAfter = new Set();
  assert.equal(impacts.length, 4);
  for (const [i, impact] of impacts.entries()) {
    const scenario = scenarios[i], summary = check.scenarios[i];
    assert.equal(impact.evaluatedAt, check.evaluatedAt); assert.equal(impact.profileSha256, orderedHash(scenario.profile)); assert.equal(impact.weightsSha256, orderedHash(scenario.weights));
    assert.equal(impact.beforeInputSha256, orderedHash(before)); assert.equal(impact.afterInputSha256, orderedHash(after));
    assert.equal(impact.beforeResultSha256, orderedHash(impact.before)); assert.equal(impact.afterResultSha256, orderedHash(impact.after));
    assert.equal(summary.impactSha256, orderedHash(impact));
    for (const field of ["profileSha256", "weightsSha256", "beforeResultSha256", "afterResultSha256", "beforeInputSha256", "afterInputSha256", "decisionOutcomesChanged"]) assert.equal(summary[field], impact[field], field);
    assert.equal(summary.beforeOptions, impact.before.candidates.length); assert.equal(summary.afterOptions, impact.after.candidates.length);
    assert.equal(summary.beforeUnknownFindings, unknowns(impact.before)); assert.equal(summary.afterUnknownFindings, unknowns(impact.after));
    assert.deepEqual(summary.routes, routes.map(r => ({ profilePath: r.profilePath, handling: r.handling, beforeOutputs: outputs(r, scenario.profile, impact.before),
      afterOutputs: outputs(r, scenario.profile, impact.after), accounted: outputs(r, scenario.profile, impact.before) > 0 && outputs(r, scenario.profile, impact.after) > 0 })));
    for (const flag of ["coverageComplete", "sourceVerificationPerformed", "approvalGranted", "publicationReady", "writesPerformed"]) assert.equal(impact[flag], false, flag);
    for (const result of [impact.before, impact.after]) {
      for (const flag of ["sourceAuthorityVerified", "configurationVerified", "complianceVerified", "publicationReady", "writesPerformed"]) assert.equal(result[flag], false, flag);
      assert.equal(result.binding.inputs.hardChecks.evaluatedAt, check.evaluatedAt);
    }
    consumed(impact.before, consumedBefore); consumed(impact.after, consumedAfter);
  }
  const missing = [...[...baseClaims.keys()].filter(f => !consumedBefore.has(f)).map(f => `before:${f}`),
    ...[...nextClaims.keys()].filter(f => !consumedAfter.has(f)).map(f => `after:${f}`)].sort();
  assert.deepEqual(check.uncoveredFacts, missing); assert.deepEqual(check.verificationGaps, verificationGaps);
  for (const flag of withheldFlags) assert.equal(check[flag], false, flag);
}
