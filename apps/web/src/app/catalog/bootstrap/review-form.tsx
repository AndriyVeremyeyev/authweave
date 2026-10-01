"use client";

import { useRef, useState, type FormEvent } from "react";
import { bootstrapCandidate, bootstrapReceiptFromCore, bootstrapReceiptHref, bootstrapReviewSubmission,
  bootstrapVerdicts, type BootstrapPreparation, type BootstrapReceipt, type BootstrapReviewInput } from "@/lib/catalog/bootstrap-review";
import { candidateClaimSummary } from "@/lib/catalog/evidence-review";

const labels = {
  SOURCE_SUPPORTS_CLAIM: "Source supports this exact claim",
  SOURCE_DOES_NOT_SUPPORT_CLAIM: "Source does not support this claim",
  INSUFFICIENT_EVIDENCE: "Evidence is insufficient",
};
const messages: Record<string, string> = {
  invalid: "The candidate or review is invalid. Use the complete provider-catalog-draft.v1 contract and one explicit verdict per recorded fact.",
  conflict: "Core rejected this review: the bootstrap boundary is closed or this UUID conflicts with an existing review. Nothing was overwritten.",
  "not-granted": "This account does not have the scoped catalog curator role.",
  "reauth-required": "Verify the same account again in a separate tab, then retry here without reloading this form.",
  "core-rejected": "Core rejected the curator assertion. Verify the same account again before retrying.",
  "not-configured": "Catalog curator scope is not configured.",
};
async function post(path: string, body: unknown): Promise<Response> {
  return fetch(path, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body), cache: "no-store", redirect: "error", signal: AbortSignal.timeout(15_000) });
}

