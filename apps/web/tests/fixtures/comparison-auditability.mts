import type { ComparisonFinding } from "../../src/lib/assessment/comparison.ts";
import type { AuditabilityValues } from "../../src/lib/assessment/auditability.ts";
import { auditabilityFixture } from "./auditability-preview.mts";

export const noAuditRequirement: AuditabilityValues = { criticality: "NOT_REQUIRED", selectedCriteria: [], minimumRetentionDays: null };
type Seed = { workspaceId: string; assessmentId: string; assessmentVersion: number; catalogVersion: string; evaluatedAt: string;
  candidates: { optionId: string; displayName: string; plan: string; region: string;
    exclusionReasons: ComparisonFinding[]; informationGaps: ComparisonFinding[]; hardVerdict: string }[] };

// Declared fictional checks come from the independent audit fixture, never the production guard.
export function comparisonAuditFixture<T extends Seed>(seed: T, values: AuditabilityValues = noAuditRequirement) {
  const auditability = auditabilityFixture(values);
  auditability.workspaceId = seed.workspaceId; auditability.assessmentId = seed.assessmentId;
  auditability.assessmentVersion = seed.assessmentVersion; auditability.baseCatalogVersion = seed.catalogVersion;
  auditability.evaluatedAt = seed.evaluatedAt;
  auditability.candidates = seed.candidates.map(candidate => {
    const template = structuredClone(auditability.candidates[0]);
    template.displayName = candidate.displayName; template.analysis.evaluatedAt = seed.evaluatedAt;
    template.analysis.optionScope = { optionId: candidate.optionId, plan: candidate.plan, region: candidate.region,
      configuration: "Synthetic logging enabled" };
    for (const fact of template.evidence) fact.scope = { ...template.analysis.optionScope };
    return template;
  });
  return { ...seed, policyVersion: "synthetic-comparison-2", hardConstraintPolicyVersion: "hard-constraint-preflight-2", auditability,
    candidates: seed.candidates.map((candidate, index) => {
      const checks = auditability.candidates[index].analysis.checks;
      const finding = (check: typeof checks[number]): ComparisonFinding => ({ dimension: "AUDITABILITY",
        profilePath: ["REQUIREMENT_UNKNOWN", "AUDIT_INTENT_UNCLEAR"].includes(check.reasonCode)
          ? "security.auditability" : "security.auditabilityRequirements",
        reasonCode: check.reasonCode, explanation: `${check.criterion}: Explicit fictional audit check.` });
      const exclusionReasons = [...candidate.exclusionReasons, ...checks.filter(c => c.outcome === "FAIL").map(finding)];
      const informationGaps = [...candidate.informationGaps.filter(f => !(checks.some(c => c.outcome === "PASS") &&
        f.dimension === "COVERAGE" && f.reasonCode === "NO_AFFIRMATIVE_CHECKS")), ...checks.filter(c => c.outcome === "UNKNOWN").map(finding)];
      return { ...candidate, exclusionReasons, informationGaps,
        hardVerdict: exclusionReasons.length ? "EXCLUDED" : informationGaps.length ? "UNRESOLVED" : "PASSES_CHECKED_REQUIREMENTS" };
    }) };
}
