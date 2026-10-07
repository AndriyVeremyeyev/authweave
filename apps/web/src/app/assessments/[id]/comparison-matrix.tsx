"use client";

import { useState, useSyncExternalStore, type CSSProperties } from "react";
import { comparisonMatrix, evidenceFamilies, evidenceGateCopy, matrixOptionLimit, matrixSelection,
  type MatrixOption } from "@/lib/assessment/comparison-matrix";
import { comparisonVerdicts } from "@/lib/assessment/comparison-presentation";
import type { ComparisonProvenance, EvidenceGroup } from "@/lib/assessment/comparison-provenance";

const subscribe = () => () => undefined;
const clientSnapshot = () => true;
const serverSnapshot = () => false;

export function ComparisonMatrix({ candidates, evidence }: { candidates: MatrixOption[]; evidence: ComparisonProvenance[] }) {
  const [selected, setSelected] = useState<string[]>(() => candidates.slice(0, matrixOptionLimit).map(c => c.optionId));
  const [family, setFamily] = useState<EvidenceGroup["family"]>("CAPABILITY");
  // The server-rendered table works without JavaScript; controls activate only after hydration.
  const interactive = useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot);
  const matrix = comparisonMatrix(candidates, evidence);
  if (!matrix) return <p role="status" className="mt-5 rounded-xl border border-amber-700 p-4 text-sm text-amber-100">The evidence matrix is unavailable. No missing fact or comparison result is inferred; the option cards below are unchanged.</p>;
  const columns = matrixSelection(matrix.options, selected), group = matrix.groups.find(g => g.family === family)!;
  function toggle(id: string) {
    if (!matrix!.options.some(o => o.optionId === id)) return;
    setSelected(previous => previous.includes(id) ? previous.filter(value => value !== id)
      : previous.length < matrixOptionLimit ? [...previous, id] : previous);
  }
  return <section className="mt-6 min-w-0 rounded-xl border border-cyan-300/20 p-4 sm:p-5" aria-labelledby="evidence-matrix-heading">
    <h3 id="evidence-matrix-heading" className="text-lg font-semibold">Compare fictional evidence side by side</h3>
    <p className="mt-3 text-sm leading-6 text-slate-300">Compare recorded claims, not provider recommendations. All sources are fictional .invalid references and are never fetched. A fresh REVIEWED label passes date/review gates only, not real source or deployed-behavior verification.</p>
    <p className="mt-2 text-xs leading-5 text-slate-400">Not every listed fact is applied to your requirements. Missing evidence is not proof of incompatibility; UNKNOWN remains unknown. Choosing columns or an evidence group changes this table only, not answers, verdicts, weights or Core order.</p>
    <fieldset disabled={!interactive} className="mt-4 space-y-4">
      <legend className="font-medium text-sm">Display options only — nothing is saved</legend>
      <details className="rounded-lg border border-white/10 p-3">
        <summary className="cursor-pointer text-sm">Choose up to {matrixOptionLimit} options to display</summary>
        <p className="mt-2 text-xs leading-5 text-slate-400">Initially the first {Math.min(matrixOptionLimit, candidates.length)} options in Core order are shown for readability, not quality. Uncheck an option before adding another at the limit. Excluded and unresolved options remain selectable.</p>
        <div className="mt-3 grid gap-2 sm:grid-cols-2">{matrix.options.map(option => <label key={option.optionId} className="flex min-w-0 items-start gap-3 rounded-lg border border-white/10 p-3 text-sm">
          <input type="checkbox" checked={selected.includes(option.optionId)} onChange={() => toggle(option.optionId)}
            disabled={!selected.includes(option.optionId) && columns.length >= matrixOptionLimit} className="mt-1 shrink-0" />
          <span className="min-w-0 break-words">{option.displayName}<span className="mt-1 block text-xs text-slate-400">{option.plan} · {option.region}</span></span>
        </label>)}</div>
      </details>
      <div><label htmlFor="comparison-evidence-family" className="block text-sm font-medium">Evidence group to compare</label>
        <select id="comparison-evidence-family" value={family} onChange={event => {
          if (Object.hasOwn(evidenceFamilies, event.currentTarget.value)) setFamily(event.currentTarget.value as EvidenceGroup["family"]);
        }} className="mt-2 w-full min-w-0 rounded-lg border border-slate-600 bg-slate-900 p-2 text-sm">
          {Object.entries(evidenceFamilies).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
        </select>
      </div>
    </fieldset>
    <noscript><p className="mt-3 text-xs text-amber-100">JavaScript is required to change columns or the evidence group. The initial capability table remains readable; display controls are disabled.</p></noscript>
    <p role="status" aria-live="polite" aria-atomic="true" className="mt-4 text-xs text-slate-400">Showing {columns.length} of {matrix.options.length} options in Core order · {evidenceFamilies[family]}</p>
    {family === "RESIDENCY" && <p className="mt-3 text-xs leading-5 text-amber-100">Recorded destinations, not a region menu. PARTIAL cannot rule out other storage locations; processing, transfers and compliance are not verified.</p>}
    {family === "AUTHENTICATION_CONTROL" && <p className="mt-3 text-xs leading-5 text-amber-100">Availability and enforcement capability are distinct; neither proves configured controls, enrollment/recovery security or an assurance level.</p>}
    {family === "AUDITABILITY" && <p className="mt-3 text-xs leading-5 text-amber-100">Identity-provider scope only, not application logs. The exact configuration scope can differ by option. Documented minimum retention is not deployed retention, export delivery or compliance verification.</p>}
    {columns.length === 0 ? <p className="mt-4 rounded-lg border border-slate-700 p-4 text-sm">Choose at least one option to display. The comparison verdicts below are unchanged.</p> : <>
      <p className="mt-3 text-xs leading-5 text-slate-400">On narrow screens, focus the table and use arrow keys or scroll horizontally. {group.rows.length} catalog paths per option are shown, not an applied-check count or complete coverage.</p>
      <div role="region" aria-label={`${evidenceFamilies[family]} fictional evidence table`} tabIndex={0}
        className="mt-3 max-w-full overflow-x-auto rounded-lg border border-slate-700 focus-visible:outline-2 focus-visible:outline-cyan-300">
        <table className="w-full min-w-[var(--matrix-mobile-width)] table-fixed border-collapse text-left text-xs sm:min-w-[var(--matrix-desktop-width)]"
          style={{ "--matrix-mobile-width": `${144 + columns.length * 176}px`, "--matrix-desktop-width": `${(columns.length + 1) * 224}px` } as CSSProperties}>
          <caption className="sr-only">{evidenceFamilies[family]}: recorded fictional claims, not recommendations. Columns retain Core order.</caption>
          <thead><tr><th scope="col" className="sticky left-0 z-10 w-36 border-b border-r border-slate-700 bg-slate-900 p-3 align-top sm:w-56">Catalog fact</th>
            {columns.map(option => <th key={option.optionId} scope="col" className="border-b border-r border-slate-700 bg-slate-900 p-3 align-top">
              <p className="break-words text-sm font-semibold">{option.displayName}</p><p className="mt-2 break-words font-normal text-slate-400">{option.plan} · {option.region}</p>
              <p className="mt-2 font-normal text-slate-300">Core status: {comparisonVerdicts[option.hardVerdict].label}</p>
            </th>)}</tr></thead>
          <tbody>{group.rows.map(row => <tr key={row.path}>
            <th scope="row" className="sticky left-0 z-10 border-b border-r border-slate-700 bg-slate-900 p-3 align-top font-medium">
              <p className="break-words">{row.label}</p><details className="mt-3 font-normal text-slate-400"><summary className="cursor-pointer">Fact path</summary><p className="mt-2 break-all">{row.path}</p></details>
            </th>
            {columns.map(option => {
              const fact = row.cells.find(c => c.optionId === option.optionId)!.evidence;
              return <td key={option.optionId} className="border-b border-r border-slate-700 p-3 align-top">
                {fact.claim !== null && <p className="break-words font-medium">Recorded claim: {fact.claim}</p>}
                <p className={`mt-2 leading-5 ${fact.gate === "CURRENT" ? "text-slate-300" : "text-amber-100"}`}>{evidenceGateCopy[fact.gate]}</p>
                {fact.configuration && <p className="mt-2 break-words leading-5 text-slate-400">Exact configuration: {fact.configuration}</p>}
                {fact.sourceUrl && <details className="mt-3 text-slate-400"><summary className="cursor-pointer">Source and observation</summary>
                  <p className="mt-2 break-all leading-5">Fictional source: {fact.sourceUrl}</p>
                  <p className="mt-2 break-words leading-5">Observation: <time dateTime={fact.observedAt!}>{fact.observedAt}</time> · {fact.evidenceStatus}</p>
                </details>}
              </td>;
            })}
          </tr>)}</tbody>
        </table>
      </div>
    </>}
  </section>;
}
