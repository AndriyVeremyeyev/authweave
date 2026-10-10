import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertAssessmentResult } from "../tests/helpers/assessment-decision-result-spec.mjs";
import { orderedHash as hash } from "../tests/helpers/publication-decision-coverage-spec.mjs";
import { resultSummaryFromCore } from "../../../apps/web/src/lib/assessment/decision-results.ts";
import { resultAdviceFromCore } from "../../../apps/web/src/lib/assessment/decision-advice.ts";
import { sensitivityFromCore } from "../../../apps/web/src/lib/assessment/decision-sensitivity.ts";

assert.equal(process.argv.length, 3, "Pass actual isolated Core assessment result samples.");
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result.v1.schema.json");
const summarySchema = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result-summary.v1.schema.json");
const adviceSchema = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result-advice.v1.schema.json");
const sensitivitySchema = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-sensitivity.v1.schema.json");
function verifySensitivity(sample) {
  const { receipt, comparisonRequest: input, sensitivity: view, summary, advice } = sample, r = receipt.result;
  assert(sensitivitySchema(view), ajv.errorsText(sensitivitySchema.errors));
  const actual = sensitivityFromCore(view, r.workspaceId, r.assessmentId, input, advice);
  const before = { weights: r.request.weights, status: r.decision.status, shortlist: r.decision.shortlist,
    candidates: r.decision.candidates, rankGroups: r.decision.rankGroups };
  assert.deepEqual(actual.summary, summary); assert.deepEqual(actual.before, before);
  const after = structuredClone(before); after.weights = input.weights;
  for (const c of after.candidates) if (c.score) {
    for (const x of c.score.contributions) { x.weight = input.weights.values.find(w => w.capability === x.capability).weight;
      x.earnedPoints = x.outcome === "AVAILABLE" ? x.weight : 0; }
    c.score.lowerBound = c.score.contributions.reduce((n, x) => n + x.earnedPoints, 0);
    c.score.unknownWeight = c.score.contributions.filter(x => x.outcome === "UNKNOWN").reduce((n, x) => n + x.weight, 0);
    c.score.upperBound = c.score.lowerBound + c.score.unknownWeight;
  }
  const eligible = after.candidates.filter(c => c.hardChecks.hardVerdict === "ELIGIBLE");
  const rankable = eligible.length && after.weights.mode === "EXPLICIT" && eligible.every(c => c.score.unknownWeight === 0);
  after.status = eligible.length ? rankable ? "RANKED_SHORTLIST" : "UNRANKED_SHORTLIST"
    : after.candidates.some(c => c.hardChecks.hardVerdict === "UNRESOLVED") ? "NEEDS_INFORMATION" : "NO_ELIGIBLE_OPTIONS";
  after.rankGroups = rankable ? [...new Set(eligible.map(c => c.score.lowerBound))].sort((a, b) => b - a)
    .map((points, i) => ({ rank: i + 1, optionIds: eligible.filter(c => c.score.lowerBound === points).map(c => c.hardChecks.optionId) })) : [];
  assert.deepEqual(actual.after, after); assert.equal(actual.writesPerformed, false);
}
function verifyAdvice(sample) {
  const { receipt, advice, summary } = sample, r = receipt.result;
  assert(adviceSchema(advice), ajv.errorsText(adviceSchema.errors));
  const actual = resultAdviceFromCore(advice, r.workspaceId, r.assessmentId, receipt.reference);
  assert.deepEqual(actual, { scope: "VERIFIED_ASSESSMENT_DECISION_ADVICE", summary,
    candidates: r.decision.candidates, rankGroups: r.decision.rankGroups, architecture: r.decision.architecture,
    limitations: r.decision.limitations.map(({ declaredValue, ...l }) => l), followUps: r.decision.followUps });
}
function verifySummary(sample) {
  const { receipt, summary } = sample, r = receipt.result;
  assert(summarySchema(summary), ajv.errorsText(summarySchema.errors));
  const actual = resultSummaryFromCore(summary, r.workspaceId, r.assessmentId, receipt.reference);
  assert.deepEqual(actual, { scope: "VERIFIED_ASSESSMENT_DECISION_SUMMARY", workspaceId: r.workspaceId, assessmentId: r.assessmentId,
    item: { reference: receipt.reference, assessmentVersion: r.request.expectedAssessmentVersion, catalog: r.request.catalog,
      previousResult: r.request.previousResult, recordedAt: receipt.recordedAt }, evaluatedAt: r.evaluatedAt,
    profileSchemaVersion: r.profileSchemaVersion, profileSha256: r.profileSha256, policySha256: r.policySha256,
    weights: r.request.weights, status: r.decision.status, shortlist: r.decision.shortlist,
    candidates: r.decision.candidates.map(({ hardChecks: h, score }) => ({ optionId: h.optionId, product: h.product,
      plan: h.plan, region: h.region, deployment: h.deployment, hardVerdict: h.hardVerdict,
      score: score === null ? null : { lowerBound: score.lowerBound, upperBound: score.upperBound, unknownWeight: score.unknownWeight } })),
    verificationGapCount: r.catalog.verificationGaps.length, historicalReplayVerified: true,
    externalSourceVerificationPerformed: false, configurationVerified: false, complianceVerified: false, decisionApproved: false });
}
const samples = JSON.parse(await readFile(process.argv[2], "utf8")); assert(samples.length >= 3);
for (const sample of samples) {
  assert(validate(sample.receipt), ajv.errorsText(validate.errors));
  const stored = ajv.getSchema(`https://authweave.dev/contracts/application-identity-profile.v${sample.receipt.result.profileSchemaVersion}.schema.json`);
  assert(stored(sample.receipt.result.profile), ajv.errorsText(stored.errors)); assertAssessmentResult(sample);
  verifySummary(sample);
  verifyAdvice(sample);
  verifySensitivity(sample);
  for (const change of [s => s.sensitivity.after.candidates[0].hardChecks.product = "Forged",
    s => s.sensitivity.summary.evaluatedAt = "2026-01-01T00:00:00Z", s => s.sensitivity.summary.profileSha256 = "0".repeat(64),
    s => s.sensitivity.after.rankGroups.push({ rank: 99, optionIds: ["forged"] }), s => s.sensitivity.writesPerformed = true]) {
    const forged = structuredClone(sample); change(forged); assert.throws(() => verifySensitivity(forged));
  }
  for (const change of [s => s.advice.candidates[0].hardChecks.findings[0].reasonCode = "FORGED",
    s => s.advice.architecture.patterns[0].choice.conditions.push("Forged condition"),
    s => s.advice.summary.item.reference.resultSha256 = "0".repeat(64),
    s => s.advice.limitations[0].declaredValue = "private-user-value",
    s => s.advice.candidates.flatMap(c => c.hardChecks.findings).find(f => f.evidence !== null).evidence = null,
    s => s.advice.followUps.push("Forged follow-up")]) {
    const forged = structuredClone(sample); change(forged); assert.throws(() => verifyAdvice(forged));
  }
  for (const change of [s => s.summary.item.catalog.catalogVersion = "forged-label", s => s.summary.evaluatedAt = "2026-01-01T00:00:00Z",
    s => s.summary.item.reference.resultSha256 = "0".repeat(64), s => s.summary.decisionApproved = true,
    s => s.summary.profileSha256 = "0".repeat(64), s => s.summary.candidates[0].product = "Forged product"]) {
    const forged = structuredClone(sample); change(forged); assert.throws(() => verifySummary(forged));
  }
  for (const change of [s => s.receipt.result.policy.componentVersions.hardChecks = "wrong-kernel", s => s.receipt.result.profileSha256 = "0".repeat(64),
    s => s.receipt.result.policySha256 = "0".repeat(64), s => s.receipt.result.requestSha256 = "0".repeat(64),
    s => s.receipt.result.catalog.reference.snapshotSha256 = "0".repeat(64), s => s.receipt.result.catalog.verificationGaps.pop(),
    s => s.receipt.result.decision.binding.inputs.weightsSha256 = "0".repeat(64), s => s.receipt.result.decision.shortlist.push("forged-option"),
    s => s.receipt.result.decisionApproved = true]) {
    const forged = structuredClone(sample); change(forged); forged.receipt.reference.resultSha256 = hash(forged.receipt.result);
    assert.throws(() => assertAssessmentResult(forged));
  }
}
const successor = samples.find(s => s.receipt.reference.version === 2); assert(successor);
const parent = samples.find(s => s.receipt.reference.resultId === successor.receipt.result.request.previousResult.resultId); assert(parent);
assert.deepEqual(successor.receipt.result.request.previousResult, parent.receipt.reference);
assert.notEqual(successor.receipt.result.request.catalog.snapshotId, parent.receipt.result.request.catalog.snapshotId);
assert.notEqual(successor.receipt.result.profileSha256, parent.receipt.result.profileSha256);
assert(samples.some(s => s.receipt.result.profileSchemaVersion === 1));
assert(samples.some(s => s.decisionInputs.auditability === null)); assert(samples.some(s => s.decisionInputs.auditability !== null));
assert(samples.some(s => s.sensitivity.before.candidates.some((c, i) => c.score?.lowerBound !== s.sensitivity.after.candidates[i].score?.lowerBound)),
  "A real pinned eligible result must demonstrate changed score points, not only identical-weight replay.");
console.log(`Verified ${samples.length} actual assessment results and their exact BFF summaries/advice: owned profile/catalog pins, policy/weights/clock hashes, scoring/evidence invariants, allowlisted explanations and explicit result history.`);
