import { usageMetrics } from "@/lib/assessment/usage-planning";
import type { UsageMissingPath, UsagePlanningPreflightSummary } from "@/lib/auth/core-client";

const missingLabels: Record<UsageMissingPath, string> = {
  "operations.usagePlanning.scopeDescription": "Describe the environment and planning horizon.",
  "operations.usagePlanning.volumes.MONTHLY_ACTIVE_USERS": "Monthly active users are unknown; record a value if you can justify one.",
  "operations.usagePlanning.volumes.ENTERPRISE_SSO_CONNECTIONS": "Enterprise SSO connections are unknown; record a value if you can justify one.",
  "operations.usagePlanning.volumes.MONTHLY_M2M_TOKEN_ISSUANCES": "Monthly M2M token issuances are unknown; record a value if you can justify one.",
  "operations.usagePlanning.volumes.PEAK_HUMAN_LOGINS_PER_SECOND": "Peak human logins per second are unknown; record a value if you can justify one.",
  "operations.usagePlanning.assumptions": "Document at least one assumption behind the estimated values.",
};

const unitLabels: Record<UsagePlanningPreflightSummary["quantityChecks"][number]["unit"], string> = {
  USERS_PER_MONTH: "users/month",
  CONFIGURED_CONNECTIONS: "configured connections",
  TOKEN_ISSUANCES_PER_MONTH: "tokens/month",
  LOGINS_PER_SECOND: "logins/second",
};

export function UsagePlanningPreflight({ preview }: { preview: UsagePlanningPreflightSummary }) {
  return (
    <section className="mt-6 rounded-xl border border-slate-700 p-6" aria-labelledby="usage-preflight-heading">
      <h2 id="usage-preflight-heading" className="text-xl font-semibold">Usage input check</h2>
      <p className="mt-2 text-sm text-slate-400">Assessment version {preview.assessmentVersion} · Checked {new Date(preview.evaluatedAt).toLocaleString("en-US", { timeZone: "UTC" })} UTC</p>
      <p className="mt-4 font-medium text-cyan-200">
        {preview.status === "INPUTS_RECORDED" ? "Planning inputs recorded — not verified" :
          "More planning information is needed"}
      </p>
      {preview.missingPaths.length > 0 && (
        <div className="mt-4 rounded-lg border border-amber-700 p-4">
          <h3 className="font-medium text-amber-100">Still needed for a complete input inventory</h3>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-slate-300">
            {preview.missingPaths.map(path => <li key={path}>{missingLabels[path]}</li>)}
          </ul>
        </div>
      )}
      <dl className="mt-5 grid gap-3 sm:grid-cols-2">
        {preview.quantityChecks.map(check => <div key={check.metric} className="rounded-lg bg-slate-800/60 p-4">
          <dt className="font-medium">{usageMetrics.find(metric => metric.key === check.metric)?.label}</dt>
          <dd className="mt-1 text-sm text-slate-300">
            {check.value === null ? "Unknown" : `${check.value.toLocaleString("en-US")} ${unitLabels[check.unit]} · ${check.status.toLowerCase()}`}
          </dd>
          <dd className="mt-2 text-sm text-slate-400">{usageMetrics.find(metric => metric.key === check.metric)?.help}</dd>
        </div>)}
      </dl>
      <p className="mt-5 text-sm text-slate-400">
        These are owner-supplied inputs, not verified measurements or a cost estimate. Provider billing units,
        dated prices, paid features, additional environments and operational costs still need a separate model.
        No budget limit or free tier is inferred.
      </p>
    </section>
  );
}
