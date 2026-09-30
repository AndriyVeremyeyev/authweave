export function candidateEvidenceFixture(proposalId: string, proposalVersion: number,
  proposalSha256: string, catalogVersion = "synthetic-candidate") {
  return { proposalId, proposalVersion, proposalSha256, catalogVersion,
    evaluatedAt: "2026-09-30T12:00:00Z", policyVersion: "catalog-proposal-evidence-review-1",
    maxEvidenceAgeDays: 90, sourceVerificationPerformed: false, approvalGranted: false,
    writesPerformed: false, evaluationReady: false, factCount: 1,
    freshness: { current: 1, stale: 0, future: 0 }, offset: 0, nextOffset: null,
    items: [{ optionId: "example-managed-eu", path: "facts.OIDC",
      scope: { providerId: "example", product: "Example Identity", plan: "Example Enterprise",
        deployment: "MANAGED", region: "EU", configuration: "Synthetic pilot" },
      evidenceStatus: "UNREVIEWED", freshness: "CURRENT", conditions: ["Pilot configuration"],
      evidence: { sourceUrl: "https://docs.example.invalid/identity/plan",
        observedAt: "2026-09-12T12:00:00Z", summary: "Fictional claim only." } }] };
}
