import type { ArchitecturePatternPreflightSummary } from "@/lib/auth/core-client";
import { architectureOverview, architectureStatusText } from "@/lib/assessment/architecture-presentation";
import { evaluationContextLabels } from "@/lib/assessment/evaluation-context";

export function ArchitectureOverview({ preview }: { preview: ArchitecturePatternPreflightSummary }) {
  const rows = architectureOverview(preview);
  if (!rows) return <p role="status" className="mt-5 rounded-xl border border-amber-700 p-4 text-sm text-amber-100">The pattern overview is unavailable. No missing check, applicability or token location is inferred; the detailed cards below are unchanged.</p>;
  return <section className="mt-6 min-w-0 rounded-xl border border-cyan-300/20 p-4 sm:p-5" aria-labelledby="architecture-overview-heading">
    <h3 id="architecture-overview-heading" className="text-lg font-semibold">Compare the saved-input boundaries</h3>
    <p className="mt-3 text-sm leading-6 text-slate-300">Saved version {preview.assessmentVersion}. Five patterns in Core order, not a shortlist or ranking. Only client selection and browser token minimization are checked. A partial match is not an architecture recommendation.</p>
    <p className="mt-2 text-xs leading-5 text-slate-400">Follow a pattern link for pros, trade-offs and conditions. On narrow screens, focus the table and scroll horizontally or use arrow keys.</p>
    <div role="region" aria-label="Saved architecture pattern comparison" tabIndex={0}
      className="mt-4 max-w-full overflow-x-auto rounded-lg border border-slate-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
      <table className="w-full min-w-[688px] table-fixed border-collapse text-left text-xs">
        <caption className="sr-only">Five architecture patterns for saved assessment version {preview.assessmentVersion}; outcomes apply only to client selection and browser token minimization.</caption>
        <colgroup><col className="w-40" /><col className="w-32" /><col className="w-44" /><col className="w-56" /></colgroup>
        <thead><tr className="bg-slate-900 text-slate-300">
          <th scope="col" className="sticky left-0 z-10 border-b border-slate-700 bg-slate-900 p-3">Pattern and client scope</th>
          <th scope="col" className="border-b border-slate-700 p-3">OAuth tokens</th>
          <th scope="col" className="border-b border-slate-700 p-3">Saved-input result</th>
          <th scope="col" className="border-b border-slate-700 p-3">Exact checked boundaries</th>
        </tr></thead>
        <tbody>{rows.map(row => <tr key={row.anchor} className="align-top">
          <th scope="row" className="sticky left-0 z-10 border-b border-r border-slate-700 bg-slate-900 p-3 font-normal">
            <a href={`#${row.anchor}`} className="break-words font-semibold text-cyan-100 underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">{row.displayName}</a>
            <p className="mt-2 text-slate-300">{evaluationContextLabels[row.clientType]}</p>
            <p className="mt-1 text-slate-400">{row.clientScope}</p>
          </th>
          <td className="border-b border-slate-700 p-3 text-slate-300">{row.tokenLocation}</td>
          <td className={`border-b border-slate-700 p-3 ${row.status === "NEEDS_INFORMATION" ? "text-amber-100" : "text-slate-300"}`}>{architectureStatusText[row.status]}</td>
          <td className="border-b border-slate-700 p-3"><dl className="space-y-3">{row.checks.map(check => <div key={check.label}>
            <dt className="text-slate-400">{check.label}</dt><dd className="mt-1 text-slate-300">{check.outcome}</dd>
          </div>)}</dl></td>
        </tr>)}</tbody>
      </table>
    </div>
    <details className="mt-4 rounded-lg border border-white/10 p-3 text-xs leading-5 text-slate-400">
      <summary className="cursor-pointer font-medium">How to read this partial comparison</summary>
      <p className="mt-3">More than one pattern can apply to a mixed-client application. These are the saved checks, not the temporary declarations or concrete settings in the cards below.</p>
      <p className="mt-3">OAuth token location is a pattern property, not a measurement of your app. Application server does not mean no browser session or credential; native and workload storage are not verified by the browser criterion. No configuration, provider compatibility or ready-to-deploy design is verified here.</p>
    </details>
  </section>;
}
