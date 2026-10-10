import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

// Acceptance specification only. This does not implement a vendor evaluator, API,
// publisher or trusted catalog loader. Expected decisions come from frozen cases.
const contracts = new URL("../../", import.meta.url);
export const policy = JSON.parse(await readFile(new URL("decision-core/policy.v1.json", contracts), "utf8"));
export const suite = JSON.parse(await readFile(new URL("decision-core/cases.v1.json", contracts), "utf8"));
export const profiles = JSON.parse(await readFile(new URL("../../" + suite.profileSource, contracts), "utf8"));
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("schemas/", contracts))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL("schemas/" + file, contracts), "utf8")));
export const schemaValidate = ajv.getSchema("https://authweave.dev/contracts/decision-result.v1.schema.json");
export const profileValidate = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v6.schema.json");
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
export const hash = value => createHash("sha256").update(JSON.stringify(canonical(value)), "utf8").digest("hex");
const capabilityPaths = Object.fromEntries(policy.inputRoutes.filter(r => r.factDependency?.startsWith("facts."))
  .map(r => [r.factDependency.slice(6), r.profilePath]));
const get = (object, path) => path.split(".").reduce((value, key) => value?.[key], object);
function set(object, path, value) {
  const keys = path.split("."); let target = object;
  for (const key of keys.slice(0, -1)) target = target[key];
  target[keys.at(-1)] = structuredClone(value);
}
export function profileFor(c) {
  const profile = structuredClone(profiles.find(p => p.id === c.profileId).profile);
  for (const overrides of [suite.baselineProfileOverrides, suite.profileSpecificOverrides[c.profileId] ?? {}, c.profileOverrides])
    for (const [path, value] of Object.entries(overrides)) set(profile, path, value);
  assert.equal(profileValidate(profile), true, ajv.errorsText(profileValidate.errors));
  return profile;
}
function criticality(profile, route) {
  const value = get(profile, route.profilePath);
  if (route.routes.includes("CONTEXT")) return "CONTEXT";
  if (route.profilePath === "security.assurance") return "CONTEXT";
  if (route.profilePath === "security.auditabilityRequirements.minimumRetentionDays"
      && !profile.security.auditabilityRequirements.selectedCriteria.includes("AUDIT_LOG_RETENTION")) return "NOT_REQUIRED";
  if (["REQUIRED", "FORBIDDEN", "PREFERRED", "NOT_REQUIRED", "UNKNOWN"].includes(value)
      && !route.profilePath.startsWith("operations.")) return value;
  if (route.profilePath.startsWith("security.dataResidencyDetails.")) return profile.security.dataResidency;
  if (route.profilePath.startsWith("security.auditabilityRequirements.")) return profile.security.auditability;
  return "CONTEXT";
}
const blocking = finding => ["REQUIRED", "FORBIDDEN", "CONTEXT"].includes(finding.criticality);
const fixtureId = id => `fixture-${id}`;
export function resultFixture(c) {
  const profile = profileFor(c), weights = policy.scoring.dimensions.filter(capability => Object.hasOwn(c.weights, capability)).map(capability => ({ capability, weight: c.weights[capability] }));
  const evidence = [], candidates = ["alpha", "beta"].map((id, index) => {
    const optionId = fixtureId(id);
    const findings = policy.inputRoutes.map((route, i) => {
      const declaration = criticality(profile, route);
      const applies = (route.routes.includes("CONTEXT") || route.routes.includes("HARD")) && ["REQUIRED", "FORBIDDEN", "CONTEXT"].includes(declaration);
      const target = c.checks.find(check => check[0] === id && check[1] === route.profilePath);
      const [outcome, reasonCode] = target ? target.slice(3) : applies ? ["PASS", "SUPPORTED"] : ["NOT_APPLIED", "NO_REQUIREMENT"];
      const evidenceIds = [];
      if (route.factDependency && applies && reasonCode !== "EVIDENCE_MISSING" && reasonCode !== "REQUIRED_UNKNOWN") {
        const eid = `${optionId}.input-${i}`; evidenceIds.push(eid);
        evidence.push({ id: eid, optionId, factPath: route.factDependency,
          sourceUrl: `https://acceptance.invalid/${optionId}/input-${i}`,
          observedAt: reasonCode === "EVIDENCE_STALE" ? "2026-06-01T12:00:00Z" : reasonCode === "EVIDENCE_FUTURE" ? "2026-10-10T12:00:00Z" : "2026-10-09T11:00:00Z",
          reviewStatus: reasonCode === "EVIDENCE_UNREVIEWED" ? "UNREVIEWED" : "REVIEWED",
          assertion: "SUPPORTS_CLAIM", conditions: ["Fictional scoped documentation hypothesis; no deployment verification."] });
      }
      return { profilePath: route.profilePath, criticality: target?.[2] ?? declaration, outcome, reasonCode, evidenceIds };
    });
    let score = null;
    if (c.expected.bounds[id]) {
      const [lowerBound, upperBound, unknownWeight] = c.expected.bounds[id];
      const contributions = weights.map(w => {
        const outcome = c.preferences[id][w.capability], evidenceIds = [];
        if (outcome !== "UNKNOWN") {
          const eid = `${optionId}.preference-${w.capability.toLowerCase()}`; evidenceIds.push(eid);
          evidence.push({ id: eid, optionId, factPath: `facts.${w.capability}`, sourceUrl: `https://acceptance.invalid/${eid}`,
            observedAt: "2026-10-09T11:00:00Z", reviewStatus: "REVIEWED", assertion: "SUPPORTS_CLAIM",
            conditions: ["Fictional preference evidence, not a vendor claim."] });
        }
        return { ...w, outcome, earnedPoints: outcome === "AVAILABLE" ? w.weight : 0, evidenceIds };
      });
      score = { lowerBound, upperBound, unknownWeight, contributions };
    }
    return { optionId, providerId: `fictional-${id}`, product: `Fictional ${id} identity option`, plan: "Acceptance-only fictional plan",
      region: "Fictional exact scope", deployment: id === "alpha" ? "MANAGED" : "SELF_HOSTED",
      hardVerdict: c.expected.verdicts[index], findings, score };
  });
  const limitationRoutes = policy.inputRoutes.filter(r => r.routes.includes("LIMITATION"));
  const limitations = limitationRoutes.map(r => ({ profilePath: r.profilePath, reasonCode: "PLANNING_ONLY",
    blocksRecommendation: c.expected.blockingLimitations?.includes(r.profilePath) ?? false,
    explanation: "Scoped planning/capability evidence is not verified deployment, exact cost, lifecycle or certification." }));
  const pattern = c.expected.pattern;
  return {
    schemaVersion: 1, scope: "DECLARED_IDENTITY_DECISION_WITH_CAPABILITY_ONLY_SCORING",
    binding: { workspaceId: "00000000-0000-4000-8000-000000000001", assessmentId: "00000000-0000-4000-8000-000000000002",
      assessmentVersion: 1, profileSchemaVersion: 6, profileSha256: hash(profile), catalogBasis: suite.basis,
      catalogVersion: `${suite.catalogVersion}.${c.id}`, catalogSha256: hash([c.id, c.checks, c.preferences]), snapshotId: null,
      policyVersion: policy.policyVersion, weightsSha256: hash({ mode: weights.length ? "EXPLICIT" : "NONE", values: weights }), evaluatedAt: suite.evaluatedAt },
    status: c.expected.status, weights: { mode: weights.length ? "EXPLICIT" : "NONE", values: weights }, candidates,
    shortlist: c.expected.shortlist.map(fixtureId), rankGroups: c.expected.ranks.map((ids, i) => ({ rank: i + 1, optionIds: ids.map(fixtureId) })), evidence,
    architecture: { status: pattern ? "CONDITIONAL_ADVICE" : "NEEDS_INFORMATION", apiProtection: profile.protocols.oauth2ProtectedApis === "NOT_REQUIRED" ? "NOT_REQUIRED" : "REQUIRED_CONDITIONAL",
      patterns: policy.architecture.patterns.map(patternId => {
        const client = patternId === "NATIVE_CODE_PKCE" ? "NATIVE_MOBILE" : patternId === "M2M_CLIENT_CREDENTIALS" ? "MACHINE_TO_MACHINE" : "BROWSER";
        const selected = profile.application.clients.includes(client);
        return { patternId, disposition: !selected ? "NOT_APPLICABLE" : patternId === pattern ? "RECOMMENDED" : pattern ? "ALTERNATIVE" : "UNRESOLVED",
          pros: ["Conditional fit for the declared client/design scope."], cons: ["Operational and credential lifecycle responsibilities remain."],
          conditions: ["Validate concrete settings before deployment."], references: ["https://patterns.invalid/conditional-design"] };
      }) },
    limitations, followUps: ["Resolve unknown evidence and validate proposed configuration before deployment."],
    configurationVerified: false, complianceVerified: false
  };
}
function exactSet(actual, expected, message) {
  assert.equal(new Set(actual).size, actual.length, message);
  assert.deepEqual([...actual].sort(), [...expected].sort(), message);
}
export function assertDecisionSpec(result, binding, profile) {
  assert.equal(schemaValidate(result), true, ajv.errorsText(schemaValidate.errors));
  assert.deepEqual(result.binding, binding, "Result must replay the exact originating profile/catalog/policy/time binding.");
  assert.equal(result.binding.profileSha256, hash(profile));
  assert.equal(result.binding.weightsSha256, hash(result.weights), "Weights must replay their exact originating request binding.");
  const ids = result.candidates.map(c => c.optionId); exactSet(ids, ids, "Option IDs must be unique.");
  const evidence = new Map(result.evidence.map(e => [e.id, e]));
  assert.equal(evidence.size, result.evidence.length, "Evidence IDs must be unique.");
  const at = Date.parse(binding.evaluatedAt);
  for (const e of result.evidence) {
    assert(ids.includes(e.optionId));
    const url = new URL(e.sourceUrl); assert.equal(url.username, ""); assert.equal(url.password, ""); assert.equal(url.hash, "");
    if (binding.catalogBasis === "SYNTHETIC_ACCEPTANCE") assert(url.hostname.endsWith(".invalid"));
  }
  function checkEvidence(refs, optionId, dependency, usable) {
    for (const id of refs) {
      const e = evidence.get(id); assert(e, "Unknown evidence reference."); assert.equal(e.optionId, optionId, "Cross-option borrowing.");
      assert(e.factPath === dependency || e.factPath.startsWith(dependency + "."), "Wrong fact dependency.");
      if (usable) {
        assert.equal(e.reviewStatus, "REVIEWED"); assert.equal(e.assertion, "SUPPORTS_CLAIM");
        const observed = Date.parse(e.observedAt); assert(observed <= at && at - observed <= policy.evidenceFreshnessDays * 86400000, "Evidence is stale/future.");
      }
    }
    if (usable && dependency) assert(refs.length > 0, "Positive or negative known claim requires evidence.");
  }
  const preferred = Object.entries(capabilityPaths).filter(([, path]) => get(profile, path) === "PREFERRED").map(([capability]) => capability);
  const weights = result.weights.values;
  if (result.weights.mode === "EXPLICIT") {
    exactSet(weights.map(w => w.capability), preferred, "Weights must cover exactly explicitly preferred capabilities.");
    assert.equal(weights.reduce((sum, w) => sum + w.weight, 0), 100);
  }
  for (const candidate of result.candidates) {
    exactSet(candidate.findings.map(f => f.profilePath), policy.inputRoutes.map(r => r.profilePath), "Every declared input must have one route.");
    for (const f of candidate.findings) {
      const route = policy.inputRoutes.find(r => r.profilePath === f.profilePath);
      assert.equal(f.criticality, criticality(profile, route), "Criticality cannot be weakened in output.");
      const usable = ["PASS", "FAIL"].includes(f.outcome);
      if (route.factDependency) checkEvidence(f.evidenceIds, candidate.optionId, route.factDependency, usable);
      else assert.equal(f.evidenceIds.length, 0);
      if (usable) assert(["SUPPORTED", "UNSUPPORTED"].includes(f.reasonCode));
    }
    const hard = candidate.findings.filter(f => {
      const routes = policy.inputRoutes.find(r => r.profilePath === f.profilePath).routes;
      return (routes.includes("HARD") || routes.includes("CONTEXT")) && blocking(f);
    });
    assert(hard.every(f => f.outcome !== "NOT_APPLIED"), "An active hard/context requirement cannot be silently skipped.");
    const verdict = hard.some(f => f.outcome === "FAIL") ? "EXCLUDED" : hard.some(f => f.outcome === "UNKNOWN") ? "UNRESOLVED" : "ELIGIBLE";
    assert.equal(candidate.hardVerdict, verdict, "Confirmed failures precede unknowns; neither may receive eligibility.");
    if (verdict !== "ELIGIBLE" || result.weights.mode === "NONE") assert.equal(candidate.score, null);
    else {
      const score = candidate.score; assert(score, "Explicit weighted eligible options require disclosed scores.");
      exactSet(score.contributions.map(c => c.capability), weights.map(w => w.capability), "Missing/extra contribution.");
      for (const c of score.contributions) {
        assert.equal(c.weight, weights.find(w => w.capability === c.capability).weight);
        assert.equal(c.earnedPoints, c.outcome === "AVAILABLE" ? c.weight : 0);
        checkEvidence(c.evidenceIds, candidate.optionId, `facts.${c.capability}`, c.outcome !== "UNKNOWN");
      }
      const lower = score.contributions.reduce((sum, c) => sum + c.earnedPoints, 0);
      const unknown = score.contributions.filter(c => c.outcome === "UNKNOWN").reduce((sum, c) => sum + c.weight, 0);
      assert.equal(score.lowerBound, lower); assert.equal(score.unknownWeight, unknown); assert.equal(score.upperBound, lower + unknown);
    }
  }
  const eligible = result.candidates.filter(c => c.hardVerdict === "ELIGIBLE");
  exactSet(result.shortlist, eligible.map(c => c.optionId), "Shortlist contains exactly eligible options, not a silent winner.");
  for (const route of policy.inputRoutes.filter(r => r.routes.includes("LIMITATION")))
    assert(result.limitations.some(l => l.profilePath === route.profilePath), "Planning/evidence limitation cannot be omitted.");
  for (const l of result.limitations) assert(policy.inputRoutes.some(r => r.profilePath === l.profilePath));
  for (const path of ["security.assurance", "security.complianceTargets"])
    if (path === "security.assurance" ? ["ELEVATED", "HIGH", "UNKNOWN"].includes(profile.security.assurance)
      : profile.security.complianceScopeStatus !== "NONE_IDENTIFIED")
      assert(result.limitations.some(l => l.profilePath === path && l.blocksRecommendation), "Unproved assurance/compliance cannot be cleared.");
  const limited = result.limitations.some(l => l.blocksRecommendation);
  const rankable = eligible.length > 0 && !limited && result.weights.mode === "EXPLICIT" && eligible.every(c => c.score.unknownWeight === 0);
  const groups = [];
  if (rankable) for (const c of eligible.toSorted((a, b) => b.score.lowerBound - a.score.lowerBound || a.optionId.localeCompare(b.optionId))) {
    const last = groups.at(-1);
    if (last?.score === c.score.lowerBound) last.optionIds.push(c.optionId);
    else groups.push({ rank: groups.length + 1, score: c.score.lowerBound, optionIds: [c.optionId] });
  }
  assert.deepEqual(result.rankGroups, groups.map(({ score: _score, ...group }) => group), "Rank groups require complete scores and shared ties.");
  const status = eligible.length ? limited ? "NEEDS_INFORMATION" : rankable ? "RANKED_SHORTLIST" : "UNRANKED_SHORTLIST"
    : result.candidates.some(c => c.hardVerdict === "UNRESOLVED") ? "NEEDS_INFORMATION" : "NO_ELIGIBLE_OPTIONS";
  assert.equal(result.status, status);
  assert.equal(result.architecture.status, eligible.length && !limited ? "CONDITIONAL_ADVICE" : "NEEDS_INFORMATION");
  const apiRequirement = profile.protocols.oauth2ProtectedApis;
  assert.equal(result.architecture.apiProtection, apiRequirement === "REQUIRED" ? "REQUIRED_CONDITIONAL"
    : apiRequirement === "NOT_REQUIRED" ? "NOT_REQUIRED" : "NEEDS_INFORMATION", "Browser/session advice cannot clear API requirements.");
  exactSet(result.architecture.patterns.map(p => p.patternId), policy.architecture.patterns, "All five alternatives must retain distinct outcomes.");
  for (const p of result.architecture.patterns) {
    const client = p.patternId === "NATIVE_CODE_PKCE" ? "NATIVE_MOBILE" : p.patternId === "M2M_CLIENT_CREDENTIALS" ? "MACHINE_TO_MACHINE" : "BROWSER";
    const selected = profile.application.clients.includes(client);
    if (!selected) assert.equal(p.disposition, "NOT_APPLICABLE");
    else assert.notEqual(p.disposition, "NOT_APPLICABLE");
    if (p.disposition === "RECOMMENDED") {
      assert.equal(result.architecture.status, "CONDITIONAL_ADVICE");
      assert(p.pros.length && p.cons.length && p.conditions.length && p.references.length, "Advice must retain pros/cons/conditions/sources.");
      assert(selected);
    }
  }
}
