"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { architecturePrerequisiteByteLimit, boundedPrerequisiteText, parsePrerequisiteForm, prerequisiteAnalysis, prerequisiteIds,
  type ArchitecturePatternId, type PrerequisiteAnalysis, type PrerequisiteInput, type PrerequisitePreview } from "@/lib/assessment/architecture-prerequisites";
import { ArchitectureDesignFollowUps } from "./architecture-design-follow-ups";

const statusText: Record<PrerequisiteAnalysis["status"], string> = {
  CONDITIONALLY_MATCHES: "Conditions met in your proposed design only",
  CONDITIONALLY_DOES_NOT_MATCH: "At least one condition is not met in your proposed design",
  NEEDS_INFORMATION: "More information is needed",
  NOT_APPLICABLE: "This client type is not selected in the assessment",
};
const outcomeText: Record<PrerequisiteAnalysis["checks"][number]["outcome"], string> = {
  CONDITIONALLY_SATISFIED: "Declared met — unverified",
  CONDITIONALLY_NOT_SATISFIED: "Declared not met — unverified",
  UNKNOWN: "Unknown — clarify this condition or select the client type",
  NOT_APPLICABLE: "Not applicable to the selected clients",
};

export function ArchitecturePrerequisites({ assessmentId, version, patternId, descriptions, clientScope }: {
  assessmentId: string; version: number; patternId: ArchitecturePatternId; descriptions: string[];
  clientScope: PrerequisiteAnalysis["clientScope"];
}) {
  const [preview, setPreview] = useState<PrerequisitePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => {
    active.current?.abort();
    active.current = null;
  }, []);
  const ids = prerequisiteIds[patternId];

  function cancelPreview() {
    if (!active.current) return;
    active.current.abort();
    active.current = null;
    setPending(false);
    setError("Preview canceled. Your declarations are still here; preview again when ready.");
  }

  function changeDeclaration() {
    active.current?.abort();
    active.current = null;
    setPending(false);
    setPreview(null);
    setError(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (active.current) return;
    setPreview(null); setError(null);
    const params = new URLSearchParams();
    let input: PrerequisiteInput;
    try {
      for (const [key, value] of new FormData(event.currentTarget)) {
        if (typeof value !== "string") throw new Error();
        params.append(key, value);
      }
      input = parsePrerequisiteForm(params);
      if (input.expectedVersion !== version || input.patternId !== patternId) throw new Error();
    } catch {
      setError("Choose valid declarations for this pattern.");
      return;
    }
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]);
    active.current = controller;
    setPending(true);
    let failureMessage = "The preview is temporarily unavailable. Try again.";
    try {
      const response = await fetch(`/api/assessments/${assessmentId}/architecture-prerequisites`, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: params.toString(), credentials: "same-origin", cache: "no-store", redirect: "error", signal,
      });
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (response.status !== 200) {
        const messages: Record<number, string> = {
          401: "Your session expired. Sign in again.", 403: "This request is not allowed.",
          404: "This assessment is unavailable.", 409: "The assessment changed. Reload before previewing again.",
          400: "Choose valid declarations for this pattern.",
        };
        setError(messages[response.status] ?? failureMessage);
        return;
      }
      failureMessage = "The preview could not be read safely. Try again.";
      const body = JSON.parse(await boundedPrerequisiteText(response, architecturePrerequisiteByteLimit, signal));
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (!body || Object.keys(body).length !== 2 || body.assessmentVersion !== version) throw new Error();
      const analysis = prerequisiteAnalysis(body.analysis, input, clientScope);
      setPreview({ assessmentVersion: version, analysis });
    } catch {
      if (!controller.signal.aborted) setError(signal.aborted ?
        "The preview took too long. Your declarations are still here; try again." : failureMessage);
    } finally {
      if (active.current === controller) { active.current = null; setPending(false); }
    }
  }

  return <details className="mt-4 text-sm text-slate-300">
    <summary className="cursor-pointer font-medium">Try design conditions — temporary what-if</summary>
    <p className="mt-3">Describe your proposed design, not verified deployment settings. Answers and results are temporary and are not saved. No IdP configuration is read or changed.</p>
    {clientScope !== "SELECTED" && <p className="mt-3 rounded-lg border border-amber-300/20 bg-amber-300/5 p-3 text-amber-100">
      {clientScope === "UNKNOWN" ? "Client types are not recorded. These declarations cannot establish a match; select and save clients in Context first."
        : "This client type is not selected. These declarations cannot make the pattern applicable; review saved Context first."}
    </p>}
    <form className="mt-4 space-y-4" onSubmit={submit} onChange={changeDeclaration} aria-busy={pending}>
      <input type="hidden" name="expectedVersion" value={version} />
      <input type="hidden" name="patternId" value={patternId} />
      <fieldset disabled={pending} className="space-y-4">
        <legend className="mb-3 font-medium">Unverified design conditions</legend>
        {ids.map((id, index) => <div key={id}>
          <label htmlFor={`${patternId}-${id}`} className="block">{descriptions[index]}</label>
          <select id={`${patternId}-${id}`} name={id} defaultValue="UNKNOWN" className="mt-2 w-full scroll-mt-6 rounded-lg border border-slate-600 bg-slate-900 p-2 focus-visible:outline-2 focus-visible:outline-cyan-200">
            <option value="UNKNOWN">Unknown / not yet assessed</option>
            <option value="SATISFIED">Met in proposed design (unverified)</option>
            <option value="NOT_SATISFIED">Not met in proposed design (unverified)</option>
          </select>
        </div>)}
        <button type="submit" className="rounded-lg border border-cyan-700 px-4 py-2 text-cyan-100 disabled:opacity-50">{pending ? "Previewing…" : "Preview conditions"}</button>
      </fieldset>
      {pending && <div className="flex flex-wrap items-center gap-3">
        <p role="status">Previewing temporary conditions. Inputs are locked until this finishes or you cancel.</p>
        <button type="button" onClick={cancelPreview}
          className="rounded-lg border border-slate-500 px-4 py-2 hover:bg-slate-800">Cancel preview</button>
      </div>}
    </form>
    <div aria-live="polite" aria-atomic="true" className="mt-4">
      {!preview && !error && !pending && <p className="text-xs text-slate-400">No current what-if result. Choose declarations and preview them; changing an answer clears the result. Nothing is saved.</p>}
      {error && <p role="alert" className="text-amber-100">{error}</p>}
      {preview && <div className="rounded-lg border border-slate-600 p-4">
        <p className="font-medium text-cyan-200">{statusText[preview.analysis.status]}</p>
        <ArchitectureDesignFollowUps clientScope={preview.analysis.clientScope} kind="conditions"
          checks={preview.analysis.checks.map((check, index) => ({ fieldId: `${patternId}-${check.prerequisiteId}`,
            label: descriptions[index], outcome: check.outcome }))} />
        <ul className="mt-3 space-y-3">{preview.analysis.checks.map((check, index) => <li key={check.prerequisiteId}>
          <p>{descriptions[index]}</p><p className="text-slate-400">{outcomeText[check.outcome]} ({check.reasonCode})</p>
        </li>)}</ul>
        <p className="mt-4 text-amber-100">Conditional results do not override the separate two-criterion preflight above. Configuration and provider compatibility remain unverified. This is not a recommendation, approval or ready-to-deploy design.</p>
      </div>}
    </div>
  </details>;
}
