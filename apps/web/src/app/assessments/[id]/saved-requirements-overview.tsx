import { savedRequirementGroups, type SavedInputState } from "@/lib/assessment/saved-requirements";
import { AssessmentStepButton } from "./assessment-workflow";
import type { ReactNode } from "react";

const stateLabels: Record<SavedInputState, string> = {
  recorded: "Recorded", "not-recorded": "Not recorded", "needs-definition": "Needs definition",
};

export function SavedRequirementsOverview({ profile, version, editable, exportPanel }: {
  profile: Record<string, unknown>; version: number; editable: boolean; exportPanel?: ReactNode;
}) {
  const groups = savedRequirementGroups(profile);
  return <div className="mt-6 space-y-6">
    <div className="rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-5">
      <p className="text-sm font-semibold text-cyan-100">Saved version {version} · Read-only overview</p>
      <p className="mt-2 text-sm leading-6 text-slate-300">These cards show the existing editable input sections from your saved profile, not unsaved edits or temporary what-if answers. Other profile fields remain in the technical details below.</p>
      <p className="mt-2 text-xs leading-5 text-slate-400">Not recorded and needs definition are display labels, not validation errors. Some inputs may not apply. Recorded answers do not mean the assessment is complete, compliant or ready for a recommendation.</p>
    </div>
    {exportPanel}
    <div className="grid items-start gap-5 xl:grid-cols-2">
      {groups.map(group => <section key={group.id} aria-labelledby={`saved-${group.id}-heading`}
        className="min-w-0 rounded-xl border border-white/10 bg-white/[0.02] p-5">
        <h3 id={`saved-${group.id}-heading`} className="text-lg font-semibold">{group.title}</h3>
        <p className="mt-2 text-xs leading-5 text-slate-400">{group.note}</p>
        {group.rows ? <dl className="mt-4 divide-y divide-white/10">
          {group.rows.map(row => <div key={row.label} className="py-3">
            <dt className="text-xs text-slate-400">{row.label}</dt>
            <dd className="mt-1 flex flex-wrap items-start justify-between gap-2 text-sm">
              <span className="min-w-0 flex-1 whitespace-pre-wrap break-words text-slate-200">{row.value}</span>
              <span className={`rounded-md px-2 py-1 text-[10px] ${row.state === "recorded"
                ? "bg-white/5 text-slate-400" : "bg-amber-300/10 text-amber-200"}`}>{stateLabels[row.state]}</span>
            </dd>
          </div>)}
        </dl> : <p role="alert" className="mt-4 rounded-lg border border-amber-700 p-3 text-sm text-amber-100">This saved section cannot be read safely. No answers have been inferred; other sections remain available.</p>}
        {editable && <div className="mt-4"><AssessmentStepButton step={group.step}>{group.action} →</AssessmentStepButton></div>}
      </section>)}
    </div>
    <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-white/10 p-5">
      <p className="max-w-lg text-sm leading-6 text-slate-300">Next, explore the fictional comparison. It uses saved requirements; this overview does not select or approve a provider.</p>
      <AssessmentStepButton step="comparison">Explore comparison →</AssessmentStepButton>
    </div>
  </div>;
}
