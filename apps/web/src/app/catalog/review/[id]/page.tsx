import Link from "next/link";
import { randomUUID } from "node:crypto";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { readCatalogProposalReview, readCatalogPublicationPreflight, type CatalogReviewResult,
  type PublicationReviewResult } from "@/lib/auth/core-client";
import PublicationPreflight from "../../publication-preflight";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession } from "@/lib/auth/store";
import type { CatalogImpactReview, ScenarioImpactRow } from "@/lib/catalog/impact-review";
import { candidateClaimSummary, evidenceOffsetFromQuery, type CandidateEvidencePage } from "@/lib/catalog/evidence-review";
import { factReviewHistoryCursorFromQuery, factReviewHistoryHref, factReviewVerdictLabel,
  type FactReviewHistoryPage } from "@/lib/catalog/fact-review-history";
import type { FactReviewSummaryPage } from "@/lib/catalog/fact-review-summary";
import type { CatalogReviewPrerequisites } from "@/lib/catalog/review-prerequisites";
import { observationDateStatus, sourceDetails, type CatalogProposalReview,
  type ObservationDateStatus, type ReviewFactChange,
  type ReviewOptionChange } from "@/lib/catalog/proposal-review";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PAGE_SIZE = 20;
const observationLabels: Record<ObservationDateStatus, string> = {
  WITHIN_90_DAYS: "Dated within 90 days — still unverified",
  OLDER_THAN_90_DAYS: "Dated more than 90 days ago — stale and unverified",
  FUTURE_DATE: "Dated in the future — unusable and unverified",
  INVALID_DATE: "Invalid observation date — unusable and unverified",
};
const conditionalStatusLabels: Record<ScenarioImpactRow["before"], string> = {
  WOULD_SATISFY_CHECKED_REQUIREMENTS: "Would satisfy checked requirements",
  WOULD_VIOLATE_CHECKED_REQUIREMENTS: "Would violate checked requirements",
  INDETERMINATE: "Indeterminate",
  OPTION_ABSENT: "Option absent",
};
function boundedPage(value: string | string[] | undefined, count: number): number {
  const requested = typeof value === "string" && /^[1-9][0-9]*$/.test(value) ? Number(value) : 1;
  return Number.isSafeInteger(requested) ? Math.min(requested, Math.max(1, count)) : 1;
}

