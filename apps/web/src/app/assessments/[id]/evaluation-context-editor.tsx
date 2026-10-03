import { criticalities } from "@/lib/assessment/capabilities";
import { applicationTypes, clientTypes, complianceScopeStatuses, complianceTargets, dataCategories, membershipModels,
  populations, tenancyModels, evaluationContextLabels as labels, type EvaluationContextValues } from "@/lib/assessment/evaluation-context";

export function EvaluationContextEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: EvaluationContextValues;
}) {
  return (
    <section className="mt-10 rounded-xl border border-slate-700 p-6" aria-labelledby="context-heading">
      <h2 id="context-heading" className="text-2xl font-semibold">Application context and checked security scope</h2>
      <p className="mt-3 text-slate-300">Record facts about the application before relying on a comparison. Unknown is safer than guessing. These fields are checked separately from the nine capabilities in the Requirements step.</p>
      <form action={`/api/assessments/${assessmentId}/evaluation-context`} method="post" className="mt-6">
        <input type="hidden" name="expectedVersion" value={version} />
        <div className="grid gap-5 sm:grid-cols-2">
          <SelectField name="applicationType" label="Application type" value={values.applicationType}
            choices={applicationTypes} />
          <SelectField name="tenancy" label="Organization tenancy" value={values.tenancy}
            choices={tenancyModels} />
          <SelectField name="membership" label="User membership" value={values.membership}
            choices={membershipModels} />
          <div className="sm:col-span-2 grid gap-5 sm:grid-cols-2">
            <CheckboxGroup name="clients" label="Client types" choices={clientTypes} selected={values.clients} />
            <CheckboxGroup name="selectedPopulations" label="User populations" choices={populations}
              selected={values.selectedPopulations} />
          </div>
          <SelectField name="dataResidency" label="At-rest data residency requirement"
            value={values.dataResidency} choices={criticalities} />
          <div className="sm:col-span-2 grid gap-5 sm:grid-cols-2">
            <CheckboxGroup name="selectedDataCategories" label="Data categories covered by at-rest residency"
              choices={dataCategories} selected={values.selectedDataCategories} />
            <div>
              <label htmlFor="context-allowedCountries" className="mb-2 block text-sm font-medium">
                Allowed storage countries (ISO two-letter codes)
              </label>
              <input id="context-allowedCountries" name="allowedCountries" type="text" maxLength={1024}
                pattern="[ ]*(?:[A-Z]{2}(?:[ ]*,[ ]*[A-Z]{2})*)?[ ]*"
                title="Use uppercase two-letter country codes separated by commas, for example US, CA."
                autoCapitalize="characters" spellCheck={false}
                defaultValue={values.allowedCountries.join(", ")} placeholder="US, CA"
                className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100" />
              <p className="mt-2 text-sm text-slate-400">Use uppercase country codes separated by commas. The same allowlist applies to each selected category. Empty means not recorded, not worldwide permission.</p>
            </div>
          </div>
          <SelectField name="browserTokenExposureMinimization" label="Minimize OAuth token exposure in browser code"
            value={values.browserTokenExposureMinimization} choices={criticalities} />
          <SelectField name="complianceScopeStatus" label="Compliance target scope"
            value={values.complianceScopeStatus} choices={complianceScopeStatuses} />
          <CheckboxGroup name="selectedComplianceTargets" label="Identified target labels"
            choices={complianceTargets} selected={values.selectedComplianceTargets} />
          <SelectField name="phishingResistance" label="Phishing-resistant authentication"
            value={values.phishingResistance} choices={criticalities} />
          <SelectField name="nonExportableKeys" label="Non-exportable authentication keys"
            value={values.nonExportableKeys} choices={criticalities} />
          <SelectField name="stepUpAuthentication" label="Stronger authentication for sensitive actions"
            value={values.stepUpAuthentication} choices={criticalities} />
        </div>
        <p className="mt-5 text-sm text-slate-400">At-rest residency checks do not cover processing locations, support access or international transfers. Browser token minimization helps compare BFF/session and SPA patterns; even “Required” does not automatically prohibit all browser tokens. “Not required” removes that particular constraint; it does not prove safety or compliance. Choose “No compliance targets identified” only after checking the scope; choose “Targets identified” with at least one label. Labels are not evidence of compliance. Other profile details are preserved.</p>
        <button type="submit" className="mt-5 rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200">
          Save application context
        </button>
      </form>
    </section>
  );
}

function SelectField({ name, label, value, choices }: {
  name: string; label: string; value: string; choices: readonly string[];
}) {
  return (
    <div>
      <label htmlFor={`context-${name}`} className="mb-2 block text-sm font-medium">{label}</label>
      <select id={`context-${name}`} name={name} defaultValue={value}
        className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100">
        {choices.map(choice => <option key={choice} value={choice}>{labels[choice] ?? choice}</option>)}
      </select>
    </div>
  );
}

function CheckboxGroup({ name, label, choices, selected }: {
  name: string; label: string; choices: readonly string[]; selected: readonly string[];
}) {
  return (
    <fieldset className="rounded-lg border border-slate-700 p-4">
      <legend className="px-1 text-sm font-medium">{label}</legend>
      <div className="mt-2 space-y-2">
        {choices.map(choice => (
          <label key={choice} className="flex items-center gap-2 text-sm text-slate-300">
            <input type="checkbox" name={name} value={choice} defaultChecked={selected.includes(choice)}
              className="accent-cyan-300" />{labels[choice] ?? choice}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
