"use client";

import { useState } from "react";
import { auditCriteria, maximumRetentionDays, type AuditabilityValues } from "@/lib/assessment/auditability";
import { auditConceptReferences, auditCriticalityGuidance, auditGuidance, auditRetentionGuidance } from "@/lib/assessment/audit-guidance";
import { criticalities } from "@/lib/assessment/capabilities";

export function AuditabilityEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: AuditabilityValues;
}) {
  const [retentionSelected, setRetentionSelected] = useState(values.selectedCriteria.includes("AUDIT_LOG_RETENTION"));
  const labels = { UNKNOWN: "Unknown", REQUIRED: "Required", PREFERRED: "Preferred", NOT_REQUIRED: "Not required", FORBIDDEN: "Forbidden" };
  return (
    <section className="mt-6" aria-labelledby="auditability-heading">
      <h2 id="auditability-heading" className="text-2xl font-semibold">Identity auditability requirements</h2>
      <p className="mt-3 text-sm leading-6 text-slate-300">Choose which identity-provider logging capabilities matter to this application. The criticality applies to every selected criterion. No selection means unresolved scope, not a logging exemption.</p>
      <p className="mt-2 text-sm leading-6 text-slate-400">These are requested capabilities, not verified logs, configuration or compliance. They are not AuthWeave&apos;s own change history. Current provider previews still defer auditability; saving these inputs does not expand their coverage or produce a recommendation.</p>
      <form action={`/api/assessments/${assessmentId}/auditability`} method="post" className="mt-6 space-y-6">
        <input type="hidden" name="expectedVersion" value={version} />
        <section aria-labelledby="audit-level-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="audit-level-heading" className="text-lg font-semibold">Choose the shared requirement level</h3>
          <p id="audit-level-description" className="mt-2 text-sm leading-6 text-slate-400">One level applies to all selected criteria. Selecting a checkbox records scope; it does not turn logging on.</p>
          <label htmlFor="auditability-criticality" className="mb-2 mt-5 block text-sm font-medium">Criticality of the selected criteria</label>
          <select id="auditability-criticality" name="criticality" defaultValue={values.criticality}
            aria-describedby="audit-level-description"
            className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
            {criticalities.map(value => <option key={value} value={value}>{labels[value]}</option>)}
          </select>
          <details className="mt-4 text-sm leading-6">
            <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">How the five levels apply to auditability</summary>
            <dl className="mt-3 space-y-3">{criticalities.map(value => <div key={value}>
              <dt className="font-medium">{labels[value]}</dt><dd className="mt-1 text-slate-300">{auditCriticalityGuidance[value]}</dd>
            </div>)}</dl>
          </details>
        </section>
        {(["events", "records"] as const).map(group => <fieldset key={group}
          aria-describedby={`audit-${group}-description`} className="min-w-0 rounded-xl border border-white/10 p-4 sm:p-5">
          <legend className="px-1 text-lg font-semibold">{group === "events" ? "Identity events to investigate" : "Access and retain the records"}</legend>
          <p id={`audit-${group}-description`} className="text-sm leading-6 text-slate-400">{group === "events"
            ? "Choose the provider-side event types your team needs to investigate. Application business logs are a separate source."
            : "Export and retention answer different questions. Selecting one does not imply the other."}</p>
          <div className="mt-5 grid items-start gap-4 sm:grid-cols-2">
            {auditCriteria.filter(criterion => auditGuidance[criterion.key].group === group).map(criterion => {
              const guide = auditGuidance[criterion.key];
              return <div key={criterion.key} className="min-w-0 rounded-lg border border-white/10 p-4">
                <label htmlFor={`audit-${criterion.key}`} className="flex items-start gap-3 text-sm font-medium">
                  <input id={`audit-${criterion.key}`} name="selectedCriteria" type="checkbox" value={criterion.key}
                    defaultChecked={values.selectedCriteria.includes(criterion.key)} aria-describedby={`audit-help-${criterion.key}`}
                    className="mt-1 shrink-0 accent-cyan-300"
                    onChange={criterion.key === "AUDIT_LOG_RETENTION" ? event => setRetentionSelected(event.target.checked) : undefined} />
                  {criterion.label}
                </label>
                <p id={`audit-help-${criterion.key}`} className="mt-3 text-sm leading-6 text-slate-300">{criterion.help}</p>
                <details className="mt-3 text-sm leading-6">
                  <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Example &amp; limits<span className="sr-only">: {criterion.label}</span></summary>
                  <dl className="mt-3 space-y-3">
                    <div><dt className="font-medium">Example</dt><dd className="mt-1 text-slate-300">{guide.example}</dd></div>
                    <div><dt className="font-medium">Limits &amp; trade-offs</dt><dd className="mt-1 text-slate-300">{guide.limits}</dd></div>
                    <div><dt className="font-medium">Ask your team</dt><dd className="mt-1 text-slate-300">{guide.question}</dd></div>
                  </dl>
                </details>
              </div>;
            })}
          </div>
        </fieldset>)}
        <section aria-labelledby="audit-retention-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="audit-retention-heading" className="text-lg font-semibold">Define the provider retention minimum</h3>
          <p className="mt-2 text-sm leading-6 text-slate-400">This input is enabled only when Log retention is selected. There is no suggested duration or automatic answer.</p>
          <label htmlFor="audit-retention-days" className="mb-2 mt-5 block text-sm font-medium">Requested minimum retention (days)</label>
          <input id="audit-retention-days" name="minimumRetentionDays" type="number" min={1} max={maximumRetentionDays} step={1}
            defaultValue={values.minimumRetentionDays ?? ""} disabled={!retentionSelected} required={retentionSelected}
            aria-describedby="audit-retention-help" className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200" />
          <p id="audit-retention-help" className="mt-2 text-sm leading-6 text-slate-400">Enter 1–36,500 whole days only when retention is selected. There is no default; 36,500 is a project validation bound, not a standard. Unselecting retention clears its saved duration when you save.</p>
          <details className="mt-4 text-sm leading-6">
            <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">How the minimum-duration check works</summary>
            <dl className="mt-3 space-y-3">
              <div><dt className="font-medium">Example, not a default</dt><dd className="mt-1 text-slate-300">{auditRetentionGuidance.example}</dd></div>
              <div><dt className="font-medium">Limits &amp; trade-offs</dt><dd className="mt-1 text-slate-300">{auditRetentionGuidance.limits}</dd></div>
              <div><dt className="font-medium">Ask your team</dt><dd className="mt-1 text-slate-300">{auditRetentionGuidance.question}</dd></div>
            </dl>
          </details>
        </section>
        <details className="rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-4 text-sm leading-6">
          <summary className="cursor-pointer font-medium text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">What this form cannot verify</summary>
          <p className="mt-3 text-slate-300">Recording these requirements does not verify event content or client/user coverage, integrity, access controls, logging failures, export delivery and retrieval, deployed configuration or compliance evidence.</p>
          <p className="mt-3 text-slate-400">Plan record scope, privacy, redaction and disposal with the responsible team. Do not use passwords or access tokens as log examples. These six project criteria are not a NIST baseline or a compliance assessment.</p>
          <ul className="mt-3 space-y-2">{auditConceptReferences.map(reference => <li key={reference.href}>
            <a href={reference.href} target="_blank" rel="noopener noreferrer" className="text-xs text-cyan-200 underline underline-offset-4">Concept reference: {reference.title}<span className="sr-only"> (opens in a new tab)</span></a>
          </li>)}</ul>
          <p className="mt-3 text-xs text-slate-400">References explain logging concepts. They do not establish a provider&apos;s capability, plan, configuration or approved evidence.</p>
        </details>
        <p className="text-sm leading-6 text-slate-400">To clear the recorded scope, uncheck all six criteria and save. Other profile fields remain unchanged. Opening explanations does not save anything.</p>
        <button type="submit" className="rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Save auditability requirements</button>
      </form>
    </section>
  );
}
