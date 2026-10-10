import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { planningInputRoutes } from "./helpers/profile-planning-coverage-spec.mjs";
import { assertDecisionSpec, hash, policy, profileFor, profiles, resultFixture, schemaValidate, suite } from "./helpers/decision-result-spec.mjs";

test("P3-1 declares exactly the existing 34 input routes and bounded provider inventory", () => {
  assert.equal(policy.implementation, "RESERVED_ACCEPTANCE_TARGET");
  assert.equal(policy.defaultWeights, null);
  assert.deepEqual(policy.inputRoutes.map(r => r.profilePath).sort(), planningInputRoutes().map(r => r.profilePath).sort());
  assert.equal(new Set(policy.inputRoutes.map(r => r.profilePath)).size, 34);
  const legalRoutes = new Set(["HARD", "PREFERENCE", "CONTEXT", "ARCHITECTURE", "LIMITATION"]);
  for (const route of policy.inputRoutes) {
    assert(route.routes.length); assert.equal(new Set(route.routes).size, route.routes.length);
    assert(route.routes.every(r => legalRoutes.has(r)));
    assert.deepEqual(Object.keys(route).sort(), ["factDependency", "profilePath", "routes"]);
  }
  assert.deepEqual(policy.providerInventory.map(p => p.providerId), ["keycloak", "zitadel", "auth0", "workos", "entra-external-id"]);
  assert.equal(policy.providerInventory.find(p => p.providerId === "workos").scopes.length, 3);
  assert.equal(policy.providerInventory.find(p => p.providerId === "entra-external-id").scopes.length, 2);
  assert.deepEqual(policy.architecture.patterns, ["BFF_SESSION", "SERVER_SIDE_SESSION", "SPA_CODE_PKCE", "NATIVE_CODE_PKCE", "M2M_CLIENT_CREDENTIALS"]);
  assert.equal(policy.architecture.observedVerification, false);
});

test("18 acceptance targets remain six normal, six incomplete and six adversarial across three profiles", () => {
  assert.equal(suite.implementation, "EXPECTED_CONTRACT_CASES_NOT_RUNTIME_ENGINE_PROOF");
  assert.equal(suite.cases.length, 18); assert.equal(new Set(suite.cases.map(c => c.id)).size, 18);
  for (const category of ["NORMAL", "MISSING_OR_CONTRADICTORY", "ADVERSARIAL_OR_FAILURE"]) {
    const cases = suite.cases.filter(c => c.category === category); assert.equal(cases.length, 6);
    for (const id of ["b2b-saas-scoped", "public-sector-scoped", "internal-workforce-scoped"]) assert.equal(cases.filter(c => c.profileId === id).length, 2);
  }
  for (const c of suite.cases) {
    assert(profiles.some(p => p.id === c.profileId));
    assert(c.checks.every(check => policy.inputRoutes.some(r => r.profilePath === check[1])));
    assert.equal(c.expected.verdicts.length, 2);
    if (c.profileId === "b2b-saas-scoped") assert.equal(profileFor(c).provisioning.scim, "REQUIRED");
  }
});

test("real-kernel inputs cover the same 18 cases independently of expected outputs", async () => {
  const inputs = JSON.parse(await readFile(new URL("../decision-core/inputs.v1.json", import.meta.url), "utf8"));
  assert.equal(inputs.caseSetVersion, suite.caseSetVersion); assert.equal(inputs.cases.length, 18);
  assert.equal(new Set(inputs.cases.map(c => c.id)).size, 18);
  assert.deepEqual(inputs.cases.map(c => c.id).sort(), suite.cases.map(c => c.id).sort());
  for (const c of inputs.cases) {
    assert(Array.isArray(c.changes));
    assert(Object.keys(c).every(key => ["id", "changes", "removeFacts", "omitAssertions", "auditChanges", "rejectBorrowedAssertion", "rejectForgedScore", "verifyPinnedReplay"].includes(key)));
    assert(!Object.hasOwn(c, "expected"));
  }
  for (const c of suite.cases.filter(c => c.kernelChecks)) {
    assert.equal(c.kernelChecks.length, c.checks.length);
    assert.deepEqual(c.kernelChecks.map(check => check.slice(0, 4)), c.checks.map(check => check.slice(0, 4)), "Only specific reason vocabulary differs, not criticality or outcome");
  }
});