export default async function CatalogProposalReviewPage({ params, searchParams }:
    PageProps<"/catalog/review/[id]">) {
  const { id } = await params;
  if (!UUID.test(id)) notFound();
  const query = await searchParams;
  let result: CatalogReviewResult = { kind: "core-unavailable" };
  let publication: PublicationReviewResult | null = null;
  let anonymous = false;
  try {
    const config = authConfiguration();
    const sessionId = (await cookies()).get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(sessionId);
    if (!session) anonymous = true;
    else {
      let historyCursor;
      try { historyCursor = factReviewHistoryCursorFromQuery(query.reviewVersion, query.reviewAfter); }
      catch { result = { kind: "invalid-review-cursor" }; }
      if (historyCursor !== undefined) result = await readCatalogProposalReview(session, config, id, new Date(),
        evidenceOffsetFromQuery(query.evidenceOffset), historyCursor);
      if (result.kind === "ready") publication = await readCatalogPublicationPreflight(session, config, {
        mode: "PROPOSAL_APPROVAL", inputId: id, inputVersion: result.review.version, inputSha256: result.review.proposalSha256,
      });
    }
  } catch { /* Do not render proposal data if authentication or Core is unavailable. */ }
  if (anonymous) redirect("/account");
  if (result.kind === "not-found") notFound();
  if (result.kind !== "ready") {
    return <main className="mx-auto max-w-3xl px-6 py-20 text-slate-100">
      <Link href="/catalog/review" className="text-sm text-cyan-200 hover:underline">← Proposal review</Link>
      <h1 className="mt-8 text-3xl font-semibold">Review unavailable</h1>
      <p className="mt-5 rounded-xl border border-amber-700 p-6 text-amber-100">{
        result.kind === "reauth-required" ? "Verify this account again before reviewing a proposal or recording a curator action." :
        result.kind === "not-granted" ? "This account does not have the scoped catalog curator role." :
        result.kind === "not-configured" ? "Catalog curator scope is not configured." :
        result.kind === "stale-review-cursor" ? "The proposal revision changed. Restart the review history for the current revision." :
        result.kind === "invalid-review-cursor" ? "The review history cursor is invalid. Restart from the first page." :
        "Core could not verify curator access or return a valid proposal review."
      }</p>
      {result.kind === "reauth-required" && <form action="/api/auth/reauth" method="post" className="mt-5">
        <button className="rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950">Verify this account again</button>
      </form>}
      {(result.kind === "stale-review-cursor" || result.kind === "invalid-review-cursor") &&
        <Link href={`/catalog/review/${id}`} className="mt-5 inline-block text-cyan-200 hover:underline">Restart review history →</Link>}
    </main>;
  }
  const { review, rejection, impact, evidence, factReviews, factReviewSummary, prerequisites } = result;
  const storedObservation = typeof query.reviewResult === "string"
    ? factReviews.items.find(item => item.reviewId === query.reviewResult) : undefined;
  const pageCount = Math.max(1, Math.ceil(review.factChanges.length / PAGE_SIZE));
  const page = boundedPage(query.page, pageCount);
  const facts = review.factChanges.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  const asOf = new Date();
  return (
    <main className="mx-auto max-w-5xl px-6 py-20 text-slate-100">
      <Link href="/catalog/review" className="text-sm text-cyan-200 hover:underline">← Proposal review</Link>
      <h1 className="mt-8 text-4xl font-semibold">Proposal {review.proposalId}</h1>
      <p className="mt-5 rounded-xl border border-amber-700 bg-amber-950/20 p-5 text-amber-100">
        This is a stored, unreviewed comparison of caller-supplied drafts. Sources have not been verified,
        the base is not trusted, and affected assessments have not been fully evaluated. Rejection does not
        change the active catalog; approval and publication are unavailable in this review screen.
      </p>
      {query.error === "stale" && <p role="alert" className="mt-5 rounded-lg border border-amber-700 p-4 text-amber-100">
        The proposal changed or this revision already has a decision. Review the current version below before trying again.
      </p>}
      {query.result === "rejected" && rejection && <p role="status" className="mt-5 rounded-lg border border-emerald-700 p-4 text-emerald-100">
        Rejection recorded with an audit event. No provider facts were published.
      </p>}
      {query.reviewError === "conflict" && <p role="alert" className="mt-5 rounded-lg border border-amber-700 p-4 text-amber-100">
        The submitted observation conflicts with the current revision, fact target or observation ID. Read the current facts and history before trying again; no new observation was confirmed.
      </p>}
      {storedObservation && <p role="status" className="mt-5 rounded-lg border border-emerald-700 p-4 text-emerald-100">
        Observation #{storedObservation.reviewNumber} is stored for this exact revision with an audit event. This is a reported human conclusion, not source verification, trust promotion or catalog approval.
      </p>}
      <section className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="summary-heading">
        <h2 id="summary-heading" className="text-2xl font-semibold">Review summary</h2>
        <dl className="mt-5 grid gap-4 sm:grid-cols-2">
          <div><dt className="text-sm text-slate-400">Current revision</dt><dd>{review.version}</dd></div>
          <div><dt className="text-sm text-slate-400">Stored at</dt><dd><time dateTime={review.recordedAt}>{review.recordedAt}</time></dd></div>
          <div><dt className="text-sm text-slate-400">Base label</dt><dd className="break-words">{review.baseCatalogVersion}</dd></div>
          <div><dt className="text-sm text-slate-400">Candidate label</dt><dd className="break-words">{review.candidateCatalogVersion}</dd></div>
          <div className="sm:col-span-2"><dt className="text-sm text-slate-400">SHA-256 of this proposal</dt>
            <dd className="break-all font-mono text-sm">{review.proposalSha256}</dd></div>
        </dl>
        <h3 className="mt-7 font-semibold">Submitted rationale</h3>
        <p className="mt-2 whitespace-pre-wrap break-words text-slate-300">{review.rationale}</p>
      </section>
      <ReviewPrerequisites report={prerequisites} />
      <PublicationPreflight result={publication} />
      <section className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="changes-heading">
        <h2 id="changes-heading" className="text-2xl font-semibold">Semantic changes</h2>
        <p className="mt-2 text-slate-300">{review.affectedOptionIds.length} affected option IDs · {review.optionChanges.length} option-scope changes · {review.factChanges.length} fact changes.</p>
        <p className="mt-3 text-sm text-slate-400">Affected: {review.affectedOptionIds.length ? review.affectedOptionIds.join(", ") : "None"}</p>
        {review.optionChanges.length > 0 && <div className="mt-6 space-y-4">
          <h3 className="text-lg font-semibold">Option scope</h3>
          {review.optionChanges.map((change, index) => <OptionChange key={`${change.optionId}-${index}`} change={change} asOf={asOf} />)}
        </div>}
        <h3 className="mt-8 text-lg font-semibold">Fact changes</h3>
        <p className="mt-2 text-sm text-slate-400">Observation-date cues for displayed changes as of <time dateTime={asOf.toISOString()}>{asOf.toISOString()}</time> use the 90-day evidence age boundary. Unchanged facts are not assessed here; these cues do not verify a source or authorize a decision.</p>
        {review.factChanges.length === 0 ? <p className="mt-3 text-slate-400">No fact changes were recorded.</p> : (
          <>
            <p className="mt-2 text-sm text-slate-400">Showing {((page - 1) * PAGE_SIZE) + 1}–{Math.min(page * PAGE_SIZE, review.factChanges.length)} of {review.factChanges.length}. Page {page} of {pageCount}.</p>
            <div className="mt-5 space-y-4">{facts.map((change, index) => <FactChange
              key={`${change.optionId}-${change.path}-${(page - 1) * PAGE_SIZE + index}`} change={change} asOf={asOf} />)}</div>
            {pageCount > 1 && <nav aria-label="Fact change pages" className="mt-6 flex gap-5 text-cyan-200">
              {page > 1 && <Link href={`/catalog/review/${id}?page=${page - 1}`} className="hover:underline">← Previous</Link>}
              {page < pageCount && <Link href={`/catalog/review/${id}?page=${page + 1}`} className="hover:underline">Next →</Link>}
            </nav>}
          </>
        )}
      </section>
      <ManualObservationSummary summary={factReviewSummary} />
      <EvidenceSection proposalId={id} evidence={evidence} review={review} summary={factReviewSummary} canRecord={!rejection} />
      <FactReviewHistorySection history={factReviews} />
      <ImpactSection proposalId={id} impact={impact} scenarioPage={boundedPage(query.impactPage,
        Math.ceil((impact?.scenarios.length ?? 0) / PAGE_SIZE))} uncoveredPage={boundedPage(query.uncoveredPage,
        Math.ceil((impact?.uncoveredChanges.length ?? 0) / PAGE_SIZE))} />
      <DecisionSection review={review} rejection={rejection} />
    </main>
  );
}

