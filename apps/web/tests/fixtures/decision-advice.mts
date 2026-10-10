import { resultSummary } from "./decision-results.mts";
import type { AdviceChoice, ResultAdvice } from "../../src/lib/assessment/decision-advice.ts";
import { prerequisiteIds, type ArchitecturePatternId } from "../../src/lib/assessment/architecture-prerequisites.ts";

export function resultAdvice(): ResultAdvice {
  const summary = resultSummary();
  const evidence = { claimSha256: "d".repeat(64), sourceAssertion: "SOURCE_SUPPORTS_CLAIM" as const,
    sourceUrl: "https://source.example.invalid/scim", observedAt: "2026-10-01T10:00:00Z", conditions: ["Only the exact fictional plan"], documentedMinimumRetentionDays: null };
  const choice = (id: string): AdviceChoice => ({ id, disposition: "UNRESOLVED", reasonCode: "PROFILE_CONTEXT_UNRESOLVED",
    conditionalOptionIds: [], optionChecks: [{ optionId: summary.candidates[0].optionId, match: "UNRESOLVED",
      capabilities: [{ capability: "SCIM", usable: false, reasonCode: "REQUIRED_UNKNOWN", evidence }] }],
    pros: ["Explicit scoped design"], cons: ["Needs lifecycle verification"], conditions: ["Test offboarding and access revocation"], references: ["https://reference.example.invalid/design"] });
  const { score: ignored, ...hard } = summary.candidates[0]; void ignored;
  return { scope: "VERIFIED_ASSESSMENT_DECISION_ADVICE", summary,
    candidates: [{ hardChecks: { ...hard, providerId: "fictional-provider", configuration: "Dedicated fictional realm",
      findings: [{ checkId: "SCIM_REQUIRED", profilePath: "protocols.scim", factPath: "facts.SCIM", criticality: "REQUIRED", outcome: "UNKNOWN", reasonCode: "REQUIRED_UNKNOWN", evidence }] }, score: null }],
    rankGroups: [], architecture: { status: "NEEDS_INFORMATION", basis: "CONDITIONAL_DESIGN_ADVICE_WITH_UNAUTHENTICATED_SOURCE_HYPOTHESES",
      patterns: Object.entries(prerequisiteIds).map(([id, ids]) => ({ choice: choice(id), prerequisites: {
        patternId: id as ArchitecturePatternId, clientScope: "UNKNOWN", checks: ids.map(prerequisiteId => ({ prerequisiteId, outcome: "UNKNOWN", reasonCode: "CLIENT_SCOPE_UNKNOWN" })),
        status: "NEEDS_INFORMATION", policyVersion: "architecture-prerequisites-1", analysisBasis: "UNVERIFIED_DESIGN_DECLARATIONS",
        configurationVerified: false, providerCompatibilityVerified: false, recommendationReady: false } })),
      apiProtection: { status: "NEEDS_INFORMATION", optionChecks: [], conditions: ["Verify issuer, audience and API authorization"] },
      provisioning: [choice("SCIM_PUSH"), { ...choice("JIT_LOGIN"), disposition: "NOT_APPLICABLE", reasonCode: "REQUIRED_SCIM_CANNOT_BE_REPLACED_BY_JIT" }, choice("SCIM_AND_JIT")] },
    limitations: [{ profilePath: null, reasonCode: "SOURCE_AUTHORITY_UNVERIFIED", blocksDeploymentRecommendation: true, explanation: "External source authority is not authenticated." },
      { profilePath: "operations.cost", reasonCode: "OPERATIONS_AND_COST_PLANNING_ONLY", blocksDeploymentRecommendation: true, explanation: "Confirm workload and cost before deployment." }],
    followUps: ["Obtain manual source review for the exact selected claims."] };
}

export function rankedAdvice(): ResultAdvice {
  const r = resultAdvice();
  r.summary.status = "RANKED_SHORTLIST"; r.summary.shortlist = [r.summary.candidates[0].optionId];
  r.summary.candidates[0].hardVerdict = "ELIGIBLE";
  r.summary.candidates[0].score = { lowerBound: 100, upperBound: 100, unknownWeight: 0 };
  r.candidates[0].hardChecks.hardVerdict = "ELIGIBLE"; r.candidates[0].hardChecks.findings[0].outcome = "PASS";
  r.candidates[0].score = { ...r.summary.candidates[0].score, contributions: [{ capability: "SAML", profilePath: "protocols.saml", weight: 100,
    earnedPoints: 100, outcome: "AVAILABLE", reasonCode: "SUPPORTED", evidence: r.candidates[0].hardChecks.findings[0].evidence }] };
  r.rankGroups = [{ rank: 1, optionIds: [...r.summary.shortlist] }]; return r;
}
