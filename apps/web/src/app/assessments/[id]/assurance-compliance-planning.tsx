import { assuranceItemDefinitions, type AssurancePlanningPreview } from "@/lib/assessment/assurance-compliance-planning";
import { evaluationContextLabels } from "@/lib/assessment/evaluation-context";
import { AssessmentStepButton } from "./assessment-workflow";

const expectationLabels = { BASELINE: "Baseline", ELEVATED: "Elevated", HIGH: "High", UNKNOWN: "Unknown / not recorded" };
const statusLabels = { INPUT_CLARIFICATION_NEEDED: "Clarify the input", EVIDENCE_NEEDED: "Evidence still needed", NOT_APPLIED: "Flow not selected — not verified" };
const scopeLabels = { MACHINE_ONLY: "Machine clients only — human flows are not selected",
  HUMAN_SCOPE_RECORDED: "Human scope recorded — deployed flows are not verified", SCOPE_UNRESOLVED: "Human scope needs clarification — no wildcard is assumed" };
const reasons: Record<string, string> = {
  EXPECTATION_UNRECORDED: "No assurance expectation has been recorded. Start by defining the objective.",
  LABEL_NEEDS_DEFINITION: "A broad expectation is recorded, but its concrete criteria are not defined by this label.",
  HUMAN_FLOW_NOT_SELECTED: "This item is not applied to the explicitly machine-only scope. It does not establish safety or assurance.",
  HUMAN_SCOPE_UNRESOLVED: "Select the human clients and populations in scope before interpreting scoped evidence.",
  DECLARED_SCOPE_NOT_VERIFIED: "The selected clients and populations describe intent, not observed authentication behavior.",
  CONTROL_INTENT_UNRESOLVED: "At least one independent control is unknown or forbidden. Clarify its intent; no weaker authentication is inferred.",
  CONTROLS_NOT_VERIFIED: "Recorded control requirements are not evidence that a deployed flow enforces them.",
  FLOW_EVIDENCE_NOT_EVALUATED: "Evidence about the deployed behavior has not been evaluated here.",
  CLIENT_SCOPE_UNRESOLVED: "Client scope is unknown; workload flows are not assumed absent.",
  WORKLOAD_FLOW_NOT_SELECTED: "No machine client is selected. This is a scope decision, not workload verification.",
  TARGET_SCOPE_NOT_ESTABLISHED: "Confirm the requirements scope first. This saved label does not establish applicability or compliance.",
  OTHER_TARGET_NEEDS_DEFINITION: "Define the concrete target behind OTHER before gathering target-specific evidence.",
  TARGET_EVIDENCE_NOT_EVALUATED: "The target is recorded; applicable criteria and supporting evidence have not been evaluated.",
};

