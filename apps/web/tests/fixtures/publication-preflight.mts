import type { PublicationReference } from "../../src/lib/catalog/publication-preflight.ts";

export const publicationAt = "2026-10-07T12:00:00.123456789Z";
export function publicationBinding(bootstrap = false): PublicationReference {
  return { mode: bootstrap ? "CURATED_BOOTSTRAP" : "PROPOSAL_APPROVAL", inputId: "90000000-0000-4000-8000-000000000007",
    inputVersion: bootstrap ? null : 3, inputSha256: "a".repeat(64) };
}
/** Synthetic display fixture only; neither evidence nor a native Core calculation. */
export function publicationFixture(bootstrap = false) {
  return { ...publicationBinding(bootstrap), schemaVersion: 1, scope: "CATALOG_PUBLICATION_PREFLIGHT_REVIEW",
    policyVersion: "catalog-publication-preflight-12", status: "BLOCKED", evaluatedAt: publicationAt,
    reviewThroughNumber: bootstrap ? 0 : 12,
    facts: { total: 9, unobserved: 0, supporting: 9, contradicting: 0, insufficient: 0, stale: 0, future: 0,
      allFactsHaveSupportingObservation: true },
    planning: { evaluatedAt: publicationAt, analysisSha256: "b".repeat(64), checkedDimensions: 136,
      structuralVerificationGaps: 40, planningVerificationGaps: 22,
      regressions: [{ family: "ARCHITECTURE_CONFIGURATION", checkedCases: 252 }, { family: "PROVISIONING_LIFECYCLE", checkedCases: 2016 },
        { family: "OPERATIONS_PLANNING", checkedCases: 140 }, { family: "ASSURANCE_COMPLIANCE", checkedCases: 36 }],
      status: "INCOMPLETE", policyVersion: "catalog-profile-planning-coverage-1" } as {
        evaluatedAt: string; analysisSha256: string; checkedDimensions: number; structuralVerificationGaps: number; planningVerificationGaps: number;
        regressions: { family: string; checkedCases: number }[]; status: string; policyVersion: string;
      } | null,
    blockers: [...(bootstrap ? [] : ["BASELINE_REFERENCE_MISSING", "BASELINE_AUTHORITY_UNAVAILABLE"]),
      "IMPACT_COVERAGE_INCOMPLETE", "CURATOR_AUTHORIZATION_NOT_PERFORMED", "PUBLICATION_WORKFLOW_UNAVAILABLE"],
    coverageComplete: false, baselineVerified: false, sourceVerificationPerformed: false, approvalGranted: false,
    publicationReady: false, evaluationReady: false, writesPerformed: false };
}
