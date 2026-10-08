import { criticalities } from "@/lib/assessment/capabilities";
import { assuranceExpectations, assuranceExpectationGuidance } from "@/lib/assessment/assurance-expectation";
import { AssessmentSectionForm } from "./assessment-section-form";
import { complianceScopeGuidance, complianceTargetGuidance, contextSecurityGuidance,
  securityLevelGuidance, type ContextGuidance } from "@/lib/assessment/context-guidance";
import { applicationTypes, clientTypes, complianceScopeStatuses, complianceTargets, dataCategories, membershipModels,
  populations, tenancyModels, evaluationContextLabels as labels, type EvaluationContextValues } from "@/lib/assessment/evaluation-context";

export function EvaluationContextEditor({ assessmentId, version, values }: {
  assessmentId: string; version: number; values: EvaluationContextValues;
}) {
  return (
    <section className="mt-6" aria-labelledby="context-heading">
      <h2 id="context-heading" className="text-2xl font-semibold">Application context and checked security scope</h2>
      <p className="mt-3 text-sm leading-6 text-slate-300">Record facts about the application before relying on a comparison. Unknown is safer than guessing. These fields are checked separately from the nine capabilities in the Requirements step.</p>
      <p className="mt-2 text-sm leading-6 text-slate-400">These are requirements for the application you are evaluating, not changes to AuthWeave&apos;s own sign-in. Explanations do not select answers or verify provider support.</p>
      <AssessmentSectionForm key={`context:${assessmentId}:${version}`} section="context" action={`/api/assessments/${assessmentId}/evaluation-context`}>
        <input type="hidden" name="expectedVersion" value={version} />
        <section aria-labelledby="context-application-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="context-application-heading" className="text-lg font-semibold">Application and audience</h3>
          <p className="mt-2 text-sm leading-6 text-slate-400">Record who uses the application and which client types they use. An empty selection means no answer is recorded.</p>
          <div className="mt-5 grid gap-5 sm:grid-cols-2">
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
          </div>
        </section>
        <details className="rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-4">
          <summary className="cursor-pointer text-sm font-medium text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">How security requirement levels are checked</summary>
          <dl className="mt-4 space-y-4 text-sm leading-6">
            {criticalities.map(value => <div key={value}><dt className="font-semibold">{labels[value]}</dt>
              <dd className="mt-1 text-slate-300">{securityLevelGuidance[value]}</dd></div>)}
          </dl>
          <p className="mt-4 text-xs leading-5 text-slate-400">These explanations describe partial checks, not an overall security verdict. Evidence gaps remain unknown; preferences cannot reverse a hard exclusion.</p>
        </details>
        <section aria-labelledby="context-storage-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="context-storage-heading" className="text-lg font-semibold">Where identity data rests</h3>
          <div className="mt-5 grid gap-5 sm:grid-cols-2">
            <SelectField name="dataResidency" label="At-rest data residency requirement"
              value={values.dataResidency} choices={criticalities} guide={contextSecurityGuidance.dataResidency} />
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
                  aria-describedby="context-allowedCountries-description"
                  defaultValue={values.allowedCountries.join(", ")} placeholder="US, CA"
                  className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200" />
                <p id="context-allowedCountries-description" className="mt-2 text-sm leading-6 text-slate-400">Use uppercase country codes separated by commas. The same allowlist applies to each selected category. Empty means not recorded, not worldwide permission.</p>
              </div>
            </div>
          </div>
        </section>
        <section aria-labelledby="context-authentication-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="context-authentication-heading" className="text-lg font-semibold">Protect sign-in and sensitive actions</h3>
          <p className="mt-2 text-sm leading-6 text-slate-400">These controls are independent. MFA is recorded in Requirements; it does not automatically answer the fields below. The human-authentication checks use the selected clients and user populations.</p>
          <div className="mt-5 grid items-start gap-5 sm:grid-cols-2">
            <SelectField name="browserTokenExposureMinimization" label="Minimize OAuth token exposure in browser code"
              value={values.browserTokenExposureMinimization} choices={criticalities} guide={contextSecurityGuidance.browserTokenExposureMinimization} />
            <SelectField name="phishingResistance" label="Phishing-resistant authentication"
              value={values.phishingResistance} choices={criticalities} guide={contextSecurityGuidance.phishingResistance} />
            <SelectField name="nonExportableKeys" label="Non-exportable authentication keys"
              value={values.nonExportableKeys} choices={criticalities} guide={contextSecurityGuidance.nonExportableKeys} />
            <SelectField name="stepUpAuthentication" label="Stronger authentication for sensitive actions"
              value={values.stepUpAuthentication} choices={criticalities} guide={contextSecurityGuidance.stepUpAuthentication} />
          </div>
        </section>
        <section aria-labelledby="context-assurance-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="context-assurance-heading" className="text-lg font-semibold">Assurance expectation to define</h3>
          <p id="context-assurance-description" className="mt-2 text-sm leading-6 text-slate-400">This is an internal planning label, not an AAL, IAL or FAL level, a security verdict or proof of provider support. It does not change the independent controls above or MFA in Requirements.</p>
          {values.assuranceExpectation === undefined ?
            <p className="mt-4 text-sm text-slate-300">This saved profile has no readable assurance expectation. No default has been inferred.</p> : <div className="mt-5">
              <SelectField name="assuranceExpectation" label="Assurance expectation (planning label)"
                value={values.assuranceExpectation} choices={assuranceExpectations} descriptionId="context-assurance-description" />
              <details className="mt-3 text-sm leading-6">
                <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">What the assurance labels mean</summary>
                <dl className="mt-3 space-y-3">{assuranceExpectations.map(expectation => <div key={expectation}>
                  <dt className="font-medium">{labels[expectation]}</dt><dd className="mt-1 text-slate-300">{assuranceExpectationGuidance[expectation]}</dd>
                </div>)}</dl>
                <p className="mt-4 text-xs text-slate-400">Changing this choice only edits the form. Save explicitly to record it; every known label still needs a definition and supporting evidence.</p>
              </details>
            </div>}
        </section>
        <section aria-labelledby="context-compliance-heading" className="rounded-xl border border-white/10 p-4 sm:p-5">
          <h3 id="context-compliance-heading" className="text-lg font-semibold">Compliance scope to investigate</h3>
          <p id="context-compliance-description" className="mt-2 text-sm leading-6 text-slate-400">Record targets to investigate with your legal, privacy or security reviewer. Labels are not evidence of compliance, a certification or a legal applicability decision.</p>
          <div className="mt-5 grid items-start gap-5 sm:grid-cols-2">
            <div>
              <SelectField name="complianceScopeStatus" label="Compliance target scope"
                value={values.complianceScopeStatus} choices={complianceScopeStatuses} descriptionId="context-compliance-description" />
              <details className="mt-3 text-sm leading-6">
                <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">What the three scope choices mean</summary>
                <dl className="mt-3 space-y-3">{complianceScopeStatuses.map(status => <div key={status}>
                  <dt className="font-medium">{labels[status]}</dt><dd className="mt-1 text-slate-300">{complianceScopeGuidance[status]}</dd>
                </div>)}</dl>
              </details>
            </div>
            <div>
              <CheckboxGroup name="selectedComplianceTargets" label="Identified target labels"
                choices={complianceTargets} selected={values.selectedComplianceTargets} descriptionId="context-compliance-description" />
              <details className="mt-3 text-sm leading-6">
                <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">What the target labels mean</summary>
                <dl className="mt-3 space-y-4">{complianceTargets.map(target => {
                  const guide = complianceTargetGuidance[target];
                  return <div key={target}><dt className="font-medium">{labels[target]}</dt>
                    <dd className="mt-1 text-slate-300">{guide.definition}<ConceptReference reference={guide.reference} /></dd></div>;
                })}</dl>
              </details>
            </div>
          </div>
        </section>
        <p className="text-sm leading-6 text-slate-400">Save only when you want to record your choices. Opening explanations does not save anything. Other profile details are preserved.</p>
      </AssessmentSectionForm>
    </section>
  );
}

