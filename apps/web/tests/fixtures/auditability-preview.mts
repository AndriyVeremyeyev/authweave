import { auditCriteria, type AuditabilityValues } from "../../src/lib/assessment/auditability.ts";
import { auditabilityDeferredBoundaries, type AuditabilityCheck, type AuditabilityEvidence,
  type AuditabilityCandidate } from "../../src/lib/assessment/auditability-preview.ts";

export const auditabilityWorkspaceId = "70000000-0000-4000-8000-000000000001";
export const auditabilityAssessmentId = "80000000-0000-4000-8000-000000000001";
export const auditabilityInput: AuditabilityValues = { criticality: "REQUIRED",
  selectedCriteria: auditCriteria.map(c => c.key), minimumRetentionDays: 30 };
export const auditabilityBinding = { workspaceId: auditabilityWorkspaceId, assessmentId: auditabilityAssessmentId,
  expectedVersion: 2, values: auditabilityInput };

// Explicit fictional expectations, independent of the response guard. Core HTTP samples
// separately exercise cross-language parity using the actual scoped fixture catalog.
export function auditabilityFixture(values: AuditabilityValues = auditabilityInput) {
  return {
    workspaceId: auditabilityWorkspaceId, assessmentId: auditabilityAssessmentId, assessmentVersion: 2,
    baseCatalogVersion: "synthetic-2026-09-12.4", evidenceVersion: "synthetic-auditability-2026-09-12.1",
    catalogKind: "SYNTHETIC", evaluatedAt: "2026-10-02T12:00:00Z", criticality: values.criticality,
    requirements: { selectedCriteria: [...values.selectedCriteria], minimumRetentionDays: values.minimumRetentionDays },
    policyVersion: "auditability-capability-preflight-1", scope: "SYNTHETIC_AUDITABILITY_CAPABILITY_PREFLIGHT",
    checkedPaths: ["security.auditability", "security.auditabilityRequirements"],
    sourceVerificationPerformed: false, recommendationReady: false,
    candidates: ["fictional-complete", "fictional-no-scim", "fictional-unreviewed"].map((optionId, i) => {
      const scope = { optionId, plan: "Synthetic demo plan", region: "Synthetic region", configuration: "Synthetic logging enabled" };
      const evidence: AuditabilityEvidence[] = auditCriteria.filter(c => i !== 1 || c.key !== "PROVISIONING_CHANGE_EVENTS").map(c => ({
        scope: { ...scope }, emitter: "IDENTITY_PROVIDER", criterion: c.key,
        support: i === 1 && c.key === "AUDIT_LOG_EXPORT" ? "UNSUPPORTED" : "SUPPORTED",
        documentedMinimumRetentionDays: c.key === "AUDIT_LOG_RETENTION" ? i === 1 ? 7 : 90 : null,
        evidenceStatus: i === 2 ? "UNREVIEWED" : "REVIEWED",
        sourceUrl: `https://${optionId}.example.invalid/logging`, observedAt: "2026-09-12T12:00:00Z",
      }));
      const checks: AuditabilityCheck[] = auditCriteria.map(c => {
        const scopeReason = values.criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED" :
          values.criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT" : values.criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" :
            values.criticality === "FORBIDDEN" ? "AUDIT_INTENT_UNCLEAR" : values.selectedCriteria.length === 0 ? "AUDIT_SCOPE_UNKNOWN" :
              !values.selectedCriteria.includes(c.key) ? "CRITERION_NOT_SELECTED" : null;
        if (scopeReason) return { criterion: c.key, reasonCode: scopeReason,
          outcome: ["PREFERENCE_NOT_SCORED", "NO_REQUIREMENT", "CRITERION_NOT_SELECTED"].includes(scopeReason) ? "NOT_APPLIED" : "UNKNOWN",
          documentedMinimumRetentionDays: null };
        if (i === 2) return { criterion: c.key, reasonCode: "EVIDENCE_UNREVIEWED", outcome: "UNKNOWN", documentedMinimumRetentionDays: null };
        if (i === 1 && c.key === "PROVISIONING_CHANGE_EVENTS") return { criterion: c.key,
          reasonCode: "EVIDENCE_MISSING", outcome: "UNKNOWN", documentedMinimumRetentionDays: null };
        if (i === 1 && c.key === "AUDIT_LOG_EXPORT") return { criterion: c.key,
          reasonCode: "CAPABILITY_UNAVAILABLE", outcome: "FAIL", documentedMinimumRetentionDays: null };
        if (c.key === "AUDIT_LOG_RETENTION") {
          const minimum = i === 1 ? 7 : 90, passes = minimum >= values.minimumRetentionDays!;
          return { criterion: c.key, reasonCode: passes ? "RETENTION_MEETS_MINIMUM" : "RETENTION_BELOW_MINIMUM",
            outcome: passes ? "PASS" : "FAIL", documentedMinimumRetentionDays: minimum };
        }
        return { criterion: c.key, reasonCode: "DOCUMENTED_CAPABILITY_AVAILABLE", outcome: "PASS", documentedMinimumRetentionDays: null };
      });
      return { displayName: ["Fictional Complete", "Fictional No SCIM", "Fictional Unreviewed"][i], evidence,
        analysis: { optionScope: scope, criticality: values.criticality,
          requirements: { selectedCriteria: [...values.selectedCriteria], minimumRetentionDays: values.minimumRetentionDays },
          evaluatedAt: "2026-10-02T12:00:00Z", checks, status: status(checks),
          policyVersion: "auditability-capability-preflight-1", analysisBasis: "SYNTHETIC_SCOPED_PROVIDER_CAPABILITY_EVIDENCE",
          deferredBoundaries: [...auditabilityDeferredBoundaries], configurationVerified: false,
          complianceVerified: false, recommendationReady: false } };
    }),
  };
}
export type AuditabilityWireFixture = ReturnType<typeof auditabilityFixture>;
function status(checks: AuditabilityCheck[]): AuditabilityCandidate["status"] {
  return checks.some(c => c.outcome === "FAIL") ? "DOES_NOT_MATCH" : checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" :
    checks.some(c => c.outcome === "PASS") ? "MATCHES_CHECKED_REQUIREMENTS" : "NOT_APPLIED";
}
export function replaceAuditabilityCheck(fixture: AuditabilityWireFixture, candidate: number,
  index: number, reasonCode: AuditabilityCheck["reasonCode"], outcome: AuditabilityCheck["outcome"], duration: number | null = null) {
  const analysis = fixture.candidates[candidate].analysis;
  analysis.checks[index] = { criterion: auditCriteria[index].key, reasonCode, outcome, documentedMinimumRetentionDays: duration };
  analysis.status = status(analysis.checks);
}
