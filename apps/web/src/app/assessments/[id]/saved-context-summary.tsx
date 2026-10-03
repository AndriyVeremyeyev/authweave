import { evaluationContextLabels as labels, type EvaluationContextValues } from "@/lib/assessment/evaluation-context";

// Saved editor projection only; this does not infer a scenario, applicability or readiness.
export function SavedContextSummary({ values }: { values: EvaluationContextValues | null }) {
  return (
    <section aria-label="Saved application context" className="mt-6 rounded-xl border border-white/10 bg-white/[0.025] p-4">
      <p className="text-xs font-semibold uppercase tracking-[0.15em] text-slate-400">Saved context</p>
      <p className="mt-1 text-xs text-slate-400">From saved answers, not unsaved edits.</p>
      {values ? <dl className="mt-3 grid gap-3 sm:grid-cols-3">
        {[
          ["Application", values.applicationType === "UNKNOWN" ? "Not recorded" : labels[values.applicationType]],
          ["Users", values.selectedPopulations.length ? values.selectedPopulations.map(value => labels[value]).join(", ") : "Not recorded"],
          ["Clients", values.clients.length ? values.clients.map(value => labels[value]).join(", ") : "Not recorded"],
        ].map(([label, value]) => <div key={label} className="min-w-0">
          <dt className="text-xs text-slate-400">{label}</dt>
          <dd className="mt-1 break-words text-sm font-medium text-slate-200">{value}</dd>
        </div>)}
      </dl> : <p className="mt-3 text-sm text-amber-200">Saved context cannot be read safely. Other assessment sections remain available.</p>}
    </section>
  );
}
