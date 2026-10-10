import Link from "next/link";
import { resultReferenceQuery, type ResultPage, type ResultSummary } from "@/lib/assessment/decision-results";

const date = (value: string) => value.replace("T", " ").replace("Z", " UTC");
const statusLabels = { RANKED_SHORTLIST: "Ranked conditional shortlist", UNRANKED_SHORTLIST: "Unranked conditional shortlist",
  NEEDS_INFORMATION: "More information needed", NO_ELIGIBLE_OPTIONS: "No eligible options in this calculation" };
const verdictLabels = { ELIGIBLE: "Passes checked hard requirements", EXCLUDED: "Excluded by a checked hard requirement", UNRESOLVED: "Unresolved hard requirements" };

export function ResultUnavailable({ assessmentId, invalid = false }: { assessmentId: string; invalid?: boolean }) {
  return <main className="mx-auto max-w-3xl px-5 py-10 text-slate-100 sm:px-8">
    <Link href={`/assessments/${assessmentId}?step=review`} className="text-sm text-cyan-200 hover:underline">← Review saved requirements</Link>
    <h1 className="mt-6 text-3xl font-semibold">Saved result unavailable</h1>
    <p role="alert" className="mt-4 text-sm leading-6 text-slate-300">{invalid ? "Use a complete exact result reference or return to the latest history. No substitute result was selected." : "The exact owned read or historical verification could not be completed. No substitute or new calculation was returned. Try again later."}</p>
    <Link prefetch={false} href={`/assessments/${assessmentId}/results`} className="mt-5 inline-block text-sm text-cyan-200 hover:underline">Latest result history</Link>
  </main>;
}

export function ResultHistory({ page, paginated }: { page: ResultPage; paginated: boolean }) {
  const base = `/assessments/${page.assessmentId}/results`;
  return <section aria-labelledby="history-heading" className="mt-8">
    <h2 id="history-heading" className="text-xl font-semibold">Saved calculation versions</h2>
    <p className="mt-3 text-sm leading-6 text-slate-300">This list contains references, not verified calculation bodies. Open a version to ask Core to replay its exact historical inputs. Browsing never creates a new calculation version or approval.</p>
    {!page.items.length ? <div className="mt-6 rounded-xl border border-white/10 p-5">
      <h3 className="font-medium">{paginated ? "No older results on this page" : "No saved decision calculations yet"}</h3>
      <p className="mt-2 text-sm text-slate-300">{paginated ? "Return to the latest history to see newer versions." : "The existing synthetic previews are not saved decision results. Recording a result requires an explicitly selected workflow-verified publication and weights; this read-only page does not create one."}</p>
    </div> : <ol className="mt-6 grid gap-4">
      {page.items.map(item => <li key={item.reference.resultId} className="min-w-0 rounded-xl border border-white/10 bg-white/[0.025] p-5">
        <h3 className="text-lg font-medium">Result version {item.reference.version}</h3>
        <p className="mt-2 text-sm text-slate-300">{item.previousResult ? `Explicit re-evaluation of result version ${item.previousResult.version}` : "Initial recorded calculation"} · Profile version {item.assessmentVersion}</p>
        <p className="mt-2 break-words text-sm text-slate-300">Catalog: {item.catalog.catalogVersion}</p>
        <p className="mt-2 text-xs text-slate-400">Recorded <time dateTime={item.recordedAt}>{date(item.recordedAt)}</time></p>
        <Link prefetch={false} href={`${base}/${item.reference.resultId}?${resultReferenceQuery(item.reference)}`}
          className="mt-4 inline-block rounded-lg border border-cyan-300/30 px-3 py-2 text-sm text-cyan-200 hover:bg-cyan-300/10 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Verify and open result version {item.reference.version}</Link>
      </li>)}
    </ol>}
    <nav aria-label="Result history pages" className="mt-6 flex flex-wrap gap-6 text-sm text-cyan-200">
      {paginated && <Link prefetch={false} href={base} className="hover:underline">Latest results</Link>}
      {page.nextBefore && <Link prefetch={false} href={`${base}?${resultReferenceQuery(page.nextBefore, true)}`} className="hover:underline">Older results →</Link>}
    </nav>
  </section>;
}

