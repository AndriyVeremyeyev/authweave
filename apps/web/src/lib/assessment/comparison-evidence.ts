type CandidateEvidence = {
  informationGaps: readonly { reasonCode: string }[];
  capabilityPreferences: readonly { reasonCode: string }[];
};

export function hasStaleSyntheticEvidence(candidates: readonly CandidateEvidence[]): boolean {
  return candidates.some(candidate =>
    candidate.informationGaps.some(gap => gap.reasonCode === "EVIDENCE_STALE") ||
    candidate.capabilityPreferences.some(preference => preference.reasonCode === "EVIDENCE_STALE"));
}
