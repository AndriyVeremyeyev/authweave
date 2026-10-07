import type { ArchitecturePatternPreflightSummary } from "@/lib/auth/core-client";
import { evaluationContextLabels as labels } from "@/lib/assessment/evaluation-context";
import { AssessmentStepButton } from "./assessment-workflow";
import { ArchitecturePrerequisites } from "./architecture-prerequisites";
import { ArchitectureConfiguration } from "./architecture-configuration";
import { ArchitectureOverview } from "./architecture-overview";
import { architectureStatusText as statusText, architectureTokenLocation as tokenLocation,
  architectureOutcomeText as outcomeText } from "@/lib/assessment/architecture-presentation";
const pathLabels: Record<string, string> = {
  "application.clients": "Client type", "security.browserTokenExposureMinimization": "Browser token minimization",
  "application.type": "Application type", audience: "Audience and organization boundaries", protocols: "Identity protocols",
  provisioning: "Provisioning and user lifecycle", "security.multiFactorAuthentication": "Multi-factor authentication",
  "security.auditability": "Audit events and retention", "security.dataResidency": "Data residency",
  "security.assurance": "Identity assurance", "security.complianceTargets": "Compliance targets", operations: "Operations and usage",
};

export function ArchitecturePatterns({ preview, assessmentId }: { preview: ArchitecturePatternPreflightSummary; assessmentId: string }) {
  return (
    <section className="mt-6" aria-labelledby="patterns-heading">
      <div className="rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-5">
        <p className="text-xs font-semibold uppercase tracking-[0.15em] text-cyan-200">Partial architecture check · Saved version {preview.assessmentVersion}</p>
        <h2 id="patterns-heading" className="mt-3 text-2xl font-semibold">Understand the patterns before choosing</h2>
        <p className="mt-3 text-sm leading-6 text-slate-300">Compare five ways to handle sign-in and tokens. No architecture has been selected. Mixed client types may need several patterns; display order is not a ranking.</p>
        <dl className="mt-4 grid gap-4 rounded-lg bg-black/10 p-4 sm:grid-cols-2">
          <div><dt className="text-xs text-slate-400">Saved client types</dt>
            <dd className="mt-1 text-sm">{preview.selectedClients.length ? preview.selectedClients.map(client => labels[client]).join(", ") : "Not recorded"}</dd></div>
          <div><dt className="text-xs text-slate-400">Saved browser token minimization</dt>
            <dd className="mt-1 text-sm">{labels[preview.browserTokenExposureRequirement]}</dd></div>
        </dl>
        <div className="mt-4"><AssessmentStepButton step="context">Review saved Context →</AssessmentStepButton></div>
        <p className="mt-3 text-xs leading-5 text-slate-400">Only these two saved inputs are checked here. Required minimization is not a blanket ban on browser tokens; Preferred is not scored. Native and workload token storage are not assessed by the browser criterion.</p>
      </div>
      <ol aria-label="How to explore architecture patterns" className="mt-5 grid gap-3 text-sm sm:grid-cols-3">
        <li className="rounded-xl border border-white/10 p-4"><p className="font-medium">1. Read the saved-input checks</p><p className="mt-2 text-xs leading-5 text-slate-400">A partial match is not a complete design. Not applied is not a passed check.</p></li>
        <li className="rounded-xl border border-white/10 p-4"><p className="font-medium">2. Compare pros and trade-offs</p><p className="mt-2 text-xs leading-5 text-slate-400">Read each pattern’s advantages and conditions. Several alternatives can remain open.</p></li>
        <li className="rounded-xl border border-white/10 p-4"><p className="font-medium">3. Try conditions and concrete settings</p><p className="mt-2 text-xs leading-5 text-slate-400">Temporary choices explore a proposed design. They do not verify configuration or select a provider.</p></li>
      </ol>
      {preview.selectedClients.length === 0 && <p className="mt-4 rounded-lg border border-amber-700 p-4 text-amber-100">
        Select at least one client type in the Context step and save to assess applicability.
      </p>}
      <ArchitectureOverview preview={preview} />
      <ul aria-label="Detailed architecture patterns in Core order" className="mt-6 space-y-4">
        {preview.patterns.map((pattern, index) => <li key={pattern.patternId} id={`architecture-pattern-${index}`} tabIndex={-1}
          className="min-w-0 scroll-mt-6 rounded-xl border border-white/10 bg-white/[0.02] p-5 focus-visible:outline-2 focus-visible:outline-cyan-200">
          <h3 className="text-lg font-semibold">{pattern.displayName}</h3>
          <p className="mt-2 text-xs text-slate-400">Client: {labels[pattern.clientType]} · OAuth token location: {tokenLocation[pattern.tokenHandling]}</p>
          <p className="mt-5 text-xs font-medium uppercase tracking-wider text-slate-400">Saved-input result</p>
          <p className={`mt-2 inline-block rounded-lg px-3 py-2 text-sm font-medium ${pattern.status === "NEEDS_INFORMATION"
            ? "bg-amber-300/10 text-amber-200" : pattern.status === "NOT_APPLICABLE" ? "bg-white/5 text-slate-300" : "bg-cyan-300/10 text-cyan-100"}`}>{statusText[pattern.status]}</p>
          <dl className="mt-4 grid gap-3 sm:grid-cols-2">
            {pattern.checks.map(check => <div key={check.profilePath} className="min-w-0 rounded-lg border border-white/10 p-3">
              <dt className="text-xs text-slate-400">{pathLabels[check.profilePath] ?? check.profilePath}</dt>
              <dd className="mt-2 text-sm"><p className="font-medium">{outcomeText[check.outcome]}</p>
                <p className="mt-2 leading-6 text-slate-300">{check.explanation}</p></dd>
            </div>)}
          </dl>
          <details className="mt-3 text-xs text-slate-400"><summary className="cursor-pointer">Technical check details</summary>
            <ul className="mt-2 space-y-2 break-words">{pattern.checks.map(check => <li key={check.profilePath}>{check.profilePath} · {check.outcome} · {check.reasonCode}</li>)}</ul>
          </details>
          <details className="mt-4 text-sm text-slate-300">
            <summary className="cursor-pointer font-medium">Pros, trade-offs and prerequisites</summary>
            <div className="grid gap-4 sm:grid-cols-2"><PatternList title="Advantages" items={pattern.advantages} />
              <PatternList title="Trade-offs" items={pattern.tradeoffs} /></div>
            <PatternList title="Prerequisites to verify" items={pattern.prerequisites} />
            <p className="mt-3">Protocol references: {pattern.references.map((reference, index) => <span key={reference}>
              {index > 0 && ", "}<a className="text-cyan-200 underline" href={reference} target="_blank" rel="noopener noreferrer">
                {index + 1}
              </a>
            </span>)}</p>
          </details>
          <ArchitecturePrerequisites key={`${assessmentId}-${preview.assessmentVersion}-${pattern.patternId}`} assessmentId={assessmentId}
            version={preview.assessmentVersion} patternId={pattern.patternId} descriptions={pattern.prerequisites}
            clientScope={preview.selectedClients.length === 0 ? "UNKNOWN" :
              preview.selectedClients.includes(pattern.clientType) ? "SELECTED" : "NOT_SELECTED"} />
          <ArchitectureConfiguration key={`settings-${assessmentId}-${preview.assessmentVersion}-${pattern.patternId}`} assessmentId={assessmentId}
            version={preview.assessmentVersion} patternId={pattern.patternId}
            clientScope={preview.selectedClients.length === 0 ? "UNKNOWN" : preview.selectedClients.includes(pattern.clientType) ? "SELECTED" : "NOT_SELECTED"} />
        </li>)}
      </ul>
      <details className="mt-5 rounded-xl border border-slate-700 p-5 text-sm text-slate-300">
        <summary className="cursor-pointer font-medium">What remains outside the saved-input check</summary>
        <p className="mt-3">Technical prerequisites are not verified. A temporary what-if result does not evaluate these remaining areas or override the saved-input result:</p>
        <ul className="mt-2 list-disc space-y-1 pl-5">
          {preview.deferredPaths.map(path => <li key={path}>{pathLabels[path] ?? path} <span className="break-words text-xs text-slate-400">({path})</span></li>)}
        </ul>
        <p className="mt-3 text-amber-100">This screen is not an architecture recommendation, provider approval or ready-to-deploy design.</p>
      </details>
    </section>
  );
}

function PatternList({ title, items }: { title: string; items: string[] }) {
  return <div className="mt-3">
    <h4 className="font-medium">{title}</h4>
    <ul className="mt-1 list-disc space-y-1 pl-5">{items.map(item => <li key={item}>{item}</li>)}</ul>
  </div>;
}
