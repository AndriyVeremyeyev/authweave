"use client";

import { useState } from "react";
import { auditCriteria, maximumRetentionDays, type AuditabilityValues } from "@/lib/assessment/auditability";
import { criticalities } from "@/lib/assessment/capabilities";

export function AuditabilityEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: AuditabilityValues;
}) {
  const [retentionSelected, setRetentionSelected] = useState(values.selectedCriteria.includes("AUDIT_LOG_RETENTION"));
  const labels = { UNKNOWN: "Unknown", REQUIRED: "Required", PREFERRED: "Preferred", NOT_REQUIRED: "Not required", FORBIDDEN: "Forbidden" };
  return (
    <section className="mt-10 rounded-xl border border-slate-700 p-6" aria-labelledby="auditability-heading">
      <h2 id="auditability-heading" className="text-2xl font-semibold">Identity auditability requirements</h2>
      <p className="mt-3 text-slate-300">Choose which identity-provider logging capabilities matter to this application. The criticality applies to every selected criterion. No selection means unresolved scope, not a logging exemption.</p>
      <p className="mt-2 text-sm text-slate-400">These are requested capabilities, not verified logs, configuration or compliance. They are not AuthWeave&apos;s own change history. Current provider previews still defer auditability; saving these inputs does not expand their coverage or produce a recommendation.</p>
      <form action={`/api/assessments/${assessmentId}/auditability`} method="post" className="mt-6">
        <input type="hidden" name="expectedVersion" value={version} />
        <label htmlFor="auditability-criticality" className="mb-2 block text-sm font-medium">Criticality of the selected criteria</label>
        <select id="auditability-criticality" name="criticality" defaultValue={values.criticality}
          className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2">
          {criticalities.map(value => <option key={value} value={value}>{labels[value]}</option>)}
        </select>
        <fieldset className="mt-6 space-y-4">
          <legend className="mb-3 font-medium">Explicit logging scope</legend>
          {auditCriteria.map(criterion => (
            <div key={criterion.key}>
              <label htmlFor={`audit-${criterion.key}`} className="flex items-center gap-3">
                <input id={`audit-${criterion.key}`} name="selectedCriteria" type="checkbox" value={criterion.key}
                  defaultChecked={values.selectedCriteria.includes(criterion.key)} aria-describedby={`audit-help-${criterion.key}`}
                  onChange={criterion.key === "AUDIT_LOG_RETENTION" ? event => setRetentionSelected(event.target.checked) : undefined} />
                {criterion.label}
              </label>
              <p id={`audit-help-${criterion.key}`} className="ml-7 mt-1 text-sm text-slate-400">{criterion.help}</p>
            </div>
          ))}
        </fieldset>
        <div className="mt-6">
          <label htmlFor="audit-retention-days" className="mb-2 block text-sm font-medium">Requested minimum retention (days)</label>
          <input id="audit-retention-days" name="minimumRetentionDays" type="number" min={1} max={maximumRetentionDays} step={1}
            defaultValue={values.minimumRetentionDays ?? ""} disabled={!retentionSelected} required={retentionSelected}
            aria-describedby="audit-retention-help" className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 disabled:opacity-50" />
          <p id="audit-retention-help" className="mt-2 text-sm text-slate-400">Enter 1–36,500 whole days only when retention is selected. There is no default; 36,500 is a project validation bound, not a standard. Unselecting retention clears its saved duration.</p>
        </div>
        <p className="mt-6 text-sm text-slate-400">To clear the recorded scope, uncheck all six criteria and save. Other profile fields remain unchanged.</p>
        <button type="submit" className="mt-4 rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200">Save auditability requirements</button>
      </form>
    </section>
  );
}
