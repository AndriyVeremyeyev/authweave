"use client";

import { useEffect, useRef, useState } from "react";
import { DecisionBriefDownloadError, requestSavedDecisionBrief } from "@/lib/assessment/decision-brief";
import type { ResultReference } from "@/lib/assessment/decision-results";

const errors = {
  session: "Your session expired. Sign in again before downloading this calculation.",
  "not-found": "This saved calculation is no longer available to your account.",
  unavailable: "This exact historical calculation could not be verified for download. No newer result was substituted. Please try again later.",
};

export function SavedResultExport({ assessmentId, reference }: { assessmentId: string; reference: ResultReference }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null), [message, setMessage] = useState<string | null>(null);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);
  async function download() {
    if (active.current) return;
    const controller = new AbortController(); active.current = controller;
    setBusy(true); setError(null); setMessage(null);
    let objectUrl: string | null = null, link: HTMLAnchorElement | null = null;
    try {
      const result = await requestSavedDecisionBrief(assessmentId, reference, controller.signal);
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(new Blob([result.markdown], { type: "text/markdown;charset=utf-8" }));
      link = document.createElement("a"); link.href = objectUrl; link.download = result.filename;
      document.body.appendChild(link); link.click();
      setMessage(`Download requested for calculation version ${reference.version}. Check your browser's downloads.`);
    } catch (error) {
      if (!controller.signal.aborted) setError(errors[error instanceof DecisionBriefDownloadError ? error.kind : "unavailable"]);
    } finally {
      link?.remove();
      if (objectUrl) { const downloadedUrl = objectUrl; window.setTimeout(() => URL.revokeObjectURL(downloadedUrl), 30_000); }
      if (!controller.signal.aborted) { active.current = null; setBusy(false); }
    }
  }
  return <section aria-labelledby="result-export-heading" className="mt-8 rounded-xl border border-white/10 p-5">
    <h2 id="result-export-heading" className="text-xl font-semibold">Take this saved calculation with you</h2>
    <p className="mt-3 text-sm leading-6 text-slate-300">Download a compact Markdown brief of calculation version {reference.version}: scoped verdicts, blockers, original weights, conditional architecture and next actions. The exact result and digest are checked again, not replaced with the latest calculation.</p>
    <button type="button" disabled={busy} onClick={download}
      className="mt-4 rounded-lg bg-cyan-300 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-200 disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">
      {busy ? "Preparing calculation brief…" : "Download calculation brief (.md)"}
    </button>
    <p className="mt-3 text-xs leading-5 text-slate-400">Not a final ADR, full-profile backup or approval. Detailed per-check evidence stays on this saved page; raw profile and review/audit records are excluded. Temporary what-if comparisons are not included. Review private details before sharing; downloading does not publish or save anything.</p>
    {error && <p role="alert" className="mt-4 rounded-lg border border-amber-700 p-3 text-sm text-amber-100">{error}</p>}
    <p aria-live="polite" className="mt-3 text-xs text-cyan-100">{message}</p>
  </section>;
}
