import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { readCatalogProposalReview, type CatalogReviewResult } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession } from "@/lib/auth/store";
import { sourceDetails, type CatalogProposalReview, type ReviewFactChange,
  type ReviewOptionChange } from "@/lib/catalog/proposal-review";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PAGE_SIZE = 20;

export default async function CatalogProposalReviewPage({ params, searchParams }:
    PageProps<"/catalog/review/[id]">) {
  const { id } = await params;
  if (!UUID.test(id)) notFound();
  let result: CatalogReviewResult = { kind: "core-unavailable" };
  let anonymous = false;
  try {
    const config = authConfiguration();
    const sessionId = (await cookies()).get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(sessionId);
    if (!session) anonymous = true;
    else result = await readCatalogProposalReview(session, config, id);
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
  const { review, rejection } = result;
  const query = await searchParams;
  const requestedPage = typeof query.page === "string" && /^[1-9][0-9]*$/.test(query.page)
    ? Number(query.page) : 1;
  const pageCount = Math.max(1, Math.ceil(review.factChanges.length / PAGE_SIZE));
  const page = Number.isSafeInteger(requestedPage) ? Math.min(requestedPage, pageCount) : 1;
  const facts = review.factChanges.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
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
          {review.optionChanges.map((change, index) => <OptionChange key={`${change.optionId}-${index}`} change={change} />)}
        </div>}
        <h3 className="mt-8 text-lg font-semibold">Fact changes</h3>
        {review.factChanges.length === 0 ? <p className="mt-3 text-slate-400">No fact changes were recorded.</p> : (
          <>
            <p className="mt-2 text-sm text-slate-400">Showing {((page - 1) * PAGE_SIZE) + 1}–{Math.min(page * PAGE_SIZE, review.factChanges.length)} of {review.factChanges.length}. Page {page} of {pageCount}.</p>
            <div className="mt-5 space-y-4">{facts.map((change, index) => <FactChange
              key={`${change.optionId}-${change.path}-${(page - 1) * PAGE_SIZE + index}`} change={change} />)}</div>
            {pageCount > 1 && <nav aria-label="Fact change pages" className="mt-6 flex gap-5 text-cyan-200">
              {page > 1 && <Link href={`/catalog/review/${id}?page=${page - 1}`} className="hover:underline">← Previous</Link>}
              {page < pageCount && <Link href={`/catalog/review/${id}?page=${page + 1}`} className="hover:underline">Next →</Link>}
            </nav>}
          </>
        )}
      </section>
      <DecisionSection review={review} rejection={rejection} />
    </main>
  );
}

function OptionChange({ change }: { change: ReviewOptionChange }) {
  return <article className="rounded-lg border border-slate-600 p-4">
    <h4 className="font-medium">{change.optionId} · {change.changeType}</h4>
    <div className="mt-3 grid gap-4 sm:grid-cols-2">
      <Snapshot label="Before" value={change.before} />
      <Snapshot label="After" value={change.after} />
    </div>
  </article>;
}

function FactChange({ change }: { change: ReviewFactChange }) {
  return <article className="rounded-lg border border-slate-600 p-4">
    <h4 className="break-words font-medium">{change.optionId} · {change.path}</h4>
    <p className="mt-1 text-sm text-slate-400">{change.factKind} · {change.changeType} · Changed: {change.aspects.join(", ")}</p>
    <div className="mt-3 grid gap-4 sm:grid-cols-2">
      <Snapshot label="Before" value={change.before} />
      <Snapshot label="After" value={change.after} />
    </div>
  </article>;
}

function Snapshot({ label, value }: { label: string; value: Record<string, unknown> | null }) {
  const source = sourceDetails(value);
  return <div className="min-w-0 rounded-lg bg-slate-900 p-3">
    <h5 className="text-sm font-semibold text-slate-300">{label}</h5>
    {value === null ? <p className="mt-2 text-slate-400">Absent</p> : <>
      {source && <div className="mt-2 text-xs text-slate-300">
        <p className="break-all">Unverified source: {source.sourceUrl}</p>
        <p>Observed: {source.observedAt}</p>
        <p className="break-words">Summary: {source.summary}</p>
      </div>}
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
