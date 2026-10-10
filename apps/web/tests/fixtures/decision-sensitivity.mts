import { rankedAdvice } from "./decision-advice.mts";
import { savedScoring, type ResultSensitivity, type SensitivityRequest } from "../../src/lib/assessment/decision-sensitivity.ts";
import type { ResultAdvice } from "../../src/lib/assessment/decision-advice.ts";

export function sensitivityBaseline(): ResultAdvice {
  const r = rankedAdvice();
  r.summary.weights.values = [{ capability: "SAML", weight: 70 }, { capability: "MFA", weight: 30 }];
  const first = r.candidates[0], second = structuredClone(first);
  second.hardChecks.optionId = "fictional-second"; second.hardChecks.product = "Fictional alternative";
  r.candidates.push(second); r.summary.shortlist.push(second.hardChecks.optionId);
  r.summary.candidates.push({ ...r.summary.candidates[0], optionId: second.hardChecks.optionId, product: second.hardChecks.product });
  r.candidates.forEach((c, i) => {
    const fact = c.score!.contributions[0];
    c.score = { lowerBound: i ? 30 : 70, upperBound: i ? 30 : 70, unknownWeight: 0,
      contributions: [{ ...fact, capability: "MFA", profilePath: "security.multiFactorAuthentication", weight: 30,
        outcome: i ? "AVAILABLE" : "UNAVAILABLE", earnedPoints: i ? 30 : 0 },
      { ...fact, capability: "SAML", weight: 70, outcome: i ? "UNAVAILABLE" : "AVAILABLE", earnedPoints: i ? 0 : 70 }] };
    r.summary.candidates[i].score = { lowerBound: c.score.lowerBound, upperBound: c.score.upperBound, unknownWeight: 0 };
  });
  r.rankGroups = [{ rank: 1, optionIds: [first.hardChecks.optionId] }, { rank: 2, optionIds: [second.hardChecks.optionId] }];
  return r;
}
export function sensitivityFixture(baseline = sensitivityBaseline(), saml = 20): { input: SensitivityRequest; value: ResultSensitivity; baseline: ResultAdvice } {
  const input: SensitivityRequest = { schemaVersion: 1, reference: baseline.summary.item.reference,
    weights: { mode: "EXPLICIT", values: [{ capability: "SAML", weight: saml }, { capability: "MFA", weight: 100 - saml }] } };
  const before = savedScoring(baseline), after = structuredClone(before); after.weights = input.weights;
  after.candidates.forEach(c => {
    if (!c.score) return;
    c.score.contributions.forEach(x => { x.weight = input.weights.values.find(w => w.capability === x.capability)!.weight;
      x.earnedPoints = x.outcome === "AVAILABLE" ? x.weight : 0; });
    c.score.lowerBound = c.score.contributions.reduce((n, x) => n + x.earnedPoints, 0);
    c.score.unknownWeight = c.score.contributions.filter(x => x.outcome === "UNKNOWN").reduce((n, x) => n + x.weight, 0);
    c.score.upperBound = c.score.lowerBound + c.score.unknownWeight;
  });
  const rankable = after.candidates.filter(c => c.score).every(c => c.score!.unknownWeight === 0);
  after.status = rankable ? "RANKED_SHORTLIST" : "UNRANKED_SHORTLIST";
  const points = [...new Set(after.candidates.flatMap(c => c.score ? [c.score.lowerBound] : []))].sort((a, b) => b - a);
  after.rankGroups = rankable ? points.map((points, i) => ({ rank: i + 1,
    optionIds: after.candidates.filter(c => c.score?.lowerBound === points).map(c => c.hardChecks.optionId) })) : [];
  return { input, baseline, value: { scope: "VERIFIED_ASSESSMENT_WEIGHT_SENSITIVITY", summary: baseline.summary, before, after, writesPerformed: false } };
}