for (const c of suite.cases) test(`reserved decision contract: ${c.id}`, () => {
  const result = resultFixture(c);
  assertDecisionSpec(result, structuredClone(result.binding), profileFor(c));
  assert.equal(result.configurationVerified, false); assert.equal(result.complianceVerified, false);
});

const caseById = id => suite.cases.find(c => c.id === id);
const ranked = caseById("normal-public-explicit-preferences");
function rejects(c, mutate, shapeValid = false) {
  const result = resultFixture(c), binding = structuredClone(result.binding), profile = profileFor(c);
  mutate(result);
  if (shapeValid) assert.equal(schemaValidate(result), true, JSON.stringify(schemaValidate.errors));
  assert.throws(() => assertDecisionSpec(result, binding, profile));
}

test("strict schema rejects private fields, authority claims, unknown enums and malformed bindings", () => {
  const mutations = [
    r => r.token = "private", r => r.binding.actorSubject = "private", r => r.candidates[0].sourceBody = "private",
    r => r.evidence[0].cookie = "private", r => r.architecture.tenantSecret = "private",
    r => r.configurationVerified = true, r => r.complianceVerified = true, r => r.publicationReady = true,
    r => r.candidates[0].hardVerdict = "VERIFIED", r => r.binding.profileSchemaVersion = 7,
    r => r.binding.catalogSha256 = "bad", r => r.binding.snapshotId = "00000000-0000-4000-8000-000000000003",
    r => r.binding.catalogBasis = "PUBLISHED_SNAPSHOT", r => r.evidence[0].sourceUrl = "http://acceptance.invalid/a",
    r => r.candidates[0].score.lowerBound = -1, r => r.weights.values[0].weight = 0,
    r => delete r.limitations, r => delete r.binding.evaluatedAt
  ];
  for (const mutate of mutations) rejects(ranked, mutate);
});

test("shape-valid substitutions fail exact originating version, digest, workspace and time bindings", () => {
  for (const [key, value] of [
    ["workspaceId", "00000000-0000-4000-8000-000000000004"], ["assessmentId", "00000000-0000-4000-8000-000000000005"],
    ["assessmentVersion", 2], ["profileSha256", "0".repeat(64)], ["catalogSha256", "0".repeat(64)],
    ["catalogVersion", "fictional-acceptance-2"], ["weightsSha256", "0".repeat(64)], ["evaluatedAt", "2026-10-10T12:00:00Z"], ["catalogBasis", "REVIEWED_CANDIDATE"]
  ]) rejects(ranked, r => r.binding[key] = value, true);
  rejects(caseById("adversarial-workforce-pinned-result"), r => r.binding.catalogVersion = "fictional-acceptance-2", true);
});

test("schema-valid evidence borrowing, unsafe sources and false source promotion fail semantic checks", () => {
  const mutations = [
    r => r.candidates[0].findings[0].evidenceIds = r.candidates[1].findings[0].evidenceIds,
    r => r.evidence[0].optionId = "fixture-beta",
    r => r.evidence[0].factPath = "facts.SCIM",
    r => r.evidence[0].reviewStatus = "UNREVIEWED",
    r => r.evidence[0].assertion = "CONTRADICTS_CLAIM",
    r => r.evidence[0].assertion = "UNKNOWN",
    r => r.evidence[0].observedAt = "2026-10-10T12:00:00Z",
    r => r.evidence[0].observedAt = "2026-06-01T12:00:00Z",
    r => r.evidence[0].sourceUrl = "https://account:password@acceptance.invalid/source",
    r => r.evidence[0].sourceUrl = "https://acceptance.invalid/source#private-fragment",
    r => r.evidence[0].sourceUrl = "https://real-provider.example/source",
    r => r.evidence.push(structuredClone(r.evidence[0]))
  ];
  for (const mutate of mutations) rejects(ranked, mutate, true);
});

