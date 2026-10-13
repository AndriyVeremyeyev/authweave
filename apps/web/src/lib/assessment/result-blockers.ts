import type { ResultAdvice } from "./decision-advice.ts";

type BlockerGroup = { profilePath: string; criticality: string; outcome: "FAIL" | "UNKNOWN"; reasonCode: string;
  checks: number; options: { index: number; checks: number }[] };

/** Presentation of already verified findings only. Never derives eligibility, scores or source authority. */
export function savedBlockerGroups(advice: Pick<ResultAdvice, "candidates">): BlockerGroup[] {
  const groups = new Map<string, BlockerGroup>();
  advice.candidates.forEach((candidate, index) => {
    const outcome = candidate.hardChecks.hardVerdict === "EXCLUDED" ? "FAIL" :
      candidate.hardChecks.hardVerdict === "UNRESOLVED" ? "UNKNOWN" : null;
    if (outcome === null) return;
    for (const finding of candidate.hardChecks.findings) {
      if (finding.outcome !== outcome) continue;
      const key = JSON.stringify([finding.profilePath, finding.criticality, outcome, finding.reasonCode]);
      let group = groups.get(key);
      if (!group) {
        group = { profilePath: finding.profilePath, criticality: finding.criticality, outcome, reasonCode: finding.reasonCode, checks: 0, options: [] };
        groups.set(key, group);
      }
      group.checks++;
      const option = group.options.find(option => option.index === index);
      if (option) option.checks++; else group.options.push({ index, checks: 1 });
    }
  });
  return [...groups.values()].sort((a, b) => (a.outcome === b.outcome ? 0 : a.outcome === "FAIL" ? -1 : 1) ||
    a.profilePath.localeCompare(b.profilePath) || a.criticality.localeCompare(b.criticality) || a.reasonCode.localeCompare(b.reasonCode));
}
