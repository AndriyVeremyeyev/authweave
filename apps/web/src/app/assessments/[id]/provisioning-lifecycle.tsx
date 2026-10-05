"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { boundedPrerequisiteText } from "@/lib/assessment/architecture-prerequisites";
import { lifecycleAnalysis, lifecycleByteLimit, lifecycleConditions, lifecycleDescriptions, lifecyclePatterns, parseLifecycleForm,
  type LifecycleAnalysis, type LifecycleInput, type LifecyclePattern, type LifecyclePreview, type ProvisioningRequirements } from "@/lib/assessment/provisioning-lifecycle";
import { AssessmentStepButton } from "./assessment-workflow";

export const lifecycleReasonText: Record<string, string> = {
  REQUIRED_MECHANISM_PLANNED: "Required mechanism is planned — implementation unverified",
  REQUIRED_MECHANISM_ABSENT: "Required mechanism is missing from this design",
  FORBIDDEN_MECHANISM_PLANNED: "This design plans a forbidden mechanism",
  FORBIDDEN_MECHANISM_ABSENT: "Forbidden mechanism is not planned — implementation unverified",
  REQUIREMENT_UNKNOWN: "Saved requirement is unknown — clarify it in Requirements",
  PREFERENCE_NOT_SCORED: "Preference is not scored — not a passed check",
  NO_REQUIREMENT: "Not required — not a passed check",
  GROUP_LIFECYCLE_UNASSESSED: "Group synchronization is required but remains unassessed",
  GROUP_PROHIBITION_UNASSESSED: "The group synchronization prohibition remains unassessed",
  DECLARED_CONDITION_SATISFIED: "Declared met in proposed design — unverified",
  DECLARED_CONDITION_NOT_SATISFIED: "Declared not met in proposed design — unverified",
  CONDITION_UNKNOWN: "Condition is unknown — more information needed",
};
const requirementLabels: Record<keyof ProvisioningRequirements, string> = {
  scim: "SCIM provisioning", justInTimeProvisioning: "Login-time JIT", groupSynchronization: "Group synchronization",
};
const statusText: Record<LifecycleAnalysis["status"], string> = {
  CONDITIONALLY_MATCHES: "Matches your proposed design only — unverified",
  CONDITIONALLY_DOES_NOT_MATCH: "This proposed design has a mismatch",
  NEEDS_INFORMATION: "More information is needed",
};

export function ProvisioningLifecycle({ assessmentId, version, requirements }: {
  assessmentId: string; version: number; requirements: ProvisioningRequirements;
}) {
  return <section className="mt-8 border-t border-white/10 pt-8" aria-labelledby="lifecycle-heading">
    <p className="text-xs font-semibold uppercase tracking-wider text-cyan-200">Separate provisioning design check · Saved version {version}</p>
    <h2 id="lifecycle-heading" className="mt-3 text-2xl font-semibold">How will accounts be created, updated and offboarded?</h2>
    <p className="mt-3 text-sm leading-6 text-slate-300">SCIM delivers lifecycle changes independently of login. JIT creates or updates an account during a trusted login. A hybrid uses both and needs a collision policy. Compare these three examples; display order is not a ranking or a saved choice.</p>
    <dl className="mt-4 grid gap-3 sm:grid-cols-3">{Object.entries(requirementLabels).map(([key, label]) => <div key={key} className="rounded-lg border border-white/10 p-3">
      <dt className="text-xs text-slate-400">Saved {label}</dt><dd className="mt-2 text-sm">{requirements[key as keyof ProvisioningRequirements].replaceAll("_", " ").toLowerCase()}</dd>
    </div>)}</dl>
    <div className="mt-4"><AssessmentStepButton step="capabilities">Review saved Requirements →</AssessmentStepButton></div>
    <p className="mt-4 rounded-lg border border-amber-300/20 p-4 text-sm text-amber-100">JIT cannot replace required SCIM. Selecting SCIM does not prove group delivery, role mapping or revocation of existing sessions and tokens. This check does not override the client/token preflight above.</p>
    <ul className="mt-5 space-y-4">{lifecyclePatterns.map(pattern => <li key={pattern.patternId} className="min-w-0 rounded-xl border border-white/10 p-5">
      <h3 className="text-lg font-semibold">{pattern.displayName}</h3>
      <div className="mt-3 grid gap-4 text-sm text-slate-300 sm:grid-cols-2">
        <div><h4 className="font-medium">Advantages</h4><ul className="mt-2 list-disc pl-5">{pattern.advantages.map(text => <li key={text}>{text}</li>)}</ul></div>
        <div><h4 className="font-medium">Trade-offs</h4><ul className="mt-2 list-disc space-y-2 pl-5">{pattern.tradeoffs.map(text => <li key={text}>{text}</li>)}</ul></div>
      </div>
      <LifecycleConditions key={`${assessmentId}-${version}-${pattern.patternId}`} assessmentId={assessmentId} version={version}
        patternId={pattern.patternId} requirements={requirements} />
      <details className="mt-4 text-xs text-slate-400"><summary className="cursor-pointer">Concept references (not provider compatibility evidence)</summary>
        <ul className="mt-2 space-y-2">{pattern.references.map((url, index) => <li key={url}><a href={url} target="_blank" rel="noopener noreferrer" className="text-cyan-200 underline">{url.includes("rfc7644") ? "SCIM operations" : url.includes("rfc7643") ? "SCIM schemas" : "ZITADEL login-time creation example"} ({index + 1})</a></li>)}</ul>
      </details>
    </li>)}</ul>
  </section>;
}

