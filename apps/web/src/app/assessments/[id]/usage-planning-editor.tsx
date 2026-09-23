import { usageMetrics, type UsagePlanningValues } from "@/lib/assessment/usage-planning";

export function UsagePlanningEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: UsagePlanningValues;
}) {
  return (
    <section className="mt-10 rounded-xl border border-slate-700 p-6" aria-labelledby="usage-heading">
      <h2 id="usage-heading" className="text-2xl font-semibold">Usage planning inputs</h2>
      <p className="mt-3 text-slate-300">Record a specific environment and time horizon before comparing costs. These are your planning inputs, not verified usage or provider billing units. No price is calculated here.</p>
      <form action={`/api/assessments/${assessmentId}/usage-planning`} method="post" className="mt-6">
        <input type="hidden" name="expectedVersion" value={version} />
        <label htmlFor="usage-scope" className="block text-sm font-medium">Scope and planning horizon</label>
        <textarea id="usage-scope" name="scopeDescription" maxLength={500} rows={3}
          defaultValue={values.scopeDescription} placeholder="For example: production tenant, expected monthly usage in the first year"
          className="mt-2 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100" />
        <div className="mt-6 grid gap-5 sm:grid-cols-2">
          {usageMetrics.map(metric => {
            const quantity = values.volumes[metric.key];
            return (
              <fieldset key={metric.key} className="rounded-lg border border-slate-700 p-4">
                <legend className="px-1 font-medium">{metric.label}</legend>
                <p className="mb-3 text-sm text-slate-400">{metric.help}</p>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div>
                    <label htmlFor={`usage-basis-${metric.key}`} className="block text-sm">Basis</label>
                    <select id={`usage-basis-${metric.key}`} name={`basis_${metric.key}`}
                      defaultValue={quantity?.basis ?? "UNKNOWN"}
                      className="mt-1 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100">
                      <option value="UNKNOWN">Unknown</option>
                      <option value="ASSUMED">Assumed</option>
                      <option value="OBSERVED">Observed by owner</option>
                    </select>
                  </div>
                  <div>
                    <label htmlFor={`usage-value-${metric.key}`} className="block text-sm">Value</label>
                    <input id={`usage-value-${metric.key}`} name={`value_${metric.key}`} type="number"
                      min="0" max="9007199254740991" step="1" inputMode="numeric"
                      defaultValue={quantity?.value ?? ""}
                      className="mt-1 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100" />
                  </div>
                </div>
              </fieldset>
            );
          })}
        </div>
        <details className="mt-6 rounded-lg border border-slate-700 p-4">
          <summary className="cursor-pointer font-medium">Assumptions and limitations (up to 10)</summary>
          <p className="mt-3 text-sm text-slate-400">Use one field per distinct assumption. Leave unused fields empty. Do not enter secrets or personal data.</p>
          <div className="mt-4 grid gap-4">
            {Array.from({ length: 10 }, (_, index) => (
              <div key={index}>
                <label htmlFor={`usage-assumption-${index}`} className="block text-sm">Assumption {index + 1}</label>
                <textarea id={`usage-assumption-${index}`} name="assumption" maxLength={500} rows={2}
                  defaultValue={values.assumptions[index] ?? ""}
                  className="mt-1 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100" />
              </div>
            ))}
          </div>
        </details>
        <p className="mt-5 text-sm text-slate-400">Blank metric values mean unknown; an explicit zero means zero. If you enter a value, choose Assumed or Observed. “Observed” is your statement, not independently verified evidence. Other assessment fields are preserved.</p>
        <button type="submit" className="mt-5 rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200">
          Save usage inputs
        </button>
      </form>
    </section>
  );
}