export default function BootstrapReviewForm() {
  const [draft, setDraft] = useState("");
  const [prepared, setPrepared] = useState<BootstrapPreparation | null>(null);
  const [verdicts, setVerdicts] = useState<Record<string, string>>({});
  const [confirmed, setConfirmed] = useState(false);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [submission, setSubmission] = useState<BootstrapReviewInput | null>(null);
  const [receipt, setReceipt] = useState<BootstrapReceipt | null>(null);
  const [message, setMessage] = useState("");

  async function prepare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current) return;
    busy.current = true; setPending(true); setMessage("");
    try {
      let candidate;
      try { candidate = bootstrapCandidate(JSON.parse(draft)); } catch { /* Report a safe local validation error. */ }
      if (!candidate) { setMessage("Enter a complete draft JSON document, no larger than 1 MiB of UTF-8."); return; }
      const response = await post("/api/catalog-bootstrap-reviews/prepare", candidate);
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        setMessage(messages[body?.kind] ?? "Preparation is unavailable. Sign in with a fresh scoped curator session and check Core availability.");
        return;
      }
      setPrepared(await response.json() as BootstrapPreparation); setVerdicts({}); setConfirmed(false);
    } catch { setMessage("Preparation is unavailable. No review was submitted."); }
    finally { busy.current = false; setPending(false); }
  }

  async function record(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current || !prepared || !confirmed) return;
    const input = submission ?? bootstrapReviewSubmission(prepared, verdicts, confirmed);
    if (!input) { setMessage("Choose a verdict for every recorded fact and explicitly confirm the complete manual review."); return; }
    // Freeze the whole payload before the first write. Network failure must not silently mint a new retry key or assertions.
    busy.current = true; setSubmission(input); setPending(true); setMessage(""); setConfirmed(false);
    try {
      const response = await post("/api/catalog-bootstrap-reviews", input);
      if (response.status !== 200 && response.status !== 201) {
        const body = await response.json().catch(() => null);
        setMessage(messages[body?.kind] ?? "The write outcome is unconfirmed: the review may already be stored. Retry this exact UUID and payload after checking Core availability.");
        return;
      }
      setReceipt(bootstrapReceiptFromCore(await response.json(), { id: input.reviewId }, input));
    } catch { setMessage("The write outcome is unconfirmed: the review may already be stored. Retry this exact UUID and payload; do not create another review or reload this tab."); }
    finally { busy.current = false; setPending(false); }
  }

  if (receipt) return <section className="mt-8 rounded-xl border border-cyan-700 p-6" aria-label="Bootstrap receipt">
    <h2 className="text-xl font-semibold">Manual bootstrap review recorded</h2>
    <p className="mt-3">Supporting: {receipt.counts.supporting} · Contradicting: {receipt.counts.contradicting} · Insufficient: {receipt.counts.insufficient}</p>
    <p className="mt-3 break-all text-sm">Review UUID: {receipt.reviewId}<br />Review SHA-256: {receipt.reviewSha256}</p>
    <p className="mt-3 text-slate-300">No source verification, trust promotion, approval or catalog publication was performed.</p>
    <a href={bootstrapReceiptHref(receipt)} className="mt-4 inline-block text-cyan-200 hover:underline">Read this exact stored receipt →</a>
  </section>;

  return <section className="mt-8">
    {message && <p role="alert" className="mb-6 rounded-xl border border-amber-700 p-4 text-amber-100">{message}</p>}
    {!prepared ? <form onSubmit={prepare}>
      <label htmlFor="bootstrap-draft" className="block text-xl font-semibold">Import the first candidate</label>
      <p className="mt-3 text-sm text-slate-300">Paste a complete provider-catalog-draft.v1 JSON document (at most 1 MiB UTF-8). Do not include secrets or personal data. Preparation is read-only; it does not fetch sources or save a review.</p>
      <textarea id="bootstrap-draft" required rows={14} value={draft} onChange={event => setDraft(event.target.value)}
        disabled={pending} maxLength={1024 * 1024} spellCheck={false}
        className="mt-4 w-full rounded-xl border border-slate-600 bg-slate-900 p-4 font-mono text-sm" />
      <button disabled={pending} className="mt-4 rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 disabled:opacity-50">{pending ? "Preparing…" : "Prepare manual review"}</button>
    </form> : <form onSubmit={record}>
      <h2 className="text-2xl font-semibold">Review every recorded fact</h2>
      <p className="mt-3">Catalog: {prepared.candidate.catalogVersion} · {prepared.facts.length} facts · Assessed at {prepared.evaluatedAt}</p>
      <p className="mt-3 break-all text-sm text-slate-400">Review UUID: {prepared.reviewId}<br />Candidate draft SHA-256: {prepared.candidateSha256}</p>
      <p className="mt-4 text-slate-300">Open and assess each source yourself. Dates describe freshness only, not source truth. A supporting verdict for UNKNOWN does not make a capability available; absent facts remain unknown. All evidence stays UNREVIEWED.</p>
      <p className="mt-3 text-sm text-slate-400">{Object.keys(verdicts).length} / {prepared.facts.length} explicit conclusions. No conclusion is selected by default.</p>
      {submission && <p className="mt-4 rounded-xl border border-amber-700 p-4 text-amber-100">This UUID and complete payload are frozen for exact retries. Keep this tab open; reloading loses the in-memory retry payload. <a href="/account" target="_blank" rel="noopener noreferrer" className="underline">Open account in another tab</a> to verify the same account again if needed. Retries still require fresh authorization.</p>}
      <div className="mt-6 space-y-5">{prepared.facts.map((fact, index) => {
        const key = `${fact.optionId}\0${fact.path}`;
        return <fieldset key={key} disabled={pending || !!submission} className="rounded-xl border border-slate-700 p-5">
          <legend className="break-all px-2 font-semibold">{index + 1}. {fact.optionId} · {fact.path}</legend>
          <p className="text-sm text-slate-300">{fact.scope.providerId} · {fact.scope.product} · {fact.scope.plan} · {fact.scope.deployment} · {fact.scope.region} · {fact.scope.configuration}</p>
          {candidateClaimSummary(fact.claim).map(line => <p key={line} className="mt-3 font-medium">{line}</p>)}
          <p className="mt-3 text-sm">Evidence: UNREVIEWED · Date assessment: {fact.freshness} · Observed at {fact.evidence.observedAt}</p>
          <a href={fact.evidence.sourceUrl} target="_blank" rel="noopener noreferrer" className="mt-3 inline-block break-all text-cyan-200 underline">{fact.evidence.sourceUrl}</a>
          <p className="mt-3 whitespace-pre-wrap text-slate-300">{fact.evidence.summary}</p>
          <p className="mt-3 text-sm text-slate-400">Conditions: {fact.conditions.length ? fact.conditions.join("; ") : "None recorded"}</p>
          <div className="mt-4 space-y-2">{bootstrapVerdicts.map(verdict => <label key={verdict} className="flex items-start gap-3">
            <input type="radio" name={`fact-${index}`} required value={verdict} checked={verdicts[key] === verdict}
              onChange={() => setVerdicts(current => ({ ...current, [key]: verdict }))} className="mt-1" />{labels[verdict]}
          </label>)}</div>
        </fieldset>;
      })}</div>
      <label className="mt-8 flex items-start gap-3 rounded-xl border border-amber-700 p-5">
        <input type="checkbox" required checked={confirmed} onChange={event => setConfirmed(event.target.checked)} disabled={pending} className="mt-1" />
        {submission ? "I explicitly confirm retrying this exact complete manual bootstrap source review with the same UUID, candidate and conclusions." :
          "I manually assessed every recorded claim and source and confirm this complete bootstrap source review. This is not independent source verification, trust promotion, approval or publication."}
      </label>
      <button disabled={pending || !confirmed || Object.keys(verdicts).length !== prepared.facts.length}
        className="mt-5 rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 disabled:opacity-50">{pending ? "Recording…" : submission ? "Retry this exact review" : "Record manual bootstrap review"}</button>
      {!submission && <button type="button" disabled={pending} onClick={() => { setPrepared(null); setVerdicts({}); setConfirmed(false); setMessage(""); }}
        className="ml-4 mt-5 text-cyan-200 underline">Change candidate (discard these unsaved conclusions)</button>}
    </form>}
  </section>;
}