export function LifecycleConditions({ assessmentId, version, patternId, requirements }: {
  assessmentId: string; version: number; patternId: LifecyclePattern; requirements: ProvisioningRequirements;
}) {
  const [preview, setPreview] = useState<LifecyclePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => { active.current?.abort(); active.current = null; }, []);
  function change() {
    active.current?.abort(); active.current = null;
    setPending(false); setPreview(null); setError(null);
  }
  function cancel() {
    if (!active.current) return;
    active.current.abort(); active.current = null; setPending(false);
    setError("Preview canceled. Your declarations are still here; preview again when ready.");
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (active.current) return;
    setPreview(null); setError(null);
    const params = new URLSearchParams();
    let input: LifecycleInput;
    try {
      for (const [key, value] of new FormData(event.currentTarget)) {
        if (typeof value !== "string") throw new Error();
        params.append(key, value);
      }
      input = parseLifecycleForm(params);
      if (input.patternId !== patternId || input.expectedVersion !== version) throw new Error();
    } catch { setError("Choose valid declarations for this provisioning pattern."); return; }
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]);
    active.current = controller; setPending(true);
    let failure = "The provisioning preview is temporarily unavailable. Try again.";
    try {
      const response = await fetch(`/api/assessments/${assessmentId}/provisioning-lifecycle`, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: params.toString(),
        credentials: "same-origin", cache: "no-store", redirect: "error", signal,
      });
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (response.status !== 200) {
        const messages: Record<number, string> = { 401: "Your session expired. Sign in again.", 403: "This request is not allowed.",
          404: "This assessment is unavailable.", 409: "The assessment changed. Reload before previewing again.",
          400: "Choose valid declarations for this provisioning pattern." };
        setError(messages[response.status] ?? failure); return;
      }
      failure = "The provisioning preview could not be read safely. Try again.";
      const body = JSON.parse(await boundedPrerequisiteText(response, lifecycleByteLimit));
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (!body || Object.keys(body).length !== 2 || body.assessmentVersion !== version) throw new Error();
      setPreview({ assessmentVersion: version, analysis: lifecycleAnalysis(body.analysis, input, requirements) });
    } catch {
      if (!controller.signal.aborted) setError(signal.aborted ? "The preview took too long. Your declarations are still here; try again." : failure);
    } finally { if (active.current === controller) { active.current = null; setPending(false); } }
  }
  return <details className="mt-4 text-sm text-slate-300">
    <summary className="cursor-pointer font-medium">Try provisioning conditions — temporary what-if</summary>
    <p className="mt-3">Describe a proposed design, not verified deployment settings. Answers and results are not saved. No IdP configuration is read or changed.</p>
    <form className="mt-4 space-y-4" onSubmit={submit} onChange={change} aria-busy={pending}>
      <input type="hidden" name="expectedVersion" value={version} /><input type="hidden" name="patternId" value={patternId} />
      <fieldset disabled={pending} className="space-y-4"><legend className="mb-3 font-medium">Unverified provisioning conditions</legend>
        {lifecycleConditions[patternId].map(id => <div key={id}><label htmlFor={`lifecycle-${patternId}-${id}`} className="block">{lifecycleDescriptions[id]}</label>
          <select id={`lifecycle-${patternId}-${id}`} name={id} defaultValue="UNKNOWN" className="mt-2 w-full rounded-lg border border-slate-600 bg-slate-900 p-2">
            <option value="UNKNOWN">Unknown / not yet assessed</option><option value="SATISFIED">Met in proposed design (unverified)</option><option value="NOT_SATISFIED">Not met in proposed design (unverified)</option>
          </select></div>)}
        <button type="submit" className="rounded-lg border border-cyan-700 px-4 py-2 text-cyan-100 disabled:opacity-50">{pending ? "Previewing…" : "Preview provisioning design"}</button>
      </fieldset>
      {pending && <div className="flex flex-wrap items-center gap-3"><p role="status">Previewing temporary conditions. Inputs are locked until this finishes or you cancel.</p>
        <button type="button" onClick={cancel} className="rounded-lg border border-slate-500 px-4 py-2 hover:bg-slate-800">Cancel preview</button></div>}
    </form>
    <div aria-live="polite" aria-atomic="true" className="mt-4">
      {!preview && !error && !pending && <p className="text-xs text-slate-400">No current what-if result. Changing an answer clears the result. Nothing is saved.</p>}
      {error && <p role="alert" className="text-amber-100">{error}</p>}
      {preview && <div className="rounded-lg border border-slate-600 p-4"><p className="font-medium text-cyan-200">{statusText[preview.analysis.status]}</p>
        <h4 className="mt-4 font-medium">Saved requirement checks</h4>
        <ul className="mt-2 space-y-3">{preview.analysis.requirementChecks.map(check => <li key={check.profilePath}>
          <p>{requirementLabels[check.profilePath.slice("provisioning.".length) as keyof ProvisioningRequirements]} · {check.criticality.toLowerCase().replaceAll("_", " ")}</p>
          <p className="text-slate-400">{lifecycleReasonText[check.reasonCode]}</p></li>)}</ul>
        <h4 className="mt-4 font-medium">Temporary condition checks</h4>
        <ul className="mt-2 space-y-3">{preview.analysis.conditionChecks.map(check => <li key={check.conditionId}><p>{lifecycleDescriptions[check.conditionId]}</p>
          <p className="text-slate-400">{lifecycleReasonText[check.reasonCode]}</p></li>)}</ul>
        <p className="mt-4 text-amber-100">Provider operations and entitlements, actual delivery, group membership and authorization, session/token revocation and failure recovery remain unverified. This is not a recommendation, approval or ready-to-deploy design.</p>
      </div>}
    </div>
  </details>;
}
