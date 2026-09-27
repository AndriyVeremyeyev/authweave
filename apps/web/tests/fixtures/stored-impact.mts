export function storedImpactFixture(proposalId: string, proposalVersion: number, digest: string) {
  return {
    reportId: "90000000-0000-4000-8000-000000000003", reportNumber: 7,
    proposalId, proposalVersion, proposalSha256: digest, reportSchemaVersion: 1,
    canonicalizationVersion: "catalog-draft-canonical-json-1", reportSha256: "b".repeat(64),
    recordedAt: "2026-09-23T12:10:00Z",
    report: {
      scope: "CATALOG_PROFILE_SCENARIO_IMPACT", analysisBasis: "ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS",
      proposalId, proposalSha256: digest, storedProposalVersion: proposalVersion,
      storedRequestDigestVerified: true, evaluatedAt: "2026-09-23T12:09:00Z",
      status: "ANALYZED", policyVersion: "catalog-scenario-impact-1",
      ruleVersion: "assertion-claim-rules-1", caseSetVersion: "catalog-profile-scenarios-1",
      caseSetSha256: "c".repeat(64), impactAnalysisPerformed: true, hypotheticalEvaluationPerformed: true,
      coverageComplete: false, baselineVerified: false, sourceVerificationPerformed: false,
      approvalGranted: false, writesPerformed: false, evaluationReady: false, recommendationReady: false,
      changePreview: { proposalId, proposalSha256: digest, proposalState: "PROPOSED",
        baselineVerified: false, sourceVerificationPerformed: false, approvalGranted: false,
        writesPerformed: false, evaluationReady: false, impactAnalysisPerformed: false },
      scenarioDefinitions: ["b2b-saas", "public-sector-portal", "internal-workforce"].map(id =>
        ({ id, description: `Synthetic ${id}` })),
      scenarios: [{ scenarioId: "b2b-saas", optionId: "example-managed-eu", scopeChanged: false,
        conditionalStatusChanged: true, affectedFactPaths: ["facts.SCIM"],
        changedCheckIds: ["provisioning.scim|facts.SCIM"],
        before: { conditionalStatus: "INDETERMINATE" },
        after: { conditionalStatus: "WOULD_VIOLATE_CHECKED_REQUIREMENTS" } }],
      uncoveredChanges: [{ optionId: "example-managed-eu", factPath: "facts.OTHER",
        reason: "NO_SCENARIO_DEPENDENCY" }],
    },
  };
}
