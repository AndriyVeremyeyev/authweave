import Link from "next/link";
import type { PersonalAssessmentListPage } from "@/lib/auth/core-client";
import { evaluationContextLabels as labels } from "@/lib/assessment/evaluation-context";

const statusLabels = {
  DRAFT: "Draft", READY_FOR_EVALUATION: "Ready for evaluation", EVALUATED: "Evaluated",
  DECIDED: "Decided", ARCHIVED: "Archived",
};

function timestamp(value: string) {
  return new Date(value).toLocaleString("en-US", { timeZone: "UTC", dateStyle: "medium", timeStyle: "short" });
}

// Navigation from saved data only: no inferred scenario, completeness score or write.
export function AssessmentList({ page, paginated }: { page: PersonalAssessmentListPage; paginated: boolean }) {
  return (
    <main className="mx-auto max-w-5xl px-5 py-12 text-slate-100 sm:px-8 sm:py-16">
      <Link href="/account" className="text-sm text-cyan-200 hover:underline">← Account</Link>
      <p className="mt-8 text-xs font-semibold uppercase tracking-[0.2em] text-cyan-200">Personal workspace</p>
      <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">Your assessments</h1>
      <p className="mt-4 max-w-2xl text-sm leading-6 text-slate-300">
        Pick a saved assessment to continue. Application, users and clients below come from saved answers,
        not unsaved edits or comparison results.
      </p>
      <p className="mt-2 text-xs leading-5 text-slate-400">Only your personal workspace is shown. These labels do not measure completeness or recommendation readiness.</p>
      {paginated && <Link href="/assessments" className="mt-5 inline-block text-sm text-cyan-200 hover:underline">← Latest assessments</Link>}
      {page.items.length === 0 ? (
        <section className="mt-8 rounded-2xl border border-white/10 bg-white/[0.025] p-6">
          <h2 className="text-lg font-medium">{paginated ? "No older assessments on this page" : "No saved assessments yet"}</h2>
          <p className="mt-2 text-sm text-slate-400">{paginated ? "Return to the latest assessments to continue." : "Return to Account to create a draft. New drafts start with unrecorded context."}</p>
        </section>
      ) : (
        <ul aria-label="Saved assessments" className="mt-8 grid gap-4 md:grid-cols-2">
          {page.items.map(item => (
            <li key={item.id} className="min-w-0">
              <Link href={`/assessments/${item.id}`}
                className="flex h-full flex-col rounded-2xl border border-white/10 bg-white/[0.025] p-5 transition-colors hover:border-cyan-300/60 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-300 sm:p-6">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="rounded-full border border-white/10 px-2.5 py-1 text-xs text-slate-300">{statusLabels[item.status]}</span>
                  <span className="text-xs text-slate-400">Saved version {item.version}</span>
                </div>
                <h2 className="mt-4 text-xl font-semibold leading-7 text-slate-100">
                  {item.context === null ? "Saved context unavailable" : item.context.applicationType === "UNKNOWN" ? "Application type not recorded" : labels[item.context.applicationType]}
                </h2>
                {item.context ? <dl className="mt-4 space-y-3 text-sm">
                  <div><dt className="text-xs text-slate-400">Users</dt>
                    <dd className="mt-1 break-words text-slate-200">{item.context.userPopulations.length ? item.context.userPopulations.map(value => labels[value]).join(", ") : "Not recorded"}</dd></div>
                  <div><dt className="text-xs text-slate-400">Clients</dt>
                    <dd className="mt-1 break-words text-slate-200">{item.context.clients.length ? item.context.clients.map(value => labels[value]).join(", ") : "Not recorded"}</dd></div>
                </dl> : <p className="mt-4 text-sm leading-6 text-amber-200">The stored profile could not be read safely. Open the assessment to see its availability; no context is inferred here.</p>}
                <div className="mt-auto pt-5 text-xs leading-5 text-slate-400">
                  <p>Updated <time dateTime={item.updatedAt}>{timestamp(item.updatedAt)}</time> UTC</p>
                  <p>Created <time dateTime={item.createdAt}>{timestamp(item.createdAt)}</time> UTC</p>
                  <p className="mt-2 break-all">ID {item.id}</p>
                  <span className="mt-4 block text-sm font-medium text-cyan-200">Open assessment →</span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {page.nextBeforeId && <Link href={`/assessments?before=${page.nextBeforeId}`}
        className="mt-8 inline-block rounded-lg border border-white/10 px-4 py-2 text-sm text-cyan-200 hover:border-cyan-300">Older assessments →</Link>}
    </main>
  );
}