test("unknown or excluded hard requirements cannot acquire eligibility, a score or a rank", () => {
  const incomplete = caseById("missing-b2b-required-scim");
  rejects(incomplete, r => r.candidates[0].hardVerdict = "ELIGIBLE", true);
  rejects(incomplete, r => {
    const f = r.candidates[0].findings.find(f => f.profilePath === "provisioning.scim");
    f.criticality = "PREFERRED"; r.candidates[0].hardVerdict = "ELIGIBLE";
  }, true);
  rejects(incomplete, r => {
    const f = r.candidates[0].findings.find(f => f.profilePath === "provisioning.scim");
    f.outcome = "NOT_APPLIED"; f.reasonCode = "NO_REQUIREMENT"; r.candidates[0].hardVerdict = "ELIGIBLE";
  }, true);
  rejects(caseById("adversarial-b2b-weights-no-rescue"), r => r.shortlist.unshift("fixture-alpha"), true);
  rejects(caseById("adversarial-b2b-weights-no-rescue"), r => r.candidates[0].score = structuredClone(r.candidates[1].score));
  rejects(caseById("missing-workforce-failure-precedence"), r => r.candidates[0].hardVerdict = "UNRESOLVED", true);
  rejects(ranked, r => r.candidates[0].findings.pop(), true);
  rejects(ranked, r => r.candidates[0].findings.push(structuredClone(r.candidates[0].findings[0])), true);
});

test("explicit weights and exact contribution arithmetic reject forged scores, duplicate dimensions and tie-breaking winners", () => {
  const mutations = [
    r => r.weights.values[0].weight++,
    r => r.weights.values[1].capability = r.weights.values[0].capability,
    r => r.candidates[0].score.lowerBound--,
    r => r.candidates[0].score.upperBound--,
    r => r.candidates[0].score.unknownWeight++,
    r => r.candidates[0].score.contributions[0].earnedPoints--,
    r => r.candidates[0].score.contributions[0].weight++,
    r => r.candidates[0].score.contributions.pop(),
    r => r.rankGroups.reverse(),
    r => r.shortlist.pop()
  ];
  for (const mutate of mutations) rejects(ranked, mutate, true);
  rejects(caseById("normal-b2b-shared-tie"), r => r.rankGroups = [{ rank: 1, optionIds: ["fixture-alpha"] }, { rank: 2, optionIds: ["fixture-beta"] }], true);
  rejects(caseById("normal-public-no-preferences"), r => r.rankGroups = [{ rank: 1, optionIds: ["fixture-alpha"] }], true);
  rejects(caseById("missing-b2b-preference-range"), r => r.rankGroups = [{ rank: 1, optionIds: ["fixture-beta"] }, { rank: 2, optionIds: ["fixture-alpha"] }], true);
});

test("blocking assurance/compliance limitations and conditional architecture boundaries cannot be cleared", () => {
  const incomplete = caseById("missing-public-assurance-compliance");
  rejects(incomplete, r => { r.limitations.forEach(l => l.blocksRecommendation = false); r.status = "UNRANKED_SHORTLIST"; }, true);
  rejects(ranked, r => r.limitations = [], true);
  rejects(ranked, r => r.architecture.patterns[0].conditions = [], true);
  rejects(ranked, r => r.architecture.patterns[0].cons = [], true);
  rejects(ranked, r => r.architecture.apiProtection = "NOT_REQUIRED", true);
  rejects(ranked, r => r.architecture.patterns[0].patternId = "M2M_CLIENT_CREDENTIALS" /* baseline public profile has no machine client */, true);
  rejects(incomplete, r => r.architecture.patterns = resultFixture(ranked).architecture.patterns, true);
});

test("paired sensitivity preserves profile/catalog/clock and changes only explicit weights/contributions/ranks", () => {
  const c = caseById("normal-workforce-sensitivity"), before = resultFixture(c);
  const variant = structuredClone(c); variant.weights = c.sensitivity.weights;
  variant.expected.ranks = c.sensitivity.ranks; variant.expected.bounds = c.sensitivity.bounds;
  const after = resultFixture(variant);
  assertDecisionSpec(after, { ...before.binding, weightsSha256: after.binding.weightsSha256 }, profileFor(c));
  assert.notEqual(after.binding.weightsSha256, before.binding.weightsSha256);
  assert.deepEqual({ ...after.binding, weightsSha256: before.binding.weightsSha256 }, before.binding);
  assert.throws(() => assertDecisionSpec(after, before.binding, profileFor(c)));
  assert.deepEqual(after.candidates.map(c => c.hardVerdict), before.candidates.map(c => c.hardVerdict));
  assert.notDeepEqual(after.rankGroups, before.rankGroups);
});

test("digest canonicalization ignores object insertion order but preserves explicit array order", () => {
  assert.equal(hash({ b: [1, 2], a: { z: 1, y: 2 } }), hash({ a: { y: 2, z: 1 }, b: [1, 2] }));
  assert.notEqual(hash({ b: [1, 2] }), hash({ b: [2, 1] }));
});
