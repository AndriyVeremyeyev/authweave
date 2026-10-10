import type { PublicationReviewResult } from "@/lib/auth/core-client";
import { publicationBlockerTitle } from "@/lib/catalog/publication-preflight";

const familyTitles: Record<string, string> = {
  ARCHITECTURE_CONFIGURATION: "Proposed architecture settings",
  PROVISIONING_LIFECYCLE: "Provisioning and offboarding design",
  OPERATIONS_PLANNING: "Operational planning",
  ASSURANCE_COMPLIANCE: "Assurance and compliance investigation",
};
export default function PublicationPreflight({ result }: { result: PublicationReviewResult | null }) {
  const report = result?.kind === "ready" ? result.report : null;
  return <section className="mt-8 rounded-xl border border-amber-700 p-6" aria-labelledby="publication-preflight-heading">
    <h2 id="publication-preflight-heading" className="text-2xl font-semibold">Fresh publication check</h2>
    <p className="mt-4 text-sm text-slate-400">This is the legacy read-only preflight, not a deployment-status check for the separate opt-in Core publication workflow. Its unchanged BLOCKED contract cannot authorize or invoke that writer. This panel does not enable publication or replace fresh server-side writer checks.</p>
    {!report ? <p role="status" className="mt-4 text-amber-100">{
      result?.kind === "reauth-required" ? "Verify this account again and reload to request a fresh publication check." :
        "The fresh Core check is unavailable. Historical receipts and other review panels cannot replace it; no publication permission is inferred."
    }</p> : <>
      <p className="mt-4 text-amber-100">Publication is blocked. This fresh Core denial belongs to this exact {report.mode === "PROPOSAL_APPROVAL" ? `proposal revision ${report.inputVersion}` : "stored bootstrap review"}, not a saved approval or publication token.</p>
      <p className="mt-3 text-sm text-slate-400">Checked at <time dateTime={report.evaluatedAt}>{report.evaluatedAt}</time> using Core policy catalog-publication-preflight-12. The check uses one read-only, repeatable-read database snapshot; the rest of this page uses separate reads. Reload to request a new check.</p>
      <p className="mt-4 text-slate-300">Whole-candidate facts: {report.facts.total}. No manual observation: {report.facts.unobserved}; reported supporting: {report.facts.supporting}; contradicting: {report.facts.contradicting}; insufficient: {report.facts.insufficient}. Stale: {report.facts.stale}; future-dated: {report.facts.future}. Date counts overlap observation counts; do not add them. Proposal observations included through #{report.reviewThroughNumber}; bootstrap observations belong to its immutable review.</p>
      <h3 className="mt-6 text-lg font-semibold">What was exercised?</h3>
      {report.planning ? <>
        <p className="mt-3 text-slate-300">{report.planning.checkedDimensions} structural input/scenario dimensions and four frozen synthetic planning regression families were replayed. Coverage remains incomplete: {report.planning.structuralVerificationGaps} structural verification gaps and {report.planning.planningVerificationGaps} additional planning boundaries remain explicit.</p>
        <ul className="mt-3 space-y-2 text-slate-300">{report.planning.regressions.map(row =>
          <li key={row.family}>{familyTitles[row.family]}: {row.checkedCases} synthetic cases.</li>)}</ul>
        <p className="mt-3 text-sm text-slate-400">These counts are not a completion percentage, verified provider configuration or full candidate-change coverage. Proposed design and investigation plans are not observed lifecycle, cost estimates, assurance or compliance verification.</p>
      </> : <p className="mt-3 text-amber-100">Planning was not checked because the exact stored input was unavailable or invalid. Missing checks are not zero gaps or successful coverage.</p>}
      <h3 className="mt-6 text-lg font-semibold">What blocks publication?</h3>
      <ul className="mt-3 list-disc space-y-2 pl-5 text-slate-300">{report.blockers.map(code =>
        <li key={code} data-publication-blocker={code}>{publicationBlockerTitle(code)}</li>)}</ul>
    </>}
    <p className="mt-5 text-amber-100">Curator access authorizes this read only. Publication-write authorization is a separate, unperformed check. Nothing is approved, published, source-verified or written by this panel.</p>
  </section>;
}