function ReviewPrerequisites({ report }: { report: CatalogReviewPrerequisites }) {
  return <section className="mt-8 rounded-xl border border-amber-700 p-6" aria-labelledby="prerequisites-heading">
    <h2 id="prerequisites-heading" className="text-2xl font-semibold">Why approval is unavailable</h2>
    <p className="mt-3 text-amber-100">Read-only prerequisites report for revision {report.proposalVersion}. This is an informational display, not an exhaustive checklist, a Core approval gate or permission to publish.</p>
    <p className="mt-3 text-sm text-slate-400">Uses whole-candidate counts, not the visible evidence or history page. Gaps can overlap; do not sum their fact counts.</p>
    <p className="mt-3 text-sm text-slate-400">Evidence dates checked at <time dateTime={report.evidenceEvaluatedAt}>{report.evidenceEvaluatedAt}</time>; manual observations included through #{report.reviewThroughNumber}; latest stored scenario report: {report.impactReportNumber === null ? "none" : `#${report.impactReportNumber}`}.</p>
    <p className="mt-2 text-sm text-slate-400">These are independent Core reads, not an atomic approval snapshot. A new observation, report, revision or rejection can change later reads. Reload before any curator action; Core rechecks write preconditions.</p>
    <ul className="mt-6 space-y-5">
      {report.gaps.map(gap => <li key={gap.code} data-prerequisite={gap.code}>
        <h3 className="font-semibold">{gap.title}{gap.factCount !== null && ` · ${gap.factCount} recorded fact${gap.factCount === 1 ? "" : "s"}`}</h3>
        <p className="mt-1 text-sm text-slate-300">{gap.explanation}</p>
        <a href={`#${gap.section}`} className="mt-1 inline-block text-sm text-cyan-200 hover:underline">View details →</a>
      </li>)}
    </ul>
    <p className="mt-6 text-amber-100">No source is fetched, date refreshed, trust promoted, assessment evaluated or catalog written by this report. Supporting human observations alone never make approval available.</p>
  </section>;
}