export function ResultSummaryView({ summary }: { summary: ResultSummary }) {
  const item = summary.item;
  return <section aria-labelledby="result-heading" className="mt-8 min-w-0">
    <h2 id="result-heading" className="text-2xl font-semibold">Result version {item.reference.version}</h2>
    <p role="status" className="mt-4 rounded-xl border border-cyan-300/30 bg-cyan-300/5 p-4 text-sm text-cyan-100">Core verified the whole historical replay using the original profile, publication, policy, weights, clock and owner audit.</p>
    <p className="mt-4 text-sm leading-6 text-slate-300">Read-only summary of saved advice, not a current recommendation or final architecture choice. Replay verification does not authenticate external sources, verify deployment or certify compliance. No decision is approved. All {summary.verificationGapCount} verification boundaries remain unresolved.</p>
    <dl className="mt-6 grid gap-4 rounded-xl border border-white/10 p-5 text-sm sm:grid-cols-2">
      <div><dt className="text-slate-400">Saved outcome</dt><dd className="mt-1">{statusLabels[summary.status]}</dd></div>
      <div><dt className="text-slate-400">Original profile</dt><dd className="mt-1">Version {item.assessmentVersion} · Schema {summary.profileSchemaVersion}</dd></div>
      <div><dt className="text-slate-400">Original catalog</dt><dd className="mt-1 break-words">{item.catalog.catalogVersion}</dd></div>
      <div><dt className="text-slate-400">Original evaluation clock</dt><dd className="mt-1"><time dateTime={summary.evaluatedAt}>{date(summary.evaluatedAt)}</time></dd></div>
      <div><dt className="text-slate-400">Recorded</dt><dd className="mt-1"><time dateTime={item.recordedAt}>{date(item.recordedAt)}</time></dd></div>
      <div><dt className="text-slate-400">Result history</dt><dd className="mt-1">{item.previousResult ? `Explicit re-evaluation of version ${item.previousResult.version}` : "Initial calculation"}</dd></div>
    </dl>
    <h3 className="mt-7 text-lg font-medium">Original preference weights</h3>
    <p className="mt-2 text-sm text-slate-300">{summary.weights.mode === "NONE" ? "No preference weights or points were used. No ranking is inferred." : "Explicit weights total 100 points. Points measure the declared preferences, not confidence or hard-requirement compliance."}</p>
    {summary.weights.values.length > 0 && <ul className="mt-3 flex flex-wrap gap-3 text-sm">{summary.weights.values.map(value => <li key={value.capability} className="rounded-lg bg-white/5 px-3 py-2">{value.capability}: {value.weight}</li>)}</ul>}
    <h3 className="mt-7 text-lg font-medium">Saved option checks</h3>
    <p className="mt-2 text-sm text-slate-400">Saved engine order (option ID), not ranking; no winner is selected here. An eligible option is conditional on the checked evidence, not verified live interoperability. The full receipt retains detailed findings and architecture follow-ups.</p>
    <ul className="mt-4 grid gap-4 md:grid-cols-2">{summary.candidates.map(candidate => <li key={candidate.optionId} className="min-w-0 rounded-xl border border-white/10 p-5">
      <h4 className="break-words font-medium">{candidate.product}</h4>
      <p className="mt-2 break-words text-sm text-slate-300">{candidate.plan} · {candidate.region} · {candidate.deployment === "MANAGED" ? "Managed" : "Self-hosted"}</p>
      <p className="mt-3 text-sm text-slate-200">{verdictLabels[candidate.hardVerdict]}</p>
      <p className="mt-2 text-sm text-slate-400">{candidate.score ? `Preference points: ${candidate.score.lowerBound}–${candidate.score.upperBound} of 100; unknown weight ${candidate.score.unknownWeight}.` : "Not scored; no preference points are inferred."}</p>
      <p className="mt-3 break-all text-xs text-slate-500">Option ID: {candidate.optionId}</p>
    </li>)}</ul>
    <details className="mt-7 rounded-xl border border-white/10 p-5 text-sm text-slate-400">
      <summary className="cursor-pointer font-medium text-slate-300">Exact immutable references</summary>
      <dl className="mt-4 grid gap-3 break-all">
        <div><dt>Result ID</dt><dd>{item.reference.resultId}</dd></div>
        <div><dt>Result SHA-256</dt><dd>{item.reference.resultSha256}</dd></div>
        <div><dt>Profile SHA-256</dt><dd>{summary.profileSha256}</dd></div>
        <div><dt>Catalog snapshot ID</dt><dd>{item.catalog.snapshotId}</dd></div>
        <div><dt>Catalog snapshot SHA-256</dt><dd>{item.catalog.snapshotSha256}</dd></div>
        <div><dt>Calculation policy SHA-256</dt><dd>{summary.policySha256}</dd></div>
      </dl>
    </details>
  </section>;
}
