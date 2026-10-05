import { capabilityFields, criticalities, type CapabilityValues } from "@/lib/assessment/capabilities";
import { AssessmentSectionForm } from "./assessment-section-form";
import { capabilityGuidance, criticalityGuidance } from "@/lib/assessment/capability-guidance";

export function CapabilityEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: CapabilityValues;
}) {
  return (
    <section className="mt-6" aria-labelledby="capabilities-heading">
      <h2 id="capabilities-heading" className="text-2xl font-semibold">Capability requirements</h2>
      <p className="mt-3 text-sm leading-6 text-slate-300">Choose what your application needs, not what a provider advertises. Leave an answer Unknown when you need more information.</p>
      <p className="mt-2 text-sm leading-6 text-slate-400">The explanations below do not choose answers or verify provider support. Only these nine fields change when you explicitly save.</p>
      <details className="mt-5 rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-4">
        <summary className="cursor-pointer text-sm font-medium text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">How to choose a requirement level</summary>
        <dl className="mt-4 space-y-4 text-sm leading-6">
          {criticalities.map(value => <div key={value}>
            <dt className="font-semibold text-slate-100">{criticalityGuidance[value].label}</dt>
            <dd className="mt-1 text-slate-300">{criticalityGuidance[value].explanation}</dd>
          </div>)}
        </dl>
        <p className="mt-4 text-xs leading-5 text-slate-400">Required and Forbidden are hard constraints. Preferences cannot reverse an exclusion. The current comparison uses fictional options, not verified real-provider facts.</p>
      </details>
      <AssessmentSectionForm key={`capabilities:${assessmentId}:${version}`} section="capabilities" action={`/api/assessments/${assessmentId}/capabilities`}>
        <input type="hidden" name="expectedVersion" value={version} />
        <div className="grid items-start gap-4 sm:grid-cols-2">
          {capabilityFields.map(field => {
            const guide = capabilityGuidance[field.capability];
            const controlId = `capability-${field.capability}`;
            return (
              <div key={field.capability} className="min-w-0 rounded-xl border border-white/10 bg-white/[0.02] p-4">
                <label htmlFor={controlId} className="block text-sm font-semibold text-slate-100">{field.label}</label>
                <p id={`${controlId}-description`} className="mt-2 text-sm leading-6 text-slate-300">{guide.definition}</p>
                <select id={controlId} name={field.capability} aria-describedby={`${controlId}-description`}
                  defaultValue={values[field.capability]}
                  className="mt-3 w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
                  {criticalities.map(value => <option key={value} value={value}>{criticalityGuidance[value].label}</option>)}
                </select>
                <details className="mt-3 text-sm leading-6">
                  <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">When it helps &amp; trade-offs<span className="sr-only">: {field.label}</span></summary>
                  <dl className="mt-3 space-y-3">
                    <div><dt className="font-medium text-slate-100">When it helps</dt><dd className="mt-1 text-slate-300">{guide.usefulWhen}</dd></div>
                    <div><dt className="font-medium text-slate-100">Limits &amp; trade-offs</dt><dd className="mt-1 text-slate-300">{guide.tradeOff}</dd></div>
                    <div><dt className="font-medium text-slate-100">Ask your team</dt><dd className="mt-1 text-slate-300">{guide.question}</dd></div>
                  </dl>
                  <a href={guide.source.href} target="_blank" rel="noopener noreferrer"
                    className="mt-4 inline-block text-xs text-cyan-200 underline underline-offset-4">Concept reference: {guide.source.title}<span className="sr-only"> (opens in a new tab)</span></a>
                </details>
              </div>
            );
          })}
        </div>
      </AssessmentSectionForm>
    </section>
  );
}