function FactReviewHistorySection({ history }: { history: FactReviewHistoryPage }) {
  return <section id="fact-review-history" className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="fact-review-history-heading">
    <h2 id="fact-review-history-heading" className="text-2xl font-semibold">Manual source-review history</h2>
    <p className="mt-3 text-slate-300">Historical curator observations for revision {history.proposalVersion}, in recording order. Corrections append another observation; earlier conclusions remain visible. This is not a verified fact status or approval.</p>
    <p className="mt-3 break-all text-sm text-slate-400">Revision SHA-256: {history.proposalSha256}</p>
    <p className="mt-3 text-sm text-amber-100">Recording or reading these observations does not fetch a source, refresh observedAt, promote evidence trust or publish a catalog. Actor identities are not shown.</p>
    {history.items.length === 0 ? <p className="mt-5 text-slate-300">{
      history.afterReviewNumber === 0 ? "No manual source-review observations are stored for this revision. This does not mean the facts are verified." :
        "No later observations are stored after this cursor. Return to the first page to read the history."
    }</p> : <ol className="mt-5 space-y-4">
      {history.items.map(item => <li key={item.reviewId} className="rounded-lg border border-slate-600 p-4">
        <h3 className="break-words font-medium">#{item.reviewNumber} · {item.optionId} · {item.factPath}</h3>
        <p className="mt-2 text-slate-300">{factReviewVerdictLabel(item.verdict)}</p>
        <p className="mt-2 text-sm text-slate-400">Recorded <time dateTime={item.recordedAt}>{item.recordedAt}</time>.</p>
        <p className="mt-1 break-all font-mono text-xs text-slate-400">Observation ID: {item.reviewId}</p>
      </li>)}
    </ol>}
    {(history.afterReviewNumber > 0 || history.nextAfterReviewNumber !== null) && <nav aria-label="Manual source-review history pages" className="mt-6 flex gap-5 text-cyan-200">
      {history.afterReviewNumber > 0 && <Link href={factReviewHistoryHref(history.proposalId, history.proposalVersion, 0)} className="hover:underline">← First observations</Link>}
      {history.nextAfterReviewNumber !== null && <Link href={factReviewHistoryHref(history.proposalId, history.proposalVersion, history.nextAfterReviewNumber)} className="hover:underline">Next observations →</Link>}
    </nav>}
  </section>;
}

function ManualObservationSummary({ summary }: { summary: FactReviewSummaryPage }) {
  return <section className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="observation-summary-heading">
    <h2 id="observation-summary-heading" className="text-2xl font-semibold">Manual observation coverage</h2>
    <p className="mt-3 text-slate-300">Counts cover all {summary.factCount} recorded candidate facts in revision {summary.proposalVersion}, not just this page. Each fact uses its latest recorded human observation by review number. Earlier conclusions remain in history; omitted facts remain unknown.</p>
    <p className="mt-3 text-sm text-slate-400">Observations included through review number {summary.reviewThroughNumber}. New observations can change later reads; the separately read history may be newer. Reload to refresh this summary.</p>
    <dl className="mt-5 grid gap-4 sm:grid-cols-2">
      <div><dt className="text-sm text-slate-400">No manual observation</dt><dd>{summary.counts.noObservation}</dd></div>
      <div><dt className="text-sm text-slate-400">Curator reported: source supports claim</dt><dd>{summary.counts.sourceSupportsClaim}</dd></div>
      <div><dt className="text-sm text-slate-400">Curator reported: source does not support claim</dt><dd>{summary.counts.sourceDoesNotSupportClaim}</dd></div>
      <div><dt className="text-sm text-slate-400">Curator reported: insufficient evidence</dt><dd>{summary.counts.insufficientEvidence}</dd></div>
    </dl>
    <p className="mt-5 text-amber-100">These are reported source assessments, not provider capability values or verified evidence. Even a supporting observation for every recorded fact does not establish freshness, a trusted baseline, complete coverage, eligibility, approval or publication.</p>
  </section>;
}

