import { operationalPreferenceFields, operationalPreferenceLabels } from "@/lib/assessment/operational-preferences";
import type { OperationsInputs } from "@/lib/assessment/operations-planning";
import { AssessmentSectionForm } from "./assessment-section-form";

export function OperationalPreferencesEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: OperationsInputs;
}) {
  return <section className="mt-6" aria-labelledby="operations-editor-heading">
    <h2 id="operations-editor-heading" className="text-2xl font-semibold">Operational preferences</h2>
    <p className="mt-3 text-sm leading-6 text-slate-300">Record preferences for the application you are evaluating. Unknown or undecided is a valid answer; no option is chosen for you.</p>
    <p className="mt-2 text-sm leading-6 text-slate-400">Saved version {version}. These choices guide the managed/self-hosted planning comparison, not provider eligibility, pricing or a final recommendation.</p>
    <AssessmentSectionForm key={`operations:${assessmentId}:${version}`} section="operations"
      action={`/api/assessments/${assessmentId}/operational-preferences`}>
      <input type="hidden" name="expectedVersion" value={version} />
      <div className="grid gap-5 sm:grid-cols-2">
        {(Object.keys(operationalPreferenceFields) as (keyof OperationsInputs)[]).map(key => {
          const field = operationalPreferenceFields[key];
          return <div key={key} className="min-w-0 rounded-xl border border-white/10 p-4">
            <label htmlFor={`operations-${key}`} className="mb-2 block text-sm font-medium">{field.label}</label>
            <select id={`operations-${key}`} name={key} defaultValue={values[key]}
              aria-describedby={`operations-${key}-description`}
              className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
              {field.choices.map(choice => <option key={choice} value={choice}>{operationalPreferenceLabels[choice]}</option>)}
            </select>
            <p id={`operations-${key}-description`} className="mt-3 text-sm leading-6 text-slate-400">{field.help}</p>
          </div>;
        })}
      </div>
      <p className="text-sm leading-6 text-slate-400">Save deliberately. Usage quantities and assumptions, audit requirements and other profile fields are preserved. The comparison below uses saved values, not unsaved selections.</p>
    </AssessmentSectionForm>
  </section>;
}
