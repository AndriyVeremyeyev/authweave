import type { OperationsMissingPath, OperationsPlanningPreview } from "@/lib/assessment/operations-planning";
import { usageMetrics } from "@/lib/assessment/usage-planning";

const labels: Record<string, string> = {
  MANAGED: "Managed service", SELF_HOSTED: "Self-hosted", NO_PREFERENCE: "No preference", UNKNOWN: "Unknown / not recorded",
  AZURE: "Azure", AWS: "AWS", GOOGLE_CLOUD: "Google Cloud", ON_PREMISES: "On premises", MULTI_CLOUD: "Multi-cloud", UNDECIDED: "Undecided",
  LIMITED: "Limited", MODERATE: "Moderate", ADVANCED: "Advanced", HIGH: "High", LOW: "Low",
};
const alignments = {
  PREFERENCE_ALIGNED: "Aligns with your hosting preference — not a recommendation",
  PREFERENCE_DIFFERS: "Differs from your hosting preference — not excluded",
  NO_PREFERENCE: "No hosting preference recorded in favor of either model",
  PREFERENCE_UNKNOWN: "Hosting preference is unknown",
};
const support = {
  SUPPORT_CAPACITY_UNDEFINED: "Clarify your team's identity expertise and support capacity. Neither model is verified as ready.",
  RESPONSIBILITY_PLAN_NEEDED: "Assign owners and validate the responsibility and support plan. Declared expertise is not proof of readiness.",
  INTEGRATION_SUPPORT_PLAN_NEEDED: "With limited declared expertise, plan help for integration, access configuration and vendor escalation.",
  OPERATOR_SUPPORT_PLAN_NEEDED: "With limited declared expertise, plan experienced help to operate, secure and recover the identity platform.",
};
const budget = {
  BUDGET_SCOPE_UNDEFINED: "Clarify budget sensitivity, then build a separate cost model. No spending cap or free tier is inferred.",
  COST_MODEL_NEEDED: "A separate model must include dated prices, billing units, required features, environments and team costs. Budget sensitivity is not a spending cap.",
};
const missingLabels: Record<OperationsMissingPath, string> = {
  "operations.hosting": "Clarify the preferred identity-service operating model.",
  "operations.deploymentTarget": "Clarify where the assessed application will run; this does not select an IdP location.",
  "operations.identityExpertise": "Record the team's declared identity expertise; actual capacity still needs validation.",
  "operations.budgetSensitivity": "Clarify budget sensitivity; no monetary limit is implied.",
  "operations.usagePlanning.scopeDescription": "Describe the usage environment and planning horizon.",
  "operations.usagePlanning.assumptions": "Document assumptions behind estimated usage.",
  "operations.usagePlanning.volumes.MONTHLY_ACTIVE_USERS": "Monthly active users are unknown.",
  "operations.usagePlanning.volumes.ENTERPRISE_SSO_CONNECTIONS": "Enterprise SSO connections are unknown.",
  "operations.usagePlanning.volumes.MONTHLY_M2M_TOKEN_ISSUANCES": "Monthly M2M token issuances are unknown.",
  "operations.usagePlanning.volumes.PEAK_HUMAN_LOGINS_PER_SECOND": "Peak human logins per second are unknown.",
};

