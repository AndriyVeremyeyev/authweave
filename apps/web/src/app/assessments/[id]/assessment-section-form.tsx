"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { postProfileSection, profileReloadPath, profileSaveFeedback, profileSaveSections, profileFormIssues,
  type ProfileSaveResult, type ProfileSection } from "@/lib/assessment/profile-save";
import type { SectionFormIssue } from "@/lib/assessment/section-form-validation";
import { useAssessmentSave } from "./assessment-workflow";

export function AssessmentSectionForm({ section, action, children }: {
  section: ProfileSection; action: string; children: ReactNode;
}) {
  const [issues, setIssues] = useState<SectionFormIssue[]>([]);
  const [result, setResult] = useState<Exclude<ProfileSaveResult, "saved"> | null>(null);
  const [pending, setPending] = useState(false);
  const inFlight = useRef(false);
  const reloadRequired = useRef(false);
  const lifecycle = useAssessmentSave();
  const summary = useRef<HTMLDivElement>(null);
  const saveSummary = useRef<HTMLDivElement>(null);
  const reloadDialog = useRef<HTMLDialogElement>(null);
  const reloadButton = useRef<HTMLButtonElement | null>(null);
  useEffect(() => { if (issues.length > 0) summary.current?.focus(); }, [issues]);
  useEffect(() => { if (result) saveSummary.current?.focus(); }, [result]);
  const mustReload = result !== null && result !== "invalid";
  const reloadPath = profileReloadPath(section, action);
  const copy = profileSaveSections[section];

  function loadCurrent() {
    if (!reloadPath || inFlight.current || !reloadDialog.current?.open) return;
    reloadDialog.current.close();
    lifecycle?.allowReload();
    window.location.assign(reloadPath);
  }

  function cancelReload() {
    reloadDialog.current?.close();
    reloadButton.current?.focus();
  }

  return <form action={action} method="post" className="mt-6 space-y-6" aria-busy={pending}
    onChange={() => { setIssues([]); if (result === "invalid") setResult(null); }} onSubmit={async event => {
      if (inFlight.current || reloadRequired.current || mustReload) { event.preventDefault(); return; }
      const params = new URLSearchParams();
      for (const [name, value] of new FormData(event.currentTarget)) {
        if (typeof value === "string") params.append(name, value);
      }
      const found = profileFormIssues(section, params);
      setIssues(found);
      if (found.length > 0) { event.preventDefault(); return; }
      // Outside the workflow provider, keep the progressively enhanced native form.
      if (!lifecycle) return;
      event.preventDefault();
      const submittedForm = event.currentTarget;
      inFlight.current = true; setPending(true); lifecycle.setSaving(true); setResult(null);
      try {
        const outcome = await postProfileSection(section, action, params);
        if (!submittedForm.isConnected) return;
        if (outcome !== "invalid") reloadRequired.current = true;
        if (outcome === "saved" && reloadPath) {
          lifecycle.allowReload(); window.location.assign(reloadPath);
        } else setResult(outcome === "saved" ? "uncertain" : outcome);
      } finally {
        inFlight.current = false; setPending(false); lifecycle.setSaving(false);
      }
    }}>
    {issues.length > 0 && <div ref={summary} role="alert" tabIndex={-1}
      aria-labelledby={`${section}-form-errors-heading`}
      className="rounded-xl border border-amber-700 p-4 text-sm leading-6 text-amber-100 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-200">
      <h3 id={`${section}-form-errors-heading`} className="font-semibold">Check these inputs before saving</h3>
      <p className="mt-2">Nothing was sent. Your edits are still in this form; correcting them does not save automatically.</p>
      <ul className="mt-3 list-disc space-y-2 pl-5">{issues.map((issue, index) => <li key={index}>
        {issue.fieldId ? <a href={`#${issue.fieldId}`} className="block rounded underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-200"
          onClick={event => {
            event.preventDefault();
            const field = event.currentTarget.closest("form")?.querySelector<HTMLElement>(`#${issue.fieldId}`);
            const details = field?.closest("details");
            if (details) details.open = true;
            field?.focus();
          }}>{issue.message}</a> : issue.message}
      </li>)}</ul>
    </div>}
    {result && <div ref={saveSummary} role="alert" tabIndex={-1} aria-labelledby={`${section}-save-result-heading`}
      className="rounded-xl border border-amber-700 p-4 text-sm leading-6 text-amber-100 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-200">
      <h3 id={`${section}-save-result-heading`} className="font-semibold">{result === "invalid" && section === "usage" ? "These usage inputs were rejected" : profileSaveFeedback[result].title}</h3>
      <p className="mt-2">{profileSaveFeedback[result].text}</p>
      <p className="mt-2 text-xs">The saved summaries and input check still belong to the version originally loaded in this tab, not the current server version.</p>
      {reloadPath && <button type="button" onClick={event => {
        if (inFlight.current || !reloadDialog.current || reloadDialog.current.open) return;
        reloadButton.current = event.currentTarget;
        reloadDialog.current.showModal();
      }}
        className="mt-4 rounded-lg border border-amber-300/50 px-3 py-2 font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-200">Load current saved version</button>}
    </div>}
    {pending && <p role="status" className="text-sm text-cyan-200">Saving {copy.label} inputs… Do not close this tab until the response arrives.</p>}
    <fieldset disabled={pending} className="min-w-0 space-y-6"><legend className="sr-only">{section === "usage" ? "Usage planning fields" : `${copy.label} fields`}</legend>{children}</fieldset>
    <button type="submit" disabled={pending || mustReload}
      className="rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200 disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">{pending ? `Saving ${copy.label} inputs…` : copy.save}</button>
    <dialog ref={reloadDialog} aria-labelledby={`${section}-reload-heading`} aria-describedby={`${section}-reload-description`}
      onCancel={event => { event.preventDefault(); cancelReload(); }}
      className="m-auto w-[min(32rem,calc(100%_-_2rem))] rounded-2xl border border-slate-600 bg-slate-900 p-6 text-slate-100 shadow-2xl backdrop:bg-black/70">
      <h2 id={`${section}-reload-heading`} className="text-xl font-semibold">Discard these inputs and load the current version?</h2>
      <p id={`${section}-reload-description`} className="mt-3 text-sm leading-6 text-slate-300">Your unsaved {copy.label} inputs in this tab will be discarded. Copy anything you need first. Loading the current saved version does not merge or save these edits.</p>
      <div className="mt-6 flex flex-wrap gap-3">
        <button type="button" onClick={cancelReload}
          className="rounded-lg bg-cyan-300 px-4 py-2 text-sm font-semibold text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Keep my edits</button>
        <button type="button" onClick={loadCurrent}
          className="rounded-lg border border-slate-500 px-4 py-2 text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Discard and load current version</button>
      </div>
    </dialog>
  </form>;
}