function EvidenceSection({ proposalId, evidence, review, summary, canRecord }: {
  proposalId: string; evidence: CandidateEvidencePage;
  review: Pick<CatalogProposalReview, "version" | "proposalSha256">; canRecord: boolean;
  summary: FactReviewSummaryPage;
}) {
  const labels = { CURRENT: "Within 90 days · unreviewed", STALE: "Older than 90 days · unreviewed",
    FUTURE: "Future date · unreviewed" };
  return <section id="candidate-evidence" className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="evidence-heading">
    <h2 id="evidence-heading" className="text-2xl font-semibold">Candidate evidence</h2>
    <p className="mt-3 text-slate-300">All recorded candidate facts, including unchanged facts. Source verification is still required; omitted facts remain unknown.</p>
    <p className="mt-3 text-sm text-slate-400">Dates checked at <time dateTime={evidence.evaluatedAt}>{evidence.evaluatedAt}</time>: {evidence.freshness.current} within 90 days · {evidence.freshness.stale} older than 90 days · {evidence.freshness.future} future dates.</p>
    {!canRecord && <p className="mt-3 text-amber-100">This revision was rejected. New source-review observations are unavailable; its existing history remains readable.</p>}
    {evidence.items.length === 0 ? <p className="mt-5 text-slate-300">No recorded facts on this page.</p> : <>
      <p className="mt-5 text-sm text-slate-400">Showing {evidence.offset + 1}–{evidence.offset + evidence.items.length} of {evidence.factCount} recorded facts.</p>
      <div className="mt-4 space-y-4">{evidence.items.map((item, index) => <article key={`${item.optionId}-${item.path}`} className="rounded-lg border border-slate-600 p-4">
        <h3 className="break-words font-medium">{item.optionId} · {item.path}</h3>
        <p className="mt-2 break-words text-sm text-slate-300">{item.scope.providerId} · {item.scope.product} · {item.scope.plan} · {item.scope.deployment} · {item.scope.region}</p>
        <p className="mt-1 break-words text-sm text-slate-400">Configuration: {item.scope.configuration}</p>
        <h4 className="mt-4 text-sm font-semibold">Submitted claim · unverified</h4>
        {candidateClaimSummary(item.claim).map(line => <p key={line} className="mt-1 break-words text-sm text-slate-300">{line}</p>)}
        <p className={`mt-3 text-sm ${item.freshness === "CURRENT" ? "text-slate-300" : "text-amber-200"}`}>{labels[item.freshness]}</p>
        <p className="mt-2 break-all text-sm text-slate-300">Unverified source: {item.evidence.sourceUrl}</p>
        <p className="mt-1 text-sm text-slate-400">Observed: <time dateTime={item.evidence.observedAt}>{item.evidence.observedAt}</time></p>
        <p className="mt-2 whitespace-pre-wrap break-words text-sm text-slate-300">{item.evidence.summary}</p>
        <p className="mt-2 break-words text-sm text-slate-400">Submitted conditions: {item.conditions.length ? item.conditions.join("; ") : "None recorded"}</p>
        <h4 className="mt-4 text-sm font-semibold">Latest reported human observation</h4>
        {summary.items[index].latestObservation ? <>
          <p className="mt-2 text-sm text-slate-300">{factReviewVerdictLabel(summary.items[index].latestObservation.verdict)} · observation #{summary.items[index].latestObservation.reviewNumber}.</p>
          <Link href={factReviewHistoryHref(proposalId, review.version,
            Math.max(0, summary.items[index].latestObservation.reviewNumber - 20))} className="mt-2 inline-block text-sm text-cyan-200 hover:underline">Read observation history →</Link>
        </> : <p className="mt-2 text-sm text-slate-400">No manual observation recorded for this fact. Its evidence remains unreviewed.</p>}
        {canRecord && <ManualFactReviewForm proposalId={proposalId} version={review.version}
          digest={review.proposalSha256} optionId={item.optionId} factPath={item.path} />}
      </article>)}</div>
    </>}
    {(evidence.offset > 0 || evidence.nextOffset !== null) && <nav aria-label="Candidate evidence pages" className="mt-6 flex gap-5 text-cyan-200">
      {evidence.offset > 0 && <Link href={`/catalog/review/${proposalId}?evidenceOffset=${Math.max(0, evidence.offset - 20)}`} className="hover:underline">← Previous evidence</Link>}
      {evidence.nextOffset !== null && <Link href={`/catalog/review/${proposalId}?evidenceOffset=${evidence.nextOffset}`} className="hover:underline">Next evidence →</Link>}
    </nav>}
  </section>;
}

