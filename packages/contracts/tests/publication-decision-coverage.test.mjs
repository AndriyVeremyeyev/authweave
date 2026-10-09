import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertCoverage, canonicalization, componentVersions, coverageScope, coverageVersion, decisionPolicySha256,
  manifestSha256, orderedHash, profileSchemaSha256, routes, scenarios, scenarioSetSha256, verificationGaps, withheldFlags } from "./helpers/publication-decision-coverage-spec.mjs";

const schema = JSON.parse(await readFile(new URL("../schemas/publication-decision-coverage.v1.schema.json", import.meta.url), "utf8"));
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv); const validate = ajv.compile(schema);
function fixture() {
  const reference = { reviewId: "00000000-0000-4000-8000-000000000001", reviewSha256: "1".repeat(64), decisionCatalogSha256: "2".repeat(64), auditability: null };
  return { scope: coverageScope, policyVersion: coverageVersion, canonicalization, evaluatedAt: "2026-10-09T17:00:00Z",
    profileSchemaVersion: 6, profileSchemaSha256, decisionPolicySha256, scenarioSetSha256, manifestSha256, componentVersions,
    before: reference, after: structuredClone(reference), verificationGaps, uncoveredFacts: [], status: "COMPLETE_DECLARED_SCOPE",
    decisionScopeCoverageComplete: true, storedSourceReviewsVerified: false, ...Object.fromEntries(withheldFlags.map(f => [f, false])),
    scenarios: scenarios.map(s => ({ scenarioId: s.id, profileSha256: orderedHash(s.profile), weightsSha256: orderedHash(s.weights),
      impactSha256: "3".repeat(64), beforeResultSha256: "4".repeat(64), afterResultSha256: "4".repeat(64), beforeInputSha256: "5".repeat(64), afterInputSha256: "5".repeat(64),
      beforeOptions: 1, afterOptions: 1, beforeUnknownFindings: 20, afterUnknownFindings: 20, decisionOutcomesChanged: false,
      routes: routes.map(r => ({ profilePath: r.profilePath, handling: r.handling, beforeOutputs: r.handling === "CONDITIONAL_ARCHITECTURE" ? 5 : r.handling === "AUDITABILITY_FINDINGS" ? 6 : 1,
        afterOutputs: r.handling === "CONDITIONAL_ARCHITECTURE" ? 5 : r.handling === "AUDITABILITY_FINDINGS" ? 6 : 1, accounted: true })) })) };
}
test("new decision scope is distinct from historical incomplete/global verification scope", () => {
  const check = fixture(); assert(validate(check), ajv.errorsText(validate.errors)); assertCoverage(check);
  assert.equal(check.scenarios.flatMap(s => s.routes).length, 136); assert.equal(check.verificationGaps.length, 22);
  assert.equal(new Set(routes.map(r => r.profilePath)).size, 34); assert.equal(check.storedSourceReviewsVerified, false);
  for (const flag of withheldFlags) assert.equal(schema.properties[flag].const, false, flag);
  assert.equal(schema.additionalProperties, false); assert.deepEqual(new Set(schema.required), new Set(Object.keys(schema.properties)));
  assert.equal(orderedHash(scenarios), scenarioSetSha256);
});
test("strict schema rejects authority inflation, omitted fields, legacy payloads and unknown versions", () => {
  for (const flag of withheldFlags) assert.equal(validate({ ...fixture(), [flag]: true }), false, flag);
  for (const field of schema.required) { const check = fixture(); delete check[field]; assert.equal(validate(check), false, field); }
  assert.equal(validate({ ...fixture(), scope: "CATALOG_PROFILE_V6_COVERAGE" }), false);
  assert.equal(validate({ ...fixture(), policyVersion: "future" }), false);
  assert.equal(validate({ ...fixture(), actorSubject: "private" }), false);
  assert.equal(validate({ ...fixture(), componentVersions: { ...componentVersions, impact: "future" } }), false);
});
test("shape-valid substitutions cannot silently replace policy/profile/weights/source bindings", () => {
  const mutate = [c => c.manifestSha256 = "0".repeat(64), c => c.decisionPolicySha256 = "0".repeat(64), c => c.scenarioSetSha256 = "0".repeat(64),
    c => c.scenarios[0].profileSha256 = "0".repeat(64), c => c.scenarios[0].weightsSha256 = "0".repeat(64),
    c => c.scenarios[0].beforeInputSha256 = "0".repeat(64), c => c.before.decisionCatalogSha256 = "0".repeat(64),
    c => c.after.reviewSha256 = "0".repeat(64), c => c.evaluatedAt = "2026-10-10T17:00:00Z"];
  for (const change of mutate) {
    const check = structuredClone(fixture()), origin = structuredClone(check); change(check);
    assert(validate(check), ajv.errorsText(validate.errors)); assert.throws(() => assertCoverage(check, origin));
  }
});
test("all profiles/routes and exact verification gaps are necessary, not aggregate checkbox counts", () => {
  for (const change of [c => c.scenarios[0].routes.pop(), c => c.scenarios.pop(), c => c.verificationGaps.pop()]) {
    const check = structuredClone(fixture()); change(check); assert.equal(validate(check), false);
  }
  for (const change of [c => { c.scenarios[0].routes[0].profilePath = c.scenarios[0].routes[1].profilePath; c.scenarios[0].routes[0].beforeOutputs = 2; },
    c => c.scenarios[0].routes[0].handling = "EXPLICIT_LIMITATION", c => c.verificationGaps.reverse(),
    c => c.verificationGaps[0].boundary = "Scope was verified", c => c.scenarios.reverse()]) {
    const check = structuredClone(fixture()); change(check); assert(validate(check), ajv.errorsText(validate.errors)); assert.throws(() => assertCoverage(check));
  }
});
test("an unaccounted route or fact produces INCOMPLETE without live verification or approval", () => {
  const routeGap = structuredClone(fixture()); const row = routeGap.scenarios[0].routes[0]; row.beforeOutputs = 0; row.accounted = false;
  routeGap.status = "INCOMPLETE"; routeGap.decisionScopeCoverageComplete = false; assert(validate(routeGap), ajv.errorsText(validate.errors)); assertCoverage(routeGap);
  const factGap = structuredClone(fixture()); factGap.uncoveredFacts = ["after:fictional-option|facts.SCIM"];
  factGap.status = "INCOMPLETE"; factGap.decisionScopeCoverageComplete = false; assert(validate(factGap), ajv.errorsText(validate.errors)); assertCoverage(factGap);
  factGap.decisionScopeCoverageComplete = true; factGap.status = "COMPLETE_DECLARED_SCOPE"; assert.equal(validate(factGap), false);
});
