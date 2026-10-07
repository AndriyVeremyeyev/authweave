import type { ComparisonCandidate } from "./comparison.ts";
import type { ComparisonProvenance, EvidenceGate, EvidenceGroup, EvidenceRow } from "./comparison-provenance.ts";

export const evidenceFamilies = { CAPABILITY: "Identity capabilities", CONTEXT: "Application and audience compatibility",
  RESIDENCY: "At-rest storage destinations", AUTHENTICATION_CONTROL: "Scoped human authentication controls", AUDITABILITY: "Identity-provider auditability" } as const;
export const evidenceGateCopy: Record<EvidenceGate, string> = {
  MISSING: "No fact recorded — not proof of unsupported capability",
  UNREVIEWED: "Unreviewed claim — cannot establish support or exclusion",
  FUTURE: "Future-dated — cannot be used at this comparison time",
  STALE: "Older than 90 days — cannot be used at this comparison time",
  CURRENT: "Passes date/review gates only — not source or deployed-behavior verification",
};
export const matrixOptionLimit = 3;
export type MatrixOption = Pick<ComparisonCandidate, "optionId" | "displayName" | "plan" | "region" | "hardVerdict">;
export type MatrixRow = { path: string; label: string; cells: { optionId: string; evidence: EvidenceRow }[] };
export type EvidenceMatrix = { options: MatrixOption[]; groups: { family: EvidenceGroup["family"]; rows: MatrixRow[] }[] };
const families = Object.keys(evidenceFamilies) as EvidenceGroup["family"][];
const counts = [9, 19, 4, 36, 6];

/** Join already-guarded display data by exact option and fact path, never array position or a guessed missing fact. */
export function comparisonMatrix(candidates: MatrixOption[], evidence: ComparisonProvenance[]): EvidenceMatrix | null {
  if (!candidates.length || candidates.length > 100 || candidates.length !== evidence.length
      || new Set(candidates.map(c => c.optionId)).size !== candidates.length
      || new Set(evidence.map(e => e.optionId)).size !== evidence.length) return null;
  const options = candidates.map(c => ({ optionId: c.optionId, displayName: c.displayName, plan: c.plan, region: c.region, hardVerdict: c.hardVerdict }));
  const inventories = options.map(c => evidence.find(e => e.optionId === c.optionId));
  if (inventories.some(e => !e || e.groups.length !== families.length)) return null;
  const groups: EvidenceMatrix["groups"] = [];
  for (const [index, family] of families.entries()) {
    const inventoriesForFamily = inventories.map(e => e!.groups.find(g => g.family === family));
    if (inventoriesForFamily.some(g => !g || g.rows.length !== counts[index] || new Set(g.rows.map(r => r.path)).size !== g.rows.length)) return null;
    const rows: MatrixRow[] = [];
    for (const first of inventoriesForFamily[0]!.rows) {
      const cells: MatrixRow["cells"] = [];
      for (const [column, group] of inventoriesForFamily.entries()) {
        const fact = group!.rows.find(r => r.path === first.path);
        if (!fact || fact.label !== first.label) return null;
        cells.push({ optionId: options[column].optionId, evidence: { ...fact } });
      }
      rows.push({ path: first.path, label: first.label, cells });
    }
    groups.push({ family, rows });
  }
  return { options, groups };
}

// Selection affects display only. Output order is always Core order, not the order of clicks or a ranking.
export function matrixSelection(options: MatrixOption[], selected: readonly string[]): MatrixOption[] {
  return options.filter(o => selected.includes(o.optionId)).slice(0, matrixOptionLimit);
}