function ManualFactReviewForm({ proposalId, version, digest, optionId, factPath }: {
  proposalId: string; version: number; digest: string; optionId: string; factPath: string;
}) {
  const reviewId = randomUUID();
  const verdictId = `verdict-${reviewId}`, warningId = `review-warning-${reviewId}`;
  return <details className="mt-5 border-t border-slate-600 pt-4">
    <summary className="cursor-pointer font-medium text-cyan-200">Record a manual source review</summary>
    <p id={warningId} className="mt-3 text-sm text-amber-100">Manually assess the submitted source against the claim, product, plan, region, configuration and conditions shown above. AuthWeave does not open or verify the source. This records your observation only; it does not refresh evidence, change trust, approve or publish facts. Corrections append another observation.</p>
    <form method="post" encType="application/x-www-form-urlencoded"
      action={`/api/catalog-change-proposals/${proposalId}/fact-reviews`} aria-describedby={warningId} className="mt-4 space-y-4">
      <input type="hidden" name="reviewId" value={reviewId} />
      <input type="hidden" name="expectedVersion" value={version} />
      <input type="hidden" name="expectedSha256" value={digest} />
      <input type="hidden" name="optionId" value={optionId} />
      <input type="hidden" name="factPath" value={factPath} />
      <label htmlFor={verdictId} className="block text-sm font-medium">Your manual conclusion</label>
      <select id={verdictId} name="verdict" required defaultValue="" className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2">
        <option value="" disabled>Select a conclusion</option>
        <option value="SOURCE_SUPPORTS_CLAIM">Source supports the submitted claim in this scope</option>
        <option value="SOURCE_DOES_NOT_SUPPORT_CLAIM">Source does not support the submitted claim in this scope</option>
        <option value="INSUFFICIENT_EVIDENCE">Insufficient evidence to decide</option>
      </select>
      <label className="flex gap-3 text-sm text-slate-300">
        <input type="checkbox" name="confirmation" value="MANUAL_SOURCE_REVIEW" required className="mt-1" />
        <span>I manually assessed this source, claim, scope and conditions. Record my conclusion for {optionId} · {factPath}, revision {version}, SHA-256 {digest}. This is not approval.</span>
      </label>
      <p className="break-all text-xs text-slate-400">Observation ID: {reviewId}. If the outcome is uncertain, check history and retry this original form unchanged; do not create a new observation just to retry.</p>
      <button type="submit" className="rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200">Record observation</button>
    </form>
  </details>;
}

