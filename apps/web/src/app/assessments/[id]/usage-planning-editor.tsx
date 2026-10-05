import { usageMetrics, type UsagePlanningValues } from "@/lib/assessment/usage-planning";
import { usageAssumptionGuidance, usageBasisGuidance, usageGuidance, usageScopeGuidance } from "@/lib/assessment/usage-guidance";
import { UsagePlanningForm } from "./usage-planning-form";

export function UsagePlanningEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: UsagePlanningValues;
}) {
  return (
    <section className="mt-6" aria-labelledby="usage-heading">
      <h2 id="usage-heading" className="text-2xl font-semibold">Usage planning inputs</h2>
      <p className="mt-3 text-sm leading-6 text-slate-300">Record a specific environment and time horizon before comparing costs. These are your planning inputs, not verified usage or provider billing units. No price is calculated here.</p>
      <p className="mt-2 text-sm leading-6 text-slate-400">Examples explain AuthWeave&apos;s four planning units. They do not fill answers, look up tariffs or promise a free tier.</p>
      <UsagePlanningForm action={`/api/assessments/${assessmentId}/usage-planning`}>
        <input type="hidden" name="expectedVersion" value={version} />
        <section aria-labelledby="usage-scope-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="usage-scope-heading" className="text-lg font-semibold">Set the environment and planning horizon</h3>
          <p id="usage-scope-description" className="mt-2 text-sm leading-6 text-slate-400">Describe what the numbers cover. Monthly volumes, a configured connection inventory and a one-second peak have different time meanings.</p>
          <label htmlFor="usage-scope" className="mt-5 block text-sm font-medium">Scope and planning horizon</label>
          <textarea id="usage-scope" name="scopeDescription" maxLength={500} rows={3} aria-describedby="usage-scope-description"
            defaultValue={values.scopeDescription} placeholder="For example: production tenant, expected monthly usage in the first year"
            className="mt-2 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200" />
          <details className="mt-3 text-sm leading-6">
            <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Example &amp; limits for planning scope</summary>
            <dl className="mt-3 space-y-3">
              <div><dt className="font-medium">Example</dt><dd className="mt-1 text-slate-300">{usageScopeGuidance.example}</dd></div>
              <div><dt className="font-medium">Limits &amp; trade-offs</dt><dd className="mt-1 text-slate-300">{usageScopeGuidance.limits}</dd></div>
              <div><dt className="font-medium">Ask your team</dt><dd className="mt-1 text-slate-300">{usageScopeGuidance.question}</dd></div>
            </dl>
          </details>
        </section>
        <details className="rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-4 text-sm leading-6">
          <summary className="cursor-pointer font-medium text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">How to choose Unknown, Assumed or Observed</summary>
          <dl className="mt-3 space-y-3">{Object.entries(usageBasisGuidance).map(([basis, description]) => <div key={basis}>
            <dt className="font-medium">{basis === "OBSERVED" ? "Observed by owner" : basis === "ASSUMED" ? "Assumed" : "Unknown"}</dt>
            <dd className="mt-1 text-slate-300">{description}</dd>
          </div>)}</dl>
          <p className="mt-3 text-slate-400">Zero is an explicit quantity, not a missing answer. To clear a metric, choose Unknown and empty its Value field. Choosing a basis alone does not change the number.</p>
        </details>
        <div className="grid items-start gap-5 sm:grid-cols-2">
          {usageMetrics.map(metric => {
            const quantity = values.volumes[metric.key];
            const guide = usageGuidance[metric.key];
            return (
              <fieldset key={metric.key} aria-describedby={`usage-help-${metric.key}`} className="min-w-0 rounded-xl border border-white/10 p-4 sm:p-5">
                <legend className="px-1 font-medium">{metric.label}</legend>
                <p id={`usage-help-${metric.key}`} className="mb-3 text-sm leading-6 text-slate-300">{metric.help}</p>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div>
                    <label htmlFor={`usage-basis-${metric.key}`} className="block text-sm">Basis<span className="sr-only">: {metric.label}</span></label>
                    <select id={`usage-basis-${metric.key}`} name={`basis_${metric.key}`}
                      defaultValue={quantity?.basis ?? "UNKNOWN"}
                      aria-describedby={`usage-help-${metric.key} usage-pair-help`}
                      className="mt-1 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
                      <option value="UNKNOWN">Unknown</option>
                      <option value="ASSUMED">Assumed</option>
                      <option value="OBSERVED">Observed by owner</option>
                    </select>
                  </div>
                  <div>
                    <label htmlFor={`usage-value-${metric.key}`} className="block text-sm">Value<span className="sr-only">: {metric.label}</span></label>
                    <input id={`usage-value-${metric.key}`} name={`value_${metric.key}`} type="number"
                      min="0" max="9007199254740991" step="1" inputMode="numeric"
                      defaultValue={quantity?.value ?? ""}
                      aria-describedby={`usage-unit-${metric.key} usage-pair-help`}
                      className="mt-1 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200" />
                    <p id={`usage-unit-${metric.key}`} className="mt-2 text-xs leading-5 text-slate-400">Unit: {guide.unitLabel}</p>
                  </div>
                </div>
                <details className="mt-4 text-sm leading-6">
                  <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Example &amp; limits<span className="sr-only">: {metric.label}</span></summary>
                  <dl className="mt-3 space-y-3">
                    <div><dt className="font-medium">Example</dt><dd className="mt-1 text-slate-300">{guide.example}</dd></div>
                    <div><dt className="font-medium">Limits &amp; trade-offs</dt><dd className="mt-1 text-slate-300">{guide.limits}</dd></div>
                    <div><dt className="font-medium">Ask your team</dt><dd className="mt-1 text-slate-300">{guide.question}</dd></div>
                  </dl>
                </details>
              </fieldset>
            );
          })}
        </div>
        <p id="usage-pair-help" className="text-sm leading-6 text-slate-400">Use Unknown with a blank Value, or Assumed / Observed with a whole non-negative number. Partial inputs can be saved. An explicit zero stays zero, not unknown.</p>
        <details className="rounded-xl border border-white/10 p-4 sm:p-5">
          <summary className="cursor-pointer font-medium focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Assumptions and limitations (up to 10)</summary>
          <p id="usage-assumptions-description" className="mt-3 text-sm leading-6 text-slate-400">Use one field per distinct assumption, up to 500 characters each. Leave unused fields empty. Do not enter secrets or personal data.</p>
          <p className="mt-3 text-sm leading-6 text-slate-300">{usageAssumptionGuidance.example}</p>
          <p className="mt-3 text-sm leading-6 text-slate-400">{usageAssumptionGuidance.limits}</p>
          <div className="mt-4 grid gap-4">
            {Array.from({ length: 10 }, (_, index) => (
              <div key={index}>
                <label htmlFor={`usage-assumption-${index}`} className="block text-sm">Assumption {index + 1}</label>
                <textarea id={`usage-assumption-${index}`} name="assumption" maxLength={500} rows={2}
                  aria-describedby="usage-assumptions-description"
                  defaultValue={values.assumptions[index] ?? ""}
                  className="mt-1 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200" />
              </div>
            ))}
          </div>
        </details>
        <details className="rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-4 text-sm leading-6">
          <summary className="cursor-pointer font-medium text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">What Usage does not calculate</summary>
          <p className="mt-3 text-slate-300">The input check below uses saved answers, not unsaved edits. Recorded inputs are not verified measurements, a capacity verdict or a price estimate.</p>
          <p className="mt-3 text-slate-400">Provider billing definitions, dated prices, paid features, extra environments, infrastructure and operating costs still need a separate model. No budget limit, affordability finding or free tier is inferred.</p>
          <p className="mt-3 text-slate-400">These are AuthWeave planning definitions, not a universal vendor counting standard. Missing quantities remain unknown; they do not mean zero cost.</p>
        </details>
        <p className="text-sm leading-6 text-slate-400">“Observed” is your statement, not independently verified evidence. Other assessment fields are preserved. Opening explanations does not save anything.</p>
      </UsagePlanningForm>
    </section>
  );
}
