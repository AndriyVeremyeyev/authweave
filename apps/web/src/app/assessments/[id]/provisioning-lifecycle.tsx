"use client";

import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { boundedPrerequisiteText } from "@/lib/assessment/architecture-prerequisites";
import { lifecyclePatterns, type LifecyclePattern, type ProvisioningRequirements } from "@/lib/assessment/provisioning-lifecycle";
import { lifecycleV2Analysis, lifecycleV2ByteLimit, lifecycleV2PatternConditions, lifecycleGroupConditions, lifecycleGroupStrategies,
  lifecycleV2Descriptions, lifecycleOffboardingReferences, parseLifecycleV2Form, type LifecycleGroupStrategy,
  type LifecycleV2Analysis, type LifecycleV2Input, type LifecycleV2Preview, type LifecycleV2Condition } from "@/lib/assessment/provisioning-lifecycle-v2";
import { AssessmentStepButton } from "./assessment-workflow";

export const lifecycleReasonText: Record<string, string> = {
  REQUIRED_MECHANISM_PLANNED: "Required mechanism is planned — implementation unverified",
  REQUIRED_MECHANISM_ABSENT: "Required mechanism is missing from this design",
  FORBIDDEN_MECHANISM_PLANNED: "This design plans a forbidden mechanism",
  FORBIDDEN_MECHANISM_ABSENT: "Forbidden mechanism is not planned — implementation unverified",
  REQUIREMENT_UNKNOWN: "Saved requirement is unknown — clarify it in Requirements",
  PREFERENCE_NOT_SCORED: "Preference is not scored — not a passed check",
  NO_REQUIREMENT: "Not required — not a passed check",
  GROUP_STRATEGY_UNKNOWN: "Group strategy is unknown — select a design or explicitly choose no synchronization",
  GROUP_TRANSPORT_PLANNED: "Group delivery is planned — operations and enforcement unverified",
  NO_GROUP_TRANSPORT_PLANNED: "No group delivery is planned — not a passed synchronization check",
  SCIM_GROUPS_REQUIRE_SCIM_PATTERN: "This preview's SCIM Group option needs a SCIM user-lifecycle pattern; JIT-only does not match this option",
  DECLARED_CONDITION_SATISFIED: "Declared met in proposed design — unverified",
  DECLARED_CONDITION_NOT_SATISFIED: "Declared not met in proposed design — unverified",
  CONDITION_UNKNOWN: "Condition is unknown — more information needed",
};
const requirementLabels: Record<keyof ProvisioningRequirements, string> = {
  scim: "SCIM provisioning", justInTimeProvisioning: "Login-time JIT", groupSynchronization: "Group synchronization",
};
const statusText: Record<LifecycleV2Analysis["status"], string> = {
  CONDITIONALLY_MATCHES: "Matches your proposed design only — unverified",
  CONDITIONALLY_DOES_NOT_MATCH: "This proposed design has a mismatch",
  NEEDS_INFORMATION: "More information is needed",
};
const conditionLabels: Record<LifecycleV2Condition, string> = {
  TENANT_AND_SUBJECT_CORRELATION: "Tenant and subject correlation",
  ATTRIBUTE_OWNERSHIP_AND_MAPPING: "Attribute ownership and mapping",
  ACCOUNT_DISABLE_AND_LOGIN_BLOCK: "Account disablement and new login",
  APPLICATION_SESSION_INVALIDATION: "Application session invalidation",
  TOKEN_REVOCATION_OR_BOUNDED_EXPIRY: "Token revocation or bounded expiry",
  FAILURE_RECOVERY_AND_RECONCILIATION: "Failure recovery and reconciliation",
  SCIM_CLIENT_SERVER_DIRECTION: "SCIM client/server direction",
  SCIM_USER_OPERATIONS: "SCIM User operations",
  JIT_TRUSTED_LOGIN_AND_LINKING: "Trusted JIT login and account linking",
  SCIM_JIT_COLLISION_POLICY: "SCIM/JIT collision policy",
  GROUP_SOURCE_AND_MEMBERSHIP_MAPPING: "Group source and membership mapping",
  GROUP_CHANGE_DELIVERY_AND_RECONCILIATION: "Group change delivery and reconciliation",
  GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT: "Group-to-role mapping and enforcement",
  GROUP_REMOVAL_AND_ACCESS_RECHECK: "Group removal and access recheck",
  SCIM_GROUP_OPERATIONS: "SCIM Group operations",
  APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY: "Application bridge authorization and idempotency",
};
const fieldId = (pattern: LifecyclePattern, field: LifecycleV2Condition | "groupStrategy") => `lifecycle-${pattern}-${field}`;
const followUpLink = "text-cyan-100 underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200";