export function AssuranceCompliancePlanning({ preview, editable }: { preview: AssurancePlanningPreview; editable: boolean }) {
  return <section className="mt-8 rounded-xl border border-slate-700 p-5 sm:p-6" aria-labelledby="assurance-planning-heading">
    <h2 id="assurance-planning-heading" className="text-2xl font-semibold">Assurance and compliance: what still needs evidence?</h2>
    <p className="mt-3 text-sm leading-6 text-slate-300">Separate the requirements you have recorded from the evidence still needed to evaluate them.
      These are generic investigation prompts, not a standards checklist.</p>
    <p className="mt-3 text-sm text-slate-400">Saved assessment version {preview.assessmentVersion} · Evaluated {preview.evaluatedAt} (UTC)</p>
    <p className="mt-4 rounded-lg border border-amber-700 p-4 text-sm leading-6 text-amber-100">More information is needed.
      No assurance level, legal applicability, certification, provider compliance or recommendation is verified here.</p>
    <p className="mt-3 text-sm text-slate-400">This view uses saved requirements, not unsaved form selections. No answers or evidence are collected or saved by this view.</p>
    <dl className="mt-5 grid gap-4 text-sm sm:grid-cols-2">
      <div><dt className="text-slate-400">Assurance expectation — a planning label only</dt><dd>{expectationLabels[preview.inputs.assuranceExpectation]}</dd></div>
      <div><dt className="text-slate-400">Human authentication scope</dt><dd>{scopeLabels[preview.humanScope]}</dd></div>
      <div><dt className="text-slate-400">Selected clients</dt><dd>{preview.inputs.clients.length ? preview.inputs.clients.map(c => evaluationContextLabels[c]).join(", ") : "Unknown / no selection recorded"}</dd></div>
      <div><dt className="text-slate-400">Selected populations</dt><dd>{preview.inputs.populations.length ? preview.inputs.populations.map(p => evaluationContextLabels[p]).join(", ") : "No population selected — not a worldwide or universal scope"}</dd></div>
    </dl>
    <div className="mt-5 rounded-lg bg-slate-800/60 p-4">
      <h3 className="font-medium">Independent control requirements</h3>
      <p className="mt-2 text-sm text-slate-300">Even a High expectation does not automatically set MFA or any of these controls.
        Recorded requirements are not proof of configured enforcement.</p>
      <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2">{([
        ["multiFactorAuthentication", "MFA"], ["phishingResistance", "Phishing resistance"],
        ["nonExportableKeys", "Non-exportable keys"], ["stepUpAuthentication", "Step-up authentication"],
      ] as const).map(([key, label]) => <div key={key}><dt className="text-slate-400">{label}</dt><dd>{evaluationContextLabels[preview.inputs.controls[key]]}</dd></div>)}</dl>
    </div>
    <h3 className="mt-6 text-xl font-semibold">Assurance investigation</h3>
    <div className="mt-4 grid gap-4 lg:grid-cols-2">{preview.assuranceItems.map(item => <article key={item.itemId} className="rounded-xl border border-slate-600 p-4">
      <h4 className="font-semibold">{assuranceItemDefinitions[item.itemId].title}</h4>
      <p className={`mt-2 text-sm font-medium ${item.status === "NOT_APPLIED" ? "text-slate-400" : "text-amber-200"}`}>{statusLabels[item.status]}</p>
      <p className="mt-2 text-sm leading-6 text-slate-300">{reasons[item.reasonCode]}</p>
      <details className="mt-3 text-sm text-slate-300"><summary className="cursor-pointer font-medium">Investigation prompt</summary>
        <p className="mt-2 leading-6">{item.question}</p></details>
    </article>)}</div>
    <h3 className="mt-6 text-xl font-semibold">Compliance targets to investigate</h3>
    <p className="mt-3 text-sm text-slate-300">Saved scope: {evaluationContextLabels[preview.inputs.complianceScopeStatus]}</p>
    <p className="mt-2 text-sm leading-6 text-slate-300">{preview.complianceScopeCheck.explanation}</p>
    {preview.complianceItems.length > 0 ? <>
      <ul className="mt-4 space-y-3">{preview.complianceItems.map(item => <li key={item.target} className="rounded-lg border border-slate-600 p-4">
        <h4 className="font-semibold">{evaluationContextLabels[item.target]}</h4>
        <p className="mt-2 text-sm font-medium text-amber-200">{statusLabels[item.status]}</p>
        <p className="mt-2 text-sm leading-6 text-slate-300">{reasons[item.reasonCode]}</p>
      </li>)}</ul>
      <details className="mt-4 text-sm text-slate-300"><summary className="cursor-pointer font-medium">Questions to investigate for each target</summary>
        <ol className="mt-3 list-decimal space-y-2 pl-5">{preview.complianceQuestions.map(question => <li key={question}>{question}</li>)}</ol></details>
    </> : <p className="mt-3 text-sm text-slate-400">No target labels are recorded. This does not establish a legal exemption or compliance.</p>}
    {editable && <div className="mt-5 flex flex-wrap gap-3">
      <AssessmentStepButton step="context">Edit scope and independent controls</AssessmentStepButton>
      <AssessmentStepButton step="capabilities">Edit the MFA requirement</AssessmentStepButton>
    </div>}
    <details className="mt-5 text-sm text-slate-300"><summary className="cursor-pointer font-medium">What remains unverified</summary>
      <ul className="mt-3 list-disc space-y-2 pl-5">{preview.deferredBoundaries.map(item => <li key={item}>{item}</li>)}</ul>
      <p className="mt-3">There is no provider evaluation, evidence approval or publication readiness from this inventory.</p>
    </details>
  </section>;
}
export function AssuranceCompliancePlanningUnavailable() {
  return <section className="mt-8 rounded-xl border border-amber-700 p-5" aria-labelledby="assurance-planning-heading">
    <h2 id="assurance-planning-heading" className="text-xl font-semibold">Assurance and compliance investigation unavailable</h2>
    <p className="mt-2 text-sm leading-6 text-slate-300">Your saved assessment and other checks remain available. Reload to use the current saved version.
      No assurance or compliance result is inferred from an unavailable investigation.</p>
  </section>;
}