function ImpactSection({ proposalId, impact, scenarioPage, uncoveredPage }: {
  proposalId: string; impact: CatalogImpactReview | null; scenarioPage: number; uncoveredPage: number;
}) {
  return <section className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="impact-heading">
    <h2 id="impact-heading" className="text-2xl font-semibold">Stored scenario impact</h2>
    {!impact ? <p className="mt-3 text-slate-300">No scenario impact report is stored for this exact revision. An explicit local command is required to create one; absence does not mean no impact.</p> : <>
      <p className="mt-3 text-amber-100">Historical, conditional analysis only. The base and sources are unverified, coverage is incomplete, and results may not reflect current rules. This is not approval, a recommendation or an active catalog evaluation.</p>
      <dl className="mt-5 grid gap-4 text-sm sm:grid-cols-2">
        <div><dt className="text-slate-400">Latest stored report</dt><dd>#{impact.reportNumber} · {impact.status}</dd></div>
        <div><dt className="text-slate-400">Evaluated at</dt><dd><time dateTime={impact.evaluatedAt}>{impact.evaluatedAt}</time></dd></div>
        <div><dt className="text-slate-400">Stored at</dt><dd><time dateTime={impact.recordedAt}>{impact.recordedAt}</time></dd></div>
        <div><dt className="text-slate-400">Report ID</dt><dd className="break-all font-mono">{impact.reportId}</dd></div>
        <div><dt className="text-slate-400">Case set · rule · policy</dt><dd className="break-words">{impact.caseSetVersion} · {impact.ruleVersion} · {impact.policyVersion}</dd></div>
        <div><dt className="text-slate-400">Case-set SHA-256</dt><dd className="break-all font-mono">{impact.caseSetSha256}</dd></div>
        <div className="sm:col-span-2"><dt className="text-slate-400">Report SHA-256</dt><dd className="break-all font-mono">{impact.reportSha256}</dd></div>
      </dl>
      {impact.status === "BLOCKED" ? <p className="mt-6 text-amber-100">The stored run was blocked. No hypothetical scenario evaluation was performed.</p> : <>
        <h3 className="mt-8 text-lg font-semibold">Three synthetic profiles</h3>
        <ul className="mt-3 list-disc space-y-1 pl-5 text-slate-300">
          {impact.scenarioDefinitions.map(def => <li key={def.id}><span className="font-medium">{def.id}</span>: {def.description}</li>)}
        </ul>
        <h3 className="mt-8 text-lg font-semibold">Scenario and option outcomes</h3>
        <p className="mt-2 text-sm text-slate-400">{impact.scenarios.length} recorded rows · page {scenarioPage} of {Math.max(1, Math.ceil(impact.scenarios.length / PAGE_SIZE))}. A changed conditional status is not proof of a real provider outcome.</p>
        <div className="mt-4 space-y-4">
          {impact.scenarios.slice((scenarioPage - 1) * PAGE_SIZE, scenarioPage * PAGE_SIZE).map((row, index) =>
            <article key={`${row.scenarioId}-${row.optionId}-${index}`} className="rounded-lg border border-slate-600 p-4">
              <h4 className="font-medium">{row.scenarioId} · {row.optionId}</h4>
              <p className="mt-2 break-words text-sm text-slate-300">Before: {conditionalStatusLabels[row.before]} → After: {conditionalStatusLabels[row.after]}</p>
              <p className="mt-1 text-sm text-slate-400">Conditional status changed: {row.conditionalStatusChanged ? "Yes" : "No"}. Option scope changed: {row.scopeChanged ? "Yes" : "No"}.</p>
              <p className="mt-2 break-words text-sm text-slate-400">Changed checks: {row.changedCheckIds.length ? row.changedCheckIds.join(", ") : "None"}</p>
              <p className="mt-1 break-words text-sm text-slate-400">Affected fact paths: {row.affectedFactPaths.length ? row.affectedFactPaths.join(", ") : "None"}</p>
            </article>)}
        </div>
        {impact.scenarios.length > PAGE_SIZE && <nav aria-label="Scenario impact pages" className="mt-5 flex gap-5 text-cyan-200">
          {scenarioPage > 1 && <Link href={`/catalog/review/${proposalId}?impactPage=${scenarioPage - 1}`} className="hover:underline">← Previous scenarios</Link>}
          {scenarioPage * PAGE_SIZE < impact.scenarios.length && <Link href={`/catalog/review/${proposalId}?impactPage=${scenarioPage + 1}`} className="hover:underline">Next scenarios →</Link>}
        </nav>}
        <h3 className="mt-8 text-lg font-semibold">Changes without scenario dependency</h3>
        <p className="mt-2 text-sm text-slate-400">{impact.uncoveredChanges.length} uncovered changes · page {uncoveredPage} of {Math.max(1, Math.ceil(impact.uncoveredChanges.length / PAGE_SIZE))}. Other dimensions may also be deferred; zero here does not imply complete coverage.</p>
        <ul className="mt-3 list-disc space-y-1 pl-5 text-sm text-slate-300">
          {impact.uncoveredChanges.slice((uncoveredPage - 1) * PAGE_SIZE, uncoveredPage * PAGE_SIZE).map((row, index) =>
            <li key={`${row.optionId}-${row.factPath}-${index}`} className="break-words">{row.optionId} · {row.factPath}</li>)}
        </ul>
        {impact.uncoveredChanges.length > PAGE_SIZE && <nav aria-label="Uncovered change pages" className="mt-5 flex gap-5 text-cyan-200">
          {uncoveredPage > 1 && <Link href={`/catalog/review/${proposalId}?uncoveredPage=${uncoveredPage - 1}`} className="hover:underline">← Previous uncovered changes</Link>}
          {uncoveredPage * PAGE_SIZE < impact.uncoveredChanges.length && <Link href={`/catalog/review/${proposalId}?uncoveredPage=${uncoveredPage + 1}`} className="hover:underline">Next uncovered changes →</Link>}
        </nav>}
      </>}
    </>}
  </section>;
}

