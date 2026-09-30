import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { readCatalogProposalReview, type CatalogReviewResult } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession } from "@/lib/auth/store";
import type { CatalogImpactReview, ScenarioImpactRow } from "@/lib/catalog/impact-review";
import { candidateClaimSummary, evidenceOffsetFromQuery, type CandidateEvidencePage } from "@/lib/catalog/evidence-review";
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
  let anonymous = false;
  try {
    const config = authConfiguration();
    const sessionId = (await cookies()).get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(sessionId);
    if (!session) anonymous = true;
    else result = await readCatalogProposalReview(session, config, id, new Date(),
      evidenceOffsetFromQuery(query.evidenceOffset));
  } catch { /* Do not render proposal data if authentication or Core is unavailable. */ }
  if (anonymous) redirect("/account");
  if (result.kind === "not-found") notFound();
  if (result.kind !== "ready") {
    return <main className="mx-auto max-w-3xl px-6 py-20 text-slate-100">
      <Link href="/catalog/review" className="text-sm text-cyan-200 hover:underline">← Proposal review</Link>
      <h1 className="mt-8 text-3xl font-semibold">Review unavailable</h1>
      <p className="mt-5 rounded-xl border border-amber-700 p-6 text-amber-100">{
        result.kind === "reauth-required" ? "Verify this account again before reviewing or rejecting a proposal." :
        result.kind === "not-granted" ? "This account does not have the scoped catalog curator role." :
        result.kind === "not-configured" ? "Catalog curator scope is not configured." :
        "Core could not verify curator access or return a valid proposal review."
      }</p>
      {result.kind === "reauth-required" && <form action="/api/auth/reauth" method="post" className="mt-5">
        <button className="rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950">Verify this account again</button>
      </form>}
    </main>;
  }
  const { review, rejection, impact, evidence } = result;
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
        change the active catalog; approval and publication are unavailable.
      </p>
      {query.error === "stale" && <p role="alert" className="mt-5 rounded-lg border border-amber-700 p-4 text-amber-100">
        The proposal changed or this revision already has a decision. Review the current version below before trying again.
      </p>}
      {query.result === "rejected" && rejection && <p role="status" className="mt-5 rounded-lg border border-emerald-700 p-4 text-emerald-100">
        Rejection recorded with an audit event. No provider facts were published.
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
      <EvidenceSection proposalId={id} evidence={evidence} />
      <ImpactSection proposalId={id} impact={impact} scenarioPage={boundedPage(query.impactPage,
        Math.ceil((impact?.scenarios.length ?? 0) / PAGE_SIZE))} uncoveredPage={boundedPage(query.uncoveredPage,
        Math.ceil((impact?.uncoveredChanges.length ?? 0) / PAGE_SIZE))} />
      <DecisionSection review={review} rejection={rejection} />
    </main>
  );
}

function EvidenceSection({ proposalId, evidence }: { proposalId: string; evidence: CandidateEvidencePage }) {
  const labels = { CURRENT: "Within 90 days · unreviewed", STALE: "Older than 90 days · unreviewed",
    FUTURE: "Future date · unreviewed" };
  return <section className="mt-8 rounded-xl border border-slate-700 p-6" aria-labelledby="evidence-heading">
    <h2 id="evidence-heading" className="text-2xl font-semibold">Candidate evidence</h2>
    <p className="mt-3 text-slate-300">All recorded candidate facts, including unchanged facts. Source verification is still required; omitted facts remain unknown.</p>
    <p className="mt-3 text-sm text-slate-400">Dates checked at <time dateTime={evidence.evaluatedAt}>{evidence.evaluatedAt}</time>: {evidence.freshness.current} within 90 days · {evidence.freshness.stale} older than 90 days · {evidence.freshness.future} future dates.</p>
    {evidence.items.length === 0 ? <p className="mt-5 text-slate-300">No recorded facts on this page.</p> : <>
      <p className="mt-5 text-sm text-slate-400">Showing {evidence.offset + 1}–{evidence.offset + evidence.items.length} of {evidence.factCount} recorded facts.</p>
      <div className="mt-4 space-y-4">{evidence.items.map(item => <article key={`${item.optionId}-${item.path}`} className="rounded-lg border border-slate-600 p-4">
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
      </article>)}</div>
    </>}
    {(evidence.offset > 0 || evidence.nextOffset !== null) && <nav aria-label="Candidate evidence pages" className="mt-6 flex gap-5 text-cyan-200">
      {evidence.offset > 0 && <Link href={`/catalog/review/${proposalId}?evidenceOffset=${Math.max(0, evidence.offset - 20)}`} className="hover:underline">← Previous evidence</Link>}
      {evidence.nextOffset !== null && <Link href={`/catalog/review/${proposalId}?evidenceOffset=${evidence.nextOffset}`} className="hover:underline">Next evidence →</Link>}
    </nav>}
  </section>;
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
