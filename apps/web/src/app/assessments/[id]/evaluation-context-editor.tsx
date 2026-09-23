import { criticalities } from "@/lib/assessment/capabilities";
import { applicationTypes, clientTypes, complianceScopeStatuses, complianceTargets, membershipModels,
  populations, tenancyModels, type EvaluationContextValues } from "@/lib/assessment/evaluation-context";

const labels: Record<string, string> = {
  UNKNOWN: "Unknown / not recorded",
  B2B_SAAS: "B2B SaaS", PARTNER_PORTAL: "Partner portal",
  PUBLIC_SECTOR_PORTAL: "Public-sector portal", INTERNAL_WORKFORCE: "Internal workforce application",
  OTHER: "Other (needs definition)",
  BROWSER: "Browser", NATIVE_MOBILE: "Native mobile", MACHINE_TO_MACHINE: "Machine to machine",
  EXTERNAL_CUSTOMERS: "External customers", PARTNERS: "Partners", CITIZENS: "Citizens",
  EMPLOYEES: "Employees", CONTRACTORS: "Contractors", INTERNAL_OPERATORS: "Internal operators",
  MULTI_TENANT_ORGANIZATIONS: "Multiple customer organizations",
  SINGLE_ORGANIZATION: "One organization", NO_ORGANIZATION_BOUNDARY: "No organization boundary",
  SINGLE_ORGANIZATION_PER_USER: "One organization per user",
  MULTIPLE_ORGANIZATIONS_PER_USER: "Multiple organizations per user",
  NOT_APPLICABLE: "Not applicable",
  REQUIRED: "Required", PREFERRED: "Preferred", NOT_REQUIRED: "Not required", FORBIDDEN: "Forbidden",
  NONE_IDENTIFIED: "No compliance targets identified after review",
  TARGETS_IDENTIFIED: "Compliance targets identified (not yet evaluated)",
  SOC_2: "SOC 2", ISO_27001: "ISO 27001", HIPAA: "HIPAA", FEDRAMP: "FedRAMP",
  GDPR: "GDPR",
};

export function EvaluationContextEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: EvaluationContextValues;
}) {
  return (
    <section className="mt-10 rounded-xl border border-slate-700 p-6" aria-labelledby="context-heading">
      <h2 id="context-heading" className="text-2xl font-semibold">Application context and checked security scope</h2>
      <p className="mt-3 text-slate-300">Record facts about the application before relying on a comparison. Unknown is safer than guessing. These fields are checked separately from the nine capability preferences below.</p>
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
        <p className="mt-5 text-sm text-slate-400">Browser token minimization helps compare BFF/session and SPA patterns; even “Required” does not automatically prohibit all browser tokens. “Not required” removes that particular constraint; it does not prove safety or compliance. Choose “No compliance targets identified” only after checking the scope; choose “Targets identified” with at least one label. Labels are not evidence of compliance. Other profile details are preserved.</p>
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