function SelectField({ name, label, value, choices, guide, descriptionId }: {
  name: string; label: string; value: string; choices: readonly string[]; guide?: ContextGuidance; descriptionId?: string;
}) {
  return (
    <div>
      <label htmlFor={`context-${name}`} className="mb-2 block text-sm font-medium">{label}</label>
      {guide && <p id={`context-${name}-description`} className="mb-3 text-sm leading-6 text-slate-300">{guide.definition}</p>}
      <select id={`context-${name}`} name={name} defaultValue={value}
        aria-describedby={guide ? `context-${name}-description` : descriptionId}
        className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">
        {choices.map(choice => <option key={choice} value={choice}>{labels[choice] ?? choice}</option>)}
      </select>
      {guide && <details className="mt-3 text-sm leading-6">
        <summary className="cursor-pointer text-cyan-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Example &amp; limits<span className="sr-only">: {label}</span></summary>
        <dl className="mt-3 space-y-3">
          <div><dt className="font-medium">Example</dt><dd className="mt-1 text-slate-300">{guide.example}</dd></div>
          <div><dt className="font-medium">Limits &amp; trade-offs</dt><dd className="mt-1 text-slate-300">{guide.limits}</dd></div>
          <div><dt className="font-medium">Ask your team</dt><dd className="mt-1 text-slate-300">{guide.question}</dd></div>
        </dl>
        <ConceptReference reference={guide.reference} />
      </details>}
    </div>
  );
}

function CheckboxGroup({ name, label, choices, selected, descriptionId }: {
  name: string; label: string; choices: readonly string[]; selected: readonly string[]; descriptionId?: string;
}) {
  return (
    <fieldset aria-describedby={descriptionId} className="min-w-0 rounded-lg border border-slate-700 p-4">
      <legend className="px-1 text-sm font-medium">{label}</legend>
      <div className="mt-2 space-y-2">
        {choices.map(choice => (
          <label key={choice} className="flex items-center gap-2 text-sm text-slate-300">
            <input type="checkbox" name={name} value={choice} defaultChecked={selected.includes(choice)}
              className="shrink-0 accent-cyan-300" />{labels[choice] ?? choice}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

function ConceptReference({ reference }: { reference: ContextGuidance["reference"] }) {
  return reference ? <a href={reference.href} target="_blank" rel="noopener noreferrer"
    className="mt-3 inline-block text-xs text-cyan-200 underline underline-offset-4">Concept reference: {reference.title}<span className="sr-only"> (opens in a new tab)</span></a> : null;
}
