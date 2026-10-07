import type { SyntheticComparisonSummary } from "./comparison.ts";

export type FollowUpKind = "REQUIREMENT" | "EVIDENCE" | "BOUNDARY";
export type ComparisonFollowUp = {
  kind: FollowUpKind;
  source: "HARD_CHECK" | "PREFERENCE";
  dimension: string;
  profilePath: string;
  reasonCode: string;
  occurrences: { optionIndex: number; explanation: string }[];
};

export const followUpCopy = {
  REQUIREMENT: { heading: "Clarify your saved requirements",
    next: "Review what your application needs with your team. An explicit answer may clarify a check, but it does not verify provider support. Nothing is filled in or saved automatically." },
  EVIDENCE: { heading: "Review catalog evidence",
    next: "This needs evidence for the exact option scope, not a weaker requirement. Fictional sources cannot verify a real provider; changing a date alone does not review a claim." },
  BOUNDARY: { heading: "Review remaining check boundaries",
    next: "Inspect the exact reasons below. Recording an answer does not extend the evaluator or verify compliance. No responsible party or resolution is inferred for an unrecognized reason." },
} as const;

const inputReasons: Record<string, readonly string[]> = {
  CAPABILITY: ["REQUIREMENT_UNKNOWN"],
  CONTEXT: ["PROFILE_CONTEXT_UNKNOWN"],
  RESIDENCY: ["REQUIREMENT_UNKNOWN", "DATA_SCOPE_UNKNOWN", "ALLOWED_COUNTRIES_UNKNOWN", "RESIDENCY_INTENT_UNCLEAR"],
  AUTHENTICATION_CONTROL: ["REQUIREMENT_UNKNOWN", "CLIENT_SCOPE_UNKNOWN", "POPULATION_SCOPE_UNKNOWN", "CONTROL_INTENT_UNCLEAR"],
  COMPLIANCE_SCOPE: ["COMPLIANCE_SCOPE_UNKNOWN", "COMPLIANCE_SCOPE_INCONSISTENT"],
  AUDITABILITY: ["REQUIREMENT_UNKNOWN", "AUDIT_INTENT_UNCLEAR", "AUDIT_SCOPE_UNKNOWN"],
};
const evidenceReasons: Record<string, readonly string[]> = {
  CAPABILITY: ["CAPABILITY_UNKNOWN"],
  CONTEXT: ["CONTEXT_SUPPORT_UNKNOWN"],
  RESIDENCY: ["STORAGE_LOCATIONS_INCOMPLETE", "STORAGE_LOCATIONS_UNKNOWN"],
  AUTHENTICATION_CONTROL: ["CONTROL_AVAILABILITY_UNKNOWN", "ENFORCEMENT_UNKNOWN"],
  AUDITABILITY: ["CAPABILITY_UNKNOWN", "RETENTION_DURATION_UNKNOWN"],
};
const evidenceGates = ["EVIDENCE_MISSING", "EVIDENCE_UNREVIEWED", "EVIDENCE_STALE", "EVIDENCE_FROM_FUTURE"];

function kind(dimension: string, reason: string): FollowUpKind {
  if (Object.hasOwn(inputReasons, dimension) && inputReasons[dimension].includes(reason)) return "REQUIREMENT";
  if (Object.hasOwn(evidenceReasons, dimension) &&
      (evidenceGates.includes(reason) || evidenceReasons[dimension].includes(reason))) return "EVIDENCE";
  return "BOUNDARY";
}

/** Display grouping of Core's gaps only; never a priority, penalty, readiness score or new finding. */
export function comparisonFollowUps(comparison: SyntheticComparisonSummary): ComparisonFollowUp[] {
  const groups = new Map<string, ComparisonFollowUp>();
  comparison.candidates.forEach((candidate, optionIndex) => {
    const add = (source: ComparisonFollowUp["source"], dimension: string, profilePath: string, reasonCode: string, explanation: string) => {
      const key = JSON.stringify([source, dimension, profilePath, reasonCode]);
      let group = groups.get(key);
      if (!group) {
        group = { kind: kind(dimension, reasonCode), source, dimension, profilePath, reasonCode, occurrences: [] };
        groups.set(key, group);
      }
      // Preserve every check, including distinct audit criteria/client scopes sharing a reason and path.
      group.occurrences.push({ optionIndex, explanation });
    };
    candidate.informationGaps.forEach(f => add("HARD_CHECK", f.dimension, f.profilePath, f.reasonCode, f.explanation));
    candidate.capabilityPreferences.filter(p => p.outcome === "UNKNOWN")
      .forEach(p => add("PREFERENCE", "CAPABILITY", p.profilePath, p.reasonCode, p.explanation));
  });
  return [...groups.values()];
}
