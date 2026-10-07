import { savedInputRowId, type SavedRequirementGroup } from "@/lib/assessment/saved-requirements";

/** Summarize existing display labels only; do not decide which answers are required. */
export function SavedInputFollowUps({ groups }: { groups: SavedRequirementGroup[] }) {
  const questions = groups.map(group => ({ group,
    rows: group.rows?.flatMap((row, index) => row.state === "recorded" ? [] : [{ row, index }]) ?? [],
  })).filter(item => item.rows.length > 0);
  const unreadable = groups.filter(group => group.rows === null);
  return <section aria-labelledby="saved-input-follow-ups-heading" className="rounded-xl border border-amber-300/20 p-4 sm:p-5">
    <h3 id="saved-input-follow-ups-heading" className="text-lg font-semibold">Saved inputs to discuss</h3>
    <p className="mt-2 text-sm leading-6 text-slate-300">These are the Not recorded and Needs definition labels from the saved cards below, not validation errors or a checklist of required answers. Some inputs may not apply; a definition may need team discussion rather than another editor value.</p>
    <p className="mt-2 text-xs leading-5 text-slate-400">Links focus the exact saved row, not an editor or a temporary proposal. Inspect its value and section note before deciding whether to edit. Counts are displayed fields, not severity or assessment completeness.</p>
    {questions.length === 0 ? <p className="mt-4 text-sm text-slate-300">{unreadable.length === groups.length && groups.length > 0
      ? "No saved cards can be read safely. See the unavailable sections below; no missing answers or readiness result can be inferred."
      : "No Not recorded or Needs definition labels in the readable cards. Unreadable sections and other profile fields may still need clarification; this is not a readiness result."}</p>
      : <div className="mt-4 space-y-3">{questions.map(({ group, rows }) => <details key={group.id} className="min-w-0 rounded-lg border border-white/10 p-3">
        <summary className="cursor-pointer break-words text-sm font-medium">{group.title} · {rows.length} {rows.length === 1 ? "field" : "fields"}</summary>
        <ul className="mt-3 space-y-3">{rows.map(({ row, index }) => <li key={index} className="min-w-0 text-sm">
          <a href={`#${savedInputRowId(group, index)}`} className="break-words text-cyan-100 underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">{row.label}</a>
          <span className="mt-1 block text-xs text-amber-100">{row.state === "not-recorded" ? "Not recorded" : "Needs definition"}</span>
        </li>)}</ul>
      </details>)}</div>}
    {unreadable.length > 0 && <div className="mt-4 rounded-lg border border-amber-300/20 p-3">
      <p className="text-sm font-medium text-amber-100">Cannot inspect these saved sections</p>
      <p className="mt-2 text-xs leading-5 text-slate-400">No field count or missing answer is inferred from an unreadable section. Other readable sections remain available.</p>
      <ul className="mt-3 space-y-2 text-sm">{unreadable.map(group => <li key={group.id}>
        <a href={`#saved-${group.id}-heading`} className="break-words text-cyan-100 underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">{group.title}</a>
      </li>)}</ul>
    </div>}
    <p className="mt-4 text-xs leading-5 text-slate-400">Recorded answers do not verify provider support, applicability or compliance. The full saved cards, independent Core checks and unchecked boundaries remain unchanged.</p>
  </section>;
}
