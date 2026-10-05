"use client";

import { useEffect, useRef, useState } from "react";
import { requestSavedRequirementsBrief, RequirementsBriefDownloadError } from "@/lib/assessment/requirements-brief";

const errors = {
  session: "Your session expired. Sign in again before downloading saved requirements.",
  "not-found": "This saved assessment is no longer available to your account.",
  stale: "This assessment changed since you opened it. Reload this page to review the current saved version before downloading.",
  unavailable: "The saved requirements brief could not be downloaded. Please try again later.",
};

export function SavedRequirementsExport({ assessmentId, version }: { assessmentId: string; version: number }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);

  async function download() {
    if (active.current) return;
    const controller = new AbortController();
    active.current = controller;
    setBusy(true); setError(null); setMessage(null);
    let objectUrl: string | null = null;
    let link: HTMLAnchorElement | null = null;
    try {
      const result = await requestSavedRequirementsBrief(assessmentId, version, controller.signal);
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(new Blob([result.markdown], { type: "text/markdown;charset=utf-8" }));
      link = document.createElement("a"); link.href = objectUrl; link.download = result.filename;
      document.body.appendChild(link); link.click();
      setMessage(`Download requested for saved version ${version}. Check your browser's downloads.`);
    } catch (error) {
      if (!controller.signal.aborted) setError(errors[error instanceof RequirementsBriefDownloadError ? error.kind : "unavailable"]);
    } finally {
      link?.remove();
      if (objectUrl) {
        const downloadedUrl = objectUrl;
        window.setTimeout(() => URL.revokeObjectURL(downloadedUrl), 30_000);
      }
      if (!controller.signal.aborted) { active.current = null; setBusy(false); }
    }
  }

  return <section aria-labelledby="saved-export-heading" className="rounded-xl border border-white/10 bg-white/[0.025] p-5">
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div className="max-w-xl">
        <h3 id="saved-export-heading" className="text-lg font-semibold">Take your saved requirements with you</h3>
        <p className="mt-2 text-sm leading-6 text-slate-300">Download a Markdown brief of the five sections below, including unrecorded inputs. It uses saved version {version}, not unsaved edits.</p>
      </div>
      <button type="button" disabled={busy} onClick={download}
        className="rounded-lg bg-cyan-300 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-200 disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">
        {busy ? "Preparing saved brief…" : "Download saved brief (.md)"}
      </button>
    </div>
    <p className="mt-3 text-xs leading-5 text-slate-400">Not a full-profile backup, final ADR or provider recommendation. Temporary what-if answers and comparison results are excluded. Review the downloaded file for private details before sharing.</p>
    {error && <p role="alert" className="mt-4 rounded-lg border border-amber-700 p-3 text-sm text-amber-100">{error}</p>}
    <p aria-live="polite" className="mt-3 text-xs text-cyan-100">{message}</p>
  </section>;
}