/** Present already-checked outcomes without inferring answers or reevaluating requirements. */
export function LifecycleFollowUps({ analysis }: { analysis: LifecycleV2Analysis }) {
  const mismatches = analysis.requirementChecks.filter(check => check.outcome === "CONDITIONALLY_NOT_SATISFIED");
  const savedUnknown = analysis.requirementChecks.filter(check => check.reasonCode === "REQUIREMENT_UNKNOWN");
  const groupGaps = analysis.designChecks.filter(check => check.outcome === "UNKNOWN" || check.outcome === "CONDITIONALLY_NOT_SATISFIED");
  const unmet = analysis.conditionChecks.filter(check => check.outcome === "CONDITIONALLY_NOT_SATISFIED");
  const unknown = analysis.conditionChecks.filter(check => check.outcome === "UNKNOWN");
  return <section aria-label="Provisioning preview follow-ups" className="mt-4 rounded-lg border border-white/10 p-3 text-sm">
    <h4 className="font-medium">Next steps for this provisioning preview</h4>
    {mismatches.length > 0 && <div className="mt-3">
      <p className="font-medium text-amber-100">Design conflicts with saved requirements ({mismatches.length})</p>
      <p className="mt-2 text-xs leading-5 text-slate-400">Reconsider the proposed lifecycle or group plan. Declaring conditions met cannot supply a missing mechanism or remove a forbidden one. Required SCIM cannot be replaced by JIT; saved requirements stay unchanged.</p>
      <ul className="mt-2 list-disc space-y-2 pl-5">{mismatches.map(check => <li key={check.profilePath}>
        <p>{requirementLabels[check.profilePath.slice("provisioning.".length) as keyof ProvisioningRequirements]} · {check.criticality.toLowerCase()}</p>
        <p className="text-slate-400">{lifecycleReasonText[check.reasonCode]}</p>
        {check.profilePath === "provisioning.groupSynchronization" && <a href={`#${fieldId(analysis.patternId, "groupStrategy")}`} className={followUpLink}>Revisit the temporary group strategy</a>}
      </li>)}</ul>
    </div>}
    {savedUnknown.length > 0 && <div className="mt-3">
      <p className="font-medium text-amber-100">Saved requirements to clarify ({savedUnknown.length})</p>
      <ul className="mt-2 list-disc space-y-2 pl-5">{savedUnknown.map(check => <li key={check.profilePath}>
        {requirementLabels[check.profilePath.slice("provisioning.".length) as keyof ProvisioningRequirements]}
      </li>)}</ul>
      <p className="mt-2 text-xs leading-5 text-slate-400">These are unknown saved requirements, not unanswered temporary conditions. Review Requirements, clarify and save before previewing again.</p>
      <div className="mt-2"><AssessmentStepButton step="capabilities">Review saved Requirements →</AssessmentStepButton></div>
    </div>}
    {groupGaps.length > 0 && <div className="mt-3">
      <p className="font-medium text-amber-100">Group plan to revisit</p>
      {groupGaps.map(check => <p key={check.boundary} className="mt-2 text-slate-300">{lifecycleReasonText[check.reasonCode]}</p>)}
      <a href={`#${fieldId(analysis.patternId, "groupStrategy")}`} className={followUpLink}>Review how groups reach the application</a>
    </div>}
    {[{ label: "Declared not met", rows: unmet }, { label: "Unknown temporary conditions", rows: unknown }]
      .filter(group => group.rows.length > 0).map(group => <div key={group.label} className="mt-3">
        <p className="font-medium text-amber-100">{group.label} ({group.rows.length})</p>
        <ul className="mt-2 list-disc space-y-2 pl-5">{group.rows.map(check => <li key={check.conditionId} className="break-words">
          <a href={`#${fieldId(analysis.patternId, check.conditionId)}`} className={followUpLink}>{conditionLabels[check.conditionId]}</a>
        </li>)}</ul>
      </div>)}
    {mismatches.length + savedUnknown.length + groupGaps.length + unmet.length + unknown.length === 0 &&
      <p className="mt-2 leading-6 text-slate-300">No unmet or unknown checks in this temporary preview. Declared matches and not-applied checks are not verification or a recommendation.</p>}
    <p className="mt-3 text-xs leading-5 text-slate-400">Links return to this pattern&apos;s exact fields; they do not select an answer. Changing a proposal clears this result, so preview again. Nothing is saved. Full checks below remain visible; provider support, actual delivery and access revocation remain unverified.</p>
  </section>;
}

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
  const [preview, setPreview] = useState<LifecycleV2Preview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [groupStrategy, setGroupStrategy] = useState<LifecycleGroupStrategy>("UNKNOWN");
  const group = lifecycleGroupStrategies.find(g => g.groupStrategy === groupStrategy)!;
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
  function selectGroup(event: ChangeEvent<HTMLSelectElement>) {
    event.stopPropagation();
    change();
    const value = event.currentTarget.value;
    if (!Object.hasOwn(lifecycleGroupConditions, value)) { setError("Choose a valid group strategy."); return; }
    setGroupStrategy(value as LifecycleGroupStrategy);
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (active.current) return;
    setPreview(null); setError(null);
    const params = new URLSearchParams();
    let input: LifecycleV2Input;
    try {
      for (const [key, value] of new FormData(event.currentTarget)) {
        if (typeof value !== "string") throw new Error();
        params.append(key, value);
      }
      input = parseLifecycleV2Form(params);
      if (input.patternId !== patternId || input.expectedVersion !== version || input.groupStrategy !== groupStrategy) throw new Error();
    } catch { setError("Choose valid declarations for this provisioning pattern."); return; }
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]);
    active.current = controller; setPending(true);
    let failure = "The provisioning preview is temporarily unavailable. Try again.";
    try {
      const response = await fetch(`/api/assessments/${assessmentId}/provisioning-lifecycle-v2`, {
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
      const body = JSON.parse(await boundedPrerequisiteText(response, lifecycleV2ByteLimit, signal));
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (!body || Object.keys(body).length !== 2 || body.assessmentVersion !== version) throw new Error();
      setPreview({ assessmentVersion: version, analysis: lifecycleV2Analysis(body.analysis, input, requirements) });
    } catch {
      if (!controller.signal.aborted) setError(signal.aborted ? "The preview took too long. Your declarations are still here; try again." : failure);
    } finally { if (active.current === controller) { active.current = null; setPending(false); } }
  }
  const condition = (id: LifecycleV2Condition, key: string = id) => <div key={key}><label htmlFor={fieldId(patternId, id)} className="block">{lifecycleV2Descriptions[id]}</label>
    <select id={fieldId(patternId, id)} name={id} defaultValue="UNKNOWN" className="mt-2 w-full scroll-mt-8 rounded-lg border border-slate-600 bg-slate-900 p-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
      <option value="UNKNOWN">Unknown / not yet assessed</option><option value="SATISFIED">Met in proposed design (unverified)</option><option value="NOT_SATISFIED">Not met in proposed design (unverified)</option>
    </select></div>;
  return <details className="mt-4 text-sm text-slate-300">
    <summary className="cursor-pointer font-medium">Try provisioning conditions — temporary what-if</summary>
    <p className="mt-3">Describe a proposed design, not verified deployment settings. Answers and results are not saved. No IdP configuration is read or changed.</p>
    <form className="mt-4 space-y-4" onSubmit={submit} onChange={change} aria-busy={pending}>
      <input type="hidden" name="expectedVersion" value={version} /><input type="hidden" name="patternId" value={patternId} />
      <fieldset disabled={pending} className="space-y-4"><legend className="mb-3 font-medium">Unverified provisioning and offboarding design</legend>
        <div><label htmlFor={fieldId(patternId, "groupStrategy")} className="block font-medium">How will group memberships reach the application?</label>
          <select id={fieldId(patternId, "groupStrategy")} name="groupStrategy" value={groupStrategy} onChange={selectGroup}
            className="mt-2 w-full scroll-mt-8 rounded-lg border border-slate-600 bg-slate-900 p-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
            {lifecycleGroupStrategies.map(g => <option key={g.groupStrategy} value={g.groupStrategy}>{g.displayName}</option>)}
          </select>
          <p className="mt-2 text-xs text-slate-400">Unknown is not the same as no synchronization. This choice is temporary and does not select a provider.</p>
        </div>
        <div className="rounded-lg border border-slate-700 p-3" aria-live="polite">
          <p className="font-medium">{group.displayName}</p>
          {group.advantages.length > 0 && <div className="mt-2"><p className="font-medium">Advantages</p><ul className="list-disc pl-5">{group.advantages.map(t => <li key={t}>{t}</li>)}</ul></div>}
          <p className="mt-2 font-medium">Trade-offs / limits</p><ul className="mt-1 list-disc space-y-2 pl-5">{group.tradeoffs.map(t => <li key={t}>{t}</li>)}</ul>
        </div>
        <h4 className="font-medium">Account lifecycle and existing access</h4>
        <p className="text-xs text-slate-400">Account disablement, application sessions and issued tokens have separate paths. Describe the accepted residual-access window; these answers do not test it.</p>
        {lifecycleV2PatternConditions[patternId].map(id => condition(id))}
        {group.conditions.length > 0 && <fieldset className="space-y-4"><legend className="mb-3 font-medium">Group delivery and application authorization</legend>
          <p className="text-xs text-slate-400">Changing the group strategy resets its conditions to Unknown. Account lifecycle answers stay here, but the current result is cleared.</p>
          {group.conditions.map(id => condition(id, `${groupStrategy}-${id}`))}
        </fieldset>}
        <button type="submit" className="rounded-lg border border-cyan-700 px-4 py-2 text-cyan-100 disabled:opacity-50">{pending ? "Previewing…" : "Preview provisioning design"}</button>
      </fieldset>
      {pending && <div className="flex flex-wrap items-center gap-3"><p role="status">Previewing temporary conditions. Inputs are locked until this finishes or you cancel.</p>
        <button type="button" onClick={cancel} className="rounded-lg border border-slate-500 px-4 py-2 hover:bg-slate-800">Cancel preview</button></div>}
    </form>
    <div aria-live="polite" aria-atomic="true" className="mt-4">
      {!preview && !error && !pending && <p className="text-xs text-slate-400">No current what-if result. Changing an answer clears the result. Nothing is saved.</p>}
      {error && <p role="alert" className="text-amber-100">{error}</p>}
      {preview && <div className="rounded-lg border border-slate-600 p-4"><p className="font-medium text-cyan-200">{statusText[preview.analysis.status]}</p>
        <p className="mt-2">Temporary group plan: {group.displayName}</p>
        <LifecycleFollowUps analysis={preview.analysis} />
        <h4 className="mt-4 font-medium">Group transport design</h4>
        <ul className="mt-2 space-y-2">{preview.analysis.designChecks.map(check => <li key={check.boundary}>{lifecycleReasonText[check.reasonCode]}</li>)}</ul>
        <h4 className="mt-4 font-medium">Saved requirement checks</h4>
        <ul className="mt-2 space-y-3">{preview.analysis.requirementChecks.map(check => <li key={check.profilePath}>
          <p>{requirementLabels[check.profilePath.slice("provisioning.".length) as keyof ProvisioningRequirements]} · {check.criticality.toLowerCase().replaceAll("_", " ")}</p>
          <p className="text-slate-400">{lifecycleReasonText[check.reasonCode]}</p></li>)}</ul>
        <h4 className="mt-4 font-medium">Temporary condition checks</h4>
        <ul className="mt-2 space-y-3">{preview.analysis.conditionChecks.map(check => <li key={check.conditionId}><p>{lifecycleV2Descriptions[check.conditionId]}</p>
          <p className="text-slate-400">{lifecycleReasonText[check.reasonCode]}</p></li>)}</ul>
        <p className="mt-4 text-amber-100">Provider operations and entitlements, actual delivery, group membership and authorization, session/token revocation and failure recovery remain unverified. This is not a recommendation, approval or ready-to-deploy design.</p>
      </div>}
    </div>
    <details className="mt-4 text-xs text-slate-400"><summary className="cursor-pointer">Group / offboarding concept references (not implementation evidence)</summary>
      <ul className="mt-2 space-y-2">{[...group.references, ...lifecycleOffboardingReferences].map(url => <li key={url}><a href={url} target="_blank" rel="noopener noreferrer" className="text-cyan-200 underline">
        {url.includes("rfc7009") ? "OAuth token revocation and enforcement limits" : url.includes("section-4.2") ? "SCIM Group schema" : url.includes("rfc7644") ? "SCIM operations" : "SCIM account status"}</a></li>)}</ul>
    </details>
  </details>;
}
