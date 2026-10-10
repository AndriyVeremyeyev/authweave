import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { canonicalization, coverageScope, decisionPolicySha256, orderedHash, profileSchemaSha256,
  routes, scenarios, scenarioSetSha256, verificationGaps, withheldFlags } from "./helpers/publication-decision-coverage-spec.mjs";
import { assertProposalCoverage, componentVersions, manifestSha256, proposalCoverageVersion } from "./helpers/publication-proposal-coverage-spec.mjs";

// Shape-only fixtures. Actual Java inputs/results are validated by check-published-proposal-coverage.mjs.
const directory = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const name of await readdir(directory)) if (name.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(name, directory), "utf8")));
const schema = ajv.getSchema("https://authweave.dev/contracts/publication-proposal-decision-coverage.v1.schema.json").schema;
const validate = ajv.getSchema(schema.$id), validateBootstrap = ajv.getSchema("https://authweave.dev/contracts/publication-decision-coverage.v1.schema.json");
const hash = "1".repeat(64), uuid = "00000000-0000-4000-8000-000000000001";
function fixture() {
  return { scope: coverageScope, policyVersion: proposalCoverageVersion, canonicalization, evaluatedAt: "2026-10-09T17:00:00Z",
    profileSchemaVersion: 6, profileSchemaSha256, decisionPolicySha256, scenarioSetSha256, manifestSha256, componentVersions,
    before: { snapshotId: uuid, catalogVersion: "fictional-root", snapshotSha256: hash }, beforeProofSha256: hash, beforeCatalogSha256: hash,
    after: { revision: { proposalId: uuid, version: 0, proposalSha256: hash, requestSha256: hash }, reviewThroughNumber: 0, reviewSetSha256: hash, auditability: null },
    afterCatalogSha256: hash, uncoveredFacts: [], verificationGaps, status: "COMPLETE_DECLARED_SCOPE", decisionScopeCoverageComplete: true,
    historicalPublicationWorkflowVerified: true, storedSourceReviewsVerified: true, assessmentResultPinned: false,
    ...Object.fromEntries(withheldFlags.map(f => [f, false])),
    candidateClaims: { recorded: 2, supporting: 2, contradicted: 0, insufficient: 0, unreviewed: 0, current: 2, stale: 0, future: 0, allRecordedClaimsSupportedAndCurrent: true },
    scenarios: scenarios.map(s => ({ scenarioId: s.id, profileSha256: orderedHash(s.profile), weightsSha256: orderedHash(s.weights),
      impactSha256: hash, beforeResultSha256: hash, afterResultSha256: hash, beforeInputSha256: hash, afterInputSha256: hash,
      beforeOptions: 1, afterOptions: 1, beforeUnknownFindings: 20, afterUnknownFindings: 20, decisionOutcomesChanged: false,
      routes: routes.map(r => ({ profilePath: r.profilePath, handling: r.handling,
        beforeOutputs: r.handling === "CONDITIONAL_ARCHITECTURE" ? 5 : r.handling === "AUDITABILITY_FINDINGS" ? 6 : 1,
        afterOutputs: r.handling === "CONDITIONAL_ARCHITECTURE" ? 5 : r.handling === "AUDITABILITY_FINDINGS" ? 6 : 1, accounted: true })) })) };
}
function fresh() { return structuredClone(fixture()); }
test("new coverage policy keeps original bootstrap format distinct and never implies publication authority", () => {
  const check = fresh(); assert(validate(check), ajv.errorsText(validate.errors)); assertProposalCoverage(check, check);
  assert.equal(validateBootstrap(check), false); assert.equal(check.scenarios.flatMap(s => s.routes).length, 136);
  assert.equal(Object.keys(componentVersions).length, 23); assert.equal(check.verificationGaps.length, 22);
  assert.equal(schema.additionalProperties, false); assert.deepEqual(new Set(schema.required), new Set(Object.keys(schema.properties)));
  assert.equal(check.scenarios.some(s => s.afterUnknownFindings > 0), true);
});
test("missing fields, undeclared identity, changed policies and authority inflation fail strict schema", () => {
  for (const flag of [...withheldFlags, "assessmentResultPinned"]) assert.equal(validate({ ...fresh(), [flag]: true }), false, flag);
  for (const flag of ["historicalPublicationWorkflowVerified", "storedSourceReviewsVerified"]) assert.equal(validate({ ...fresh(), [flag]: false }), false, flag);
  for (const field of schema.required) { const check = fresh(); delete check[field]; assert.equal(validate(check), false, field); }
  assert.equal(validate({ ...fresh(), curatorSubject: "private" }), false);
  assert.equal(validate({ ...fresh(), policyVersion: "publication-decision-coverage-1" }), false);
  assert.equal(validate({ ...fresh(), componentVersions: { ...componentVersions, proposalReviewLoading: "future" } }), false);
});
test("exact publication tuple and bounded proposal revision/cutoff require explicit nullable supplement", () => {
  for (const change of [c => delete c.after.auditability, c => c.after.revision.version = -1,
    c => c.after.reviewThroughNumber = 9007199254740992, c => c.after.revision.requestSha256 = "bad",
    c => c.before.snapshotId = "bad", c => c.before.catalogVersion = "UPPERCASE",
    c => c.after.auditability = { reviewId: uuid, reviewSha256: hash }, c => c.after.curator = "private"]) {
    const check = fresh(); change(check); assert.equal(validate(check), false);
  }
  const check = fresh(); check.after.auditability = { reviewId: uuid, reviewSha256: hash, decisionSupplementSha256: hash };
  assert(validate(check), ajv.errorsText(validate.errors)); assertProposalCoverage(check);
});
test("shape-valid source, clock, policy, profile and weight substitutions fail independent originating bindings", () => {
  for (const change of [c => c.beforeProofSha256 = "0".repeat(64), c => c.before.snapshotSha256 = "0".repeat(64),
    c => c.after.revision.requestSha256 = "0".repeat(64), c => c.after.reviewSetSha256 = "0".repeat(64), c => c.after.reviewThroughNumber++,
    c => c.evaluatedAt = "2026-10-10T17:00:00Z", c => c.manifestSha256 = "0".repeat(64),
    c => c.decisionPolicySha256 = "0".repeat(64), c => c.scenarioSetSha256 = "0".repeat(64),
    c => c.scenarios[0].profileSha256 = "0".repeat(64), c => c.scenarios[0].weightsSha256 = "0".repeat(64)]) {
    const origin = fresh(), check = fresh(); change(check); assert(validate(check), ajv.errorsText(validate.errors)); assert.throws(() => assertProposalCoverage(check, origin));
  }
});
test("rule completeness is independent of negative or incomplete recorded source review eligibility", () => {
  for (const field of ["contradicted", "insufficient", "unreviewed", "stale", "future"]) {
    const check = fresh(), claims = check.candidateClaims; claims[field] = 1;
    claims[["stale", "future"].includes(field) ? "current" : "supporting"]--; claims.allRecordedClaimsSupportedAndCurrent = false;
    assert(validate(check), ajv.errorsText(validate.errors)); assertProposalCoverage(check);
    assert.equal(check.decisionScopeCoverageComplete, true); assert.equal(check.publicationReady, false);
  }
});
test("review/date totals and false eligibility cannot be hidden behind well-formed aggregate counts", () => {
  for (const change of [c => c.candidateClaims.supporting++, c => c.candidateClaims.current--,
    c => c.candidateClaims.allRecordedClaimsSupportedAndCurrent = false]) {
    const check = fresh(); change(check); assert(validate(check), ajv.errorsText(validate.errors)); assert.throws(() => assertProposalCoverage(check));
  }
  for (const change of [c => c.candidateClaims.recorded = 0, c => c.candidateClaims.current = 7401,
    c => c.candidateClaims.unreviewed = 1, c => c.candidateClaims.stale = 1, c => c.candidateClaims.future = 1]) {
    const check = fresh(); change(check); assert.equal(validate(check), false);
  }
});
test("all exact profiles, routes, gaps and unique sorted uncovered addresses remain necessary", () => {
  for (const change of [c => c.scenarios.pop(), c => c.scenarios[0].routes.pop(), c => c.verificationGaps.pop()]) {
    const check = fresh(); change(check); assert.equal(validate(check), false);
  }
  for (const change of [c => c.scenarios.reverse(), c => c.verificationGaps.reverse(),
    c => { c.scenarios[0].routes[0].profilePath = c.scenarios[0].routes[1].profilePath; c.scenarios[0].routes[0].beforeOutputs = 2; },
    c => c.scenarios[0].routes[0].handling = "EXPLICIT_LIMITATION",
    c => { c.status = "INCOMPLETE"; c.decisionScopeCoverageComplete = false; c.uncoveredFacts = ["after:z|facts.SCIM", "after:a|facts.SCIM"]; }]) {
    const check = fresh(); change(check); assert(validate(check), ajv.errorsText(validate.errors)); assert.throws(() => assertProposalCoverage(check));
  }
});
test("a genuinely unaccounted route or address is incomplete even with eligible recorded claims", () => {
  const check = fresh(); check.status = "INCOMPLETE"; check.decisionScopeCoverageComplete = false;
  check.uncoveredFacts = ["after:fictional-option|facts.SCIM"]; assert(validate(check), ajv.errorsText(validate.errors)); assertProposalCoverage(check);
  check.uncoveredFacts = []; const row = check.scenarios[0].routes[0]; row.beforeOutputs = 0; row.accounted = false;
  assert(validate(check), ajv.errorsText(validate.errors)); assertProposalCoverage(check); assert.equal(check.candidateClaims.allRecordedClaimsSupportedAndCurrent, true);
  check.status = "COMPLETE_DECLARED_SCOPE"; check.decisionScopeCoverageComplete = true; assert.equal(validate(check), false);
});