function OptionChange({ change, asOf }: { change: ReviewOptionChange; asOf: Date }) {
  return <article className="rounded-lg border border-slate-600 p-4">
    <h4 className="font-medium">{change.optionId} · {change.changeType}</h4>
    <div className="mt-3 grid gap-4 sm:grid-cols-2">
      <Snapshot label="Before" value={change.before} asOf={asOf} />
      <Snapshot label="After" value={change.after} asOf={asOf} />
    </div>
  </article>;
}

function FactChange({ change, asOf }: { change: ReviewFactChange; asOf: Date }) {
  return <article className="rounded-lg border border-slate-600 p-4">
    <h4 className="break-words font-medium">{change.optionId} · {change.path}</h4>
    <p className="mt-1 text-sm text-slate-400">{change.factKind} · {change.changeType} · Changed: {change.aspects.join(", ")}</p>
    <div className="mt-3 grid gap-4 sm:grid-cols-2">
      <Snapshot label="Before" value={change.before} asOf={asOf} expectsEvidence />
      <Snapshot label="After" value={change.after} asOf={asOf} expectsEvidence />
    </div>
  </article>;
}

function Snapshot({ label, value, asOf, expectsEvidence = false }: {
  label: string; value: Record<string, unknown> | null; asOf: Date; expectsEvidence?: boolean;
}) {
  const source = sourceDetails(value);
  const observation = source ? observationDateStatus(source.observedAt, asOf) : null;
  return <div className="min-w-0 rounded-lg bg-slate-900 p-3">
    <h5 className="text-sm font-semibold text-slate-300">{label}</h5>
    {value === null ? <p className="mt-2 text-slate-400">Absent</p> : <>
      {source && <div className="mt-2 text-xs text-slate-300">
        <p className="break-all">Unverified source: {source.sourceUrl}</p>
        <p>Observed: {source.observedAt}</p>
        {observation && <p className={observation === "WITHIN_90_DAYS" ? "text-slate-300" : "text-amber-200"}>
          {observationLabels[observation]}
        </p>}
        <p className="break-words">Summary: {source.summary}</p>
      </div>}
      {expectsEvidence && !source && <p className="mt-2 text-xs text-amber-200">Source details are missing or malformed; the observation date cannot be assessed.</p>}
      <pre className="mt-3 overflow-x-auto whitespace-pre-wrap break-words text-xs text-slate-300">{JSON.stringify(value, null, 2)}</pre>
    </>}
  </div>;
}

function DecisionSection({ review, rejection }: {
  review: CatalogProposalReview; rejection: Extract<CatalogReviewResult, { kind: "ready" }>["rejection"];
}) {
  return <section className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="decision-heading">
    <h2 id="decision-heading" className="text-2xl font-semibold">Curator decision</h2>
    {rejection ? <p className="mt-4 text-slate-300">Revision {rejection.proposalVersion} was rejected: {rejection.reasonCode}.
      Recorded <time dateTime={rejection.recordedAt}>{rejection.recordedAt}</time>. Decision ID: <span className="break-all font-mono text-xs">{rejection.decisionId}</span>.</p> : <>
      <p className="mt-3 text-slate-300">Only rejection is available. It is an irreversible audit record for the exact revision and digest shown above; it does not approve or publish facts.</p>
      <form method="post" action={`/api/catalog-change-proposals/${review.proposalId}/rejection`} className="mt-6 space-y-4">
        <input type="hidden" name="expectedVersion" value={review.version} />
        <input type="hidden" name="expectedSha256" value={review.proposalSha256} />
        <div>
          <label htmlFor="reasonCode" className="block text-sm font-medium">Reason for rejection</label>
          <select id="reasonCode" name="reasonCode" required defaultValue="" className="mt-2 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2">
            <option value="" disabled>Select a reason</option>
            <option value="INSUFFICIENT_EVIDENCE">Insufficient evidence</option>
            <option value="INACCURATE_FACTS">Inaccurate facts</option>
            <option value="OUT_OF_SCOPE">Out of scope</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
        <label className="flex gap-3 text-sm text-slate-300">
          <input type="checkbox" name="confirm" value="REJECT" required className="mt-1" />
          <span>I confirm rejection of revision {review.version} with SHA-256 {review.proposalSha256}. This records a decision and cannot be undone.</span>
        </label>
        <button className="rounded-lg bg-amber-300 px-5 py-2 font-semibold text-slate-950 hover:bg-amber-200">Reject this revision</button>
      </form>
    </>}
  </section>;
}