export function OperationsPlanning({ preview }: { preview: OperationsPlanningPreview }) {
  return <section className="mt-8 rounded-xl border border-slate-700 p-5 sm:p-6" aria-labelledby="operations-planning-heading">
    <h2 id="operations-planning-heading" className="text-2xl font-semibold">Managed or self-hosted identity?</h2>
    <p className="mt-3 text-sm leading-6 text-slate-300">Compare two operating models for your application&apos;s identity service,
      not AuthWeave&apos;s own deployment. Both remain available to explore. Hosting preference is not a hard requirement.</p>
    <p className="mt-3 text-sm text-slate-400">Saved assessment version {preview.assessmentVersion} · Evaluated {preview.evaluatedAt} (UTC)</p>
    <p className="mt-4 rounded-lg border border-amber-700 p-4 text-sm text-amber-100">Generic planning guidance, not provider eligibility,
      verified readiness, pricing or a recommendation. Even zero recorded usage does not establish a free tier or zero total cost.</p>
    <dl className="mt-5 grid gap-4 text-sm sm:grid-cols-2">
      <div><dt className="text-slate-400">Identity-service hosting preference</dt><dd>{labels[preview.inputs.hosting]}</dd></div>
      <div><dt className="text-slate-400">Application deployment target — not IdP location</dt><dd>{labels[preview.inputs.deploymentTarget]}</dd></div>
      <div><dt className="text-slate-400">Declared identity expertise — not verified</dt><dd>{labels[preview.inputs.identityExpertise]}</dd></div>
      <div><dt className="text-slate-400">Budget sensitivity — not a spending cap</dt><dd>{labels[preview.inputs.budgetSensitivity]}</dd></div>
    </dl>
    <p className="mt-3 text-sm text-slate-400">This comparison uses saved preferences and usage inputs, not unsaved form selections.</p>
    <p className="mt-4 font-medium text-cyan-200">{preview.status === "INPUTS_RECORDED"
      ? "Planning inputs recorded — responsibilities and costs still need validation" : "More planning information is needed"}</p>
    {preview.missingPaths.length > 0 && <div className="mt-3 rounded-lg bg-slate-800/60 p-4">
      <h3 className="font-medium">Missing saved inputs</h3>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-slate-300">{preview.missingPaths.map(path => <li key={path}>{missingLabels[path]}</li>)}</ul>
    </div>}
    <p className="mt-4 text-sm text-slate-400">Recorded usage inputs: {preview.usageInputs.recordedMetrics.length
      ? usageMetrics.filter(m => preview.usageInputs.recordedMetrics.includes(m.key)).map(m => m.label).join(", ") : "None — unknown is not zero"}.
      This comparison uses input presence only, not quantities or billing calculations.</p>
    <div className="mt-6 grid gap-5 lg:grid-cols-2">
      {preview.options.map(option => <article key={option.optionId} className="rounded-xl border border-slate-600 p-5">
        <h3 className="text-xl font-semibold">{option.optionId === "MANAGED_IDENTITY_SERVICE" ? "Managed identity service" : "Self-hosted identity service"}</h3>
        <p className="mt-3 text-sm font-medium text-cyan-200">{alignments[option.hostingAlignment]}</p>
        <Points title="Potential advantages" items={option.advantages} />
        <Points title="Trade-offs to investigate" items={option.tradeoffs} />
        <Points title="Model-specific responsibilities" items={option.responsibilities} />
        <h4 className="mt-5 font-medium">Support planning</h4><p className="mt-2 text-sm text-slate-300">{support[option.supportPlanning]}</p>
        <h4 className="mt-5 font-medium">Cost planning</h4><p className="mt-2 text-sm text-slate-300">{budget[option.budgetPlanning]}</p>
      </article>)}
    </div>
    <Points title="Responsibilities that remain with either model" items={preview.sharedResponsibilities} level={3} />
    <details className="mt-5 text-sm text-slate-300"><summary className="cursor-pointer font-medium">What remains unverified</summary>
      <ul className="mt-3 list-disc space-y-2 pl-5">{preview.deferredBoundaries.map(item => <li key={item}>{item}</li>)}</ul>
      <p className="mt-3">These references explain generic responsibilities, not facts about an exact vendor plan or region.</p>
      <ul className="mt-2 space-y-2">{preview.references.map((url, index) => <li key={url}><a href={url} target="_blank" rel="noopener noreferrer"
        className="text-cyan-200 underline underline-offset-4">{index === 0 ? "Shared responsibility guidance" : "Self-operated identity production concerns"}</a></li>)}</ul>
    </details>
  </section>;
}
function Points({ title, items, level = 4 }: { title: string; items: string[]; level?: 3 | 4 }) {
  const Heading = level === 3 ? "h3" : "h4";
  return <div className="mt-5"><Heading className="font-medium">{title}</Heading>
    <ul className="mt-2 list-disc space-y-2 pl-5 text-sm leading-6 text-slate-300">{items.map(item => <li key={item}>{item}</li>)}</ul></div>;
}
export function OperationsPlanningUnavailable() {
  return <section className="mt-8 rounded-xl border border-amber-700 p-5" aria-labelledby="operations-planning-heading">
    <h2 id="operations-planning-heading" className="text-xl font-semibold">Operations planning comparison unavailable</h2>
    <p className="mt-2 text-sm text-slate-300">Your saved assessment and other checks remain available. Reload to use the current saved inputs.
      No operating-model result, price or recommendation is inferred from an unavailable comparison.</p>
  </section>;
}
