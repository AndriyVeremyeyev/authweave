import type { ResultReference, ResultItem, ResultPage, ResultSummary } from "../../src/lib/assessment/decision-results.ts";
export const resultWorkspace = "70000000-0000-4000-8000-000000000001";
export const resultAssessment = "80000000-0000-4000-8000-000000000001";
export const firstResult: ResultReference = { resultId: "90000000-0000-4000-8000-000000000001", version: 1, resultSha256: "1".repeat(64) };
export const secondResult: ResultReference = { resultId: "90000000-0000-4000-8000-000000000002", version: 2, resultSha256: "2".repeat(64) };
export function resultItem(reference = firstResult): ResultItem {
  return { reference: structuredClone(reference), assessmentVersion: reference.version,
    catalog: { snapshotId: "a0000000-0000-4000-8000-000000000001", catalogVersion: "fictional-catalog", snapshotSha256: "a".repeat(64) },
    previousResult: reference.version === 1 ? null : structuredClone(firstResult), recordedAt: "2026-10-09T15:00:00Z" };
}
export function resultPage(): ResultPage { return { scope: "OWNED_ASSESSMENT_RESULT_INDEX", workspaceId: resultWorkspace,
  assessmentId: resultAssessment, items: [resultItem(secondResult), resultItem()], nextBefore: null, historicalReplayVerified: false }; }
export function resultSummary(): ResultSummary {
  return { scope: "VERIFIED_ASSESSMENT_DECISION_SUMMARY", workspaceId: resultWorkspace, assessmentId: resultAssessment, item: resultItem(),
    evaluatedAt: "2026-10-09T14:59:59Z", profileSchemaVersion: 6, profileSha256: "b".repeat(64), policySha256: "c".repeat(64),
    weights: { mode: "EXPLICIT", values: [{ capability: "SAML", weight: 100 }] }, status: "NEEDS_INFORMATION", shortlist: [],
    candidates: [{ optionId: "fictional-option", product: "Fictional identity product", plan: "Example plan", region: "Example region",
      deployment: "MANAGED", hardVerdict: "UNRESOLVED", score: null }], verificationGapCount: 22, historicalReplayVerified: true,
    externalSourceVerificationPerformed: false, configurationVerified: false, complianceVerified: false, decisionApproved: false };
}
