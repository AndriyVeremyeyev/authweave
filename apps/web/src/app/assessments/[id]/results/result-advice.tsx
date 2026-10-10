import type { AdviceChoice, AdviceEvidence, AdviceOptionCheck, ResultAdvice } from "@/lib/assessment/decision-advice";

const label = (value: string) => value.toLowerCase().replaceAll("_", " ").replace(/^./, c => c.toUpperCase());
const date = (value: string) => value.replace("T", " ").replace("Z", " UTC");
const reasons: Record<string, string> = {
  SUPPORTED: "The recorded claim meets this checked requirement, subject to its scope and conditions.",
  UNSUPPORTED: "The recorded claim fails this checked requirement; preference points cannot override it.",
  EVIDENCE_MISSING: "No matching dated evidence was recorded. Obtain evidence for this exact option and fact.",
  EVIDENCE_UNREVIEWED: "The exact claim has no recorded source verdict. It cannot establish support.",
  EVIDENCE_CONTRADICTED: "The recorded source verdict contradicts the claim. Resolve the contradiction before relying on it.",
  EVIDENCE_INSUFFICIENT: "The source verdict is insufficient for this exact claim. More specific evidence is needed.",
  EVIDENCE_STALE: "The evidence was too old at the original evaluation clock. Opening this result does not refresh it.",
  EVIDENCE_FUTURE: "The observation date is after the original evaluation clock and cannot support that calculation.",
  REQUIRED_UNKNOWN: "The required fact remains unknown; no support or eligibility is inferred.",
  CONFIGURATION_REQUIRED: "Configuration evidence is needed; optional capability availability is not proof of disablement.",
  REQUIRED_SCIM_CANNOT_BE_REPLACED_BY_JIT: "SCIM is required. Login-time JIT creation cannot replace that lifecycle requirement.",
  CONDITIONAL_PROFILE_FIT: "This is a conditional fit to the saved requirements, not verified deployment or a final choice.",
  PLANNING_ONLY: "This is a planning boundary, not observed implementation or certification.",
};
function Reason({ value }: { value: string }) {
  return <p className="mt-2 text-sm leading-6 text-slate-300">{reasons[value] ?? label(value)} <span className="break-all text-xs text-slate-500">({value})</span></p>;
}
function TextList({ title, values }: { title: string; values: string[] }) {
  return values.length > 0 && <div className="mt-4"><h5 className="text-sm font-medium text-slate-200">{title}</h5>
    <ul className="mt-2 list-disc space-y-2 pl-5 text-sm leading-6 text-slate-300">{values.map((v, i) => <li key={i} className="break-words">{v}</li>)}</ul></div>;
}
function Evidence({ evidence }: { evidence: AdviceEvidence | null }) {
  if (!evidence) return <p className="mt-2 text-sm text-slate-400">No claim evidence attached to this check. This is not proof of support.</p>;
  const fictional = new URL(evidence.sourceUrl).hostname.endsWith(".invalid");
  return <div className="mt-3 min-w-0 border-l border-white/15 pl-3 text-sm text-slate-400">
    {fictional && <p className="mb-2 font-medium text-amber-200">Fictional test evidence — not a real provider source</p>}
    <p>Recorded source: <a href={evidence.sourceUrl} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer" className="break-all text-cyan-200 underline">{evidence.sourceUrl}</a></p>
    <p className="mt-2">Observed <time dateTime={evidence.observedAt}>{date(evidence.observedAt)}</time> · original date, not a fresh check</p>
    <p className="mt-2">Recorded verdict: {evidence.sourceAssertion ? label(evidence.sourceAssertion) : "No source verdict"}. External source truth is not verified.</p>
    {evidence.documentedMinimumRetentionDays !== null && <p className="mt-2">Documented minimum retention: {evidence.documentedMinimumRetentionDays} days; not observed tenant retention.</p>}
    <TextList title="Claim conditions" values={evidence.conditions} />
    <details className="mt-3"><summary className="cursor-pointer">Exact claim digest</summary><p className="mt-2 break-all text-xs">{evidence.claimSha256}</p></details>
  </div>;
}
function OptionChecks({ checks }: { checks: AdviceOptionCheck[] }) {
  return checks.length > 0 && <details className="mt-4 rounded-lg border border-white/10 p-3">
    <summary className="cursor-pointer text-sm text-slate-200">Scoped option compatibility ({checks.length})</summary>
    <ul className="mt-3 space-y-4">{checks.map(check => <li key={check.optionId} className="min-w-0">
      <p className="break-words text-sm text-slate-300">{check.optionId}: {label(check.match)}</p>
      {check.capabilities.map(capability => <div key={capability.capability} className="mt-3 border-l border-white/10 pl-3">
        <p className="text-sm text-slate-300">{capability.capability}: {capability.usable ? "Usable in this saved conditional check" : "Not established as usable"}</p>
        <Reason value={capability.reasonCode} /><Evidence evidence={capability.evidence} />
      </div>)}
    </li>)}</ul>
  </details>;
}
const titles: Record<string, string> = { SERVER_SIDE_SESSION: "Server-side session", BFF_SESSION: "BFF/session",
  SPA_CODE_PKCE: "SPA with Authorization Code + PKCE", NATIVE_CODE_PKCE: "Native client with Authorization Code + PKCE",
  M2M_CLIENT_CREDENTIALS: "Machine-to-machine Client Credentials", SCIM_PUSH: "SCIM provisioning", JIT_LOGIN: "JIT at login", SCIM_AND_JIT: "SCIM + JIT" };
const dispositions = { RECOMMENDED: "Conditionally recommended", ALTERNATIVE: "Conditional alternative", UNRESOLVED: "More information needed", NOT_APPLICABLE: "Not applicable to the saved requirements" };
function Choice({ choice }: { choice: AdviceChoice }) {
  return <>
    <h4 className="text-lg font-medium">{titles[choice.id] ?? choice.id}</h4>
    <p className="mt-2 text-sm text-cyan-200">{dispositions[choice.disposition]}</p><Reason value={choice.reasonCode} />
    {choice.conditionalOptionIds.length > 0 && <p className="mt-3 break-words text-sm text-slate-300">Conditional options: {choice.conditionalOptionIds.join(", ")}</p>}
    <TextList title="Pros" values={choice.pros} /><TextList title="Trade-offs" values={choice.cons} /><TextList title="Conditions to verify" values={choice.conditions} />
    <OptionChecks checks={choice.optionChecks} />
    {choice.references.length > 0 && <details className="mt-4 text-sm text-slate-400"><summary className="cursor-pointer">Design references (not provider evidence)</summary>
      <ul className="mt-2 space-y-2">{choice.references.map((href, i) => <li key={i}><a href={href} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer" className="break-all text-cyan-200 underline">{href}</a></li>)}</ul>
    </details>}
  </>;
}
export function ResultAdviceView({ advice }: { advice: ResultAdvice }) {
  return <section aria-labelledby="explanation-heading" className="mt-10 min-w-0 space-y-8">
    <div><h2 id="explanation-heading" className="text-2xl font-semibold">Why this saved result?</h2>
      <p className="mt-3 text-sm leading-6 text-slate-300">Every finding below belongs to the exact historical version above. Source dates and verdicts are preserved, not refreshed. Fictional .invalid claims are test data, never real provider evidence. Links open only when you choose them; this page fetches no external source.</p>
      <p className="mt-3 text-sm leading-6 text-slate-300">Hard failures exclude an option. Unknown hard requirements keep it unresolved. Preference points apply only to eligible options and cannot repair a hard failure. Read option IDs together with product, plan, region, deployment and configuration scope.</p>
    </div>
    <div><h3 className="text-xl font-medium">Shortlist and ties</h3>
      {advice.rankGroups.length ? <ol className="mt-3 space-y-3 text-sm text-slate-300">{advice.rankGroups.map(group => <li key={group.rank} className="break-words">Rank {group.rank}: {group.optionIds.join(", ")}{group.optionIds.length > 1 ? " — tied; no automatic winner" : ""}</li>)}</ol>
        : <p className="mt-3 text-sm text-slate-300">No ranks are assigned: {advice.summary.shortlist.length ? "the saved shortlist is unranked; weights are absent or preference evidence is incomplete." : "there is no eligible shortlist. Review failed and unknown checks below before selecting an option."}</p>}
      <p className="mt-3 text-sm text-slate-400">These are the original weights and points. The separate weight comparison below keeps this saved result unchanged.</p>
    </div>
    <div><h3 className="text-xl font-medium">Requirement findings and preference contributions</h3>
      <div className="mt-4 space-y-4">{advice.candidates.map(candidate => <details key={candidate.hardChecks.optionId} className="min-w-0 rounded-xl border border-white/10 bg-white/[0.025] p-5">
        <summary className="cursor-pointer break-words font-medium">Explain option {candidate.hardChecks.optionId} — {label(candidate.hardChecks.hardVerdict)}</summary>
        <p className="mt-3 break-words text-sm text-slate-400">Configuration scope: {candidate.hardChecks.configuration}</p>
        <ul className="mt-4 space-y-5">{candidate.hardChecks.findings.map(finding => <li key={finding.checkId} className="min-w-0 border-t border-white/10 pt-4">
          <h4 className="break-words text-sm font-medium">{finding.profilePath} · {finding.criticality} · {label(finding.outcome)}</h4>
          <Reason value={finding.reasonCode} />
          {finding.outcome === "UNKNOWN" && <p className="mt-2 text-sm text-amber-200">Next action: resolve the missing requirement or obtain and review evidence for this exact scope. Do not award support by assumption.</p>}
          {finding.outcome === "FAIL" && <p className="mt-2 text-sm text-amber-200">Next action: consider another scoped option, or explicitly revise the requirement if it was incorrect. Weights cannot override this failure.</p>}
          <p className="mt-2 break-words text-xs text-slate-500">Check: {finding.checkId}{finding.factPath ? ` · Fact: ${finding.factPath}` : " · No provider fact used"}</p>
          <Evidence evidence={finding.evidence} />
        </li>)}</ul>
        {candidate.score ? <div className="mt-6"><h4 className="font-medium">Saved preference contributions</h4>
          <ul className="mt-3 space-y-4">{candidate.score.contributions.map(c => <li key={c.capability} className="border-t border-white/10 pt-3">
            <p className="text-sm">{c.capability}: {c.earnedPoints} of {c.weight} points · {label(c.outcome)}{c.outcome === "UNKNOWN" ? ` · ${c.weight} unknown points, not confidence` : ""}</p>
            <Reason value={c.reasonCode} /><Evidence evidence={c.evidence} />
          </li>)}</ul>
        </div> : <p className="mt-6 text-sm text-slate-400">No preference score was calculated for this option.</p>}
      </details>)}</div>
    </div>
    <div><h3 className="text-xl font-medium">Conditional architecture advice</h3>
      <p className="mt-3 text-sm leading-6 text-slate-300">Saved architecture status: {label(advice.architecture.status)}. Recommended means a conditional design fit, not a configured or approved solution. Prerequisites, provider interoperability, API authorization and lifecycle behavior still need verification.</p>
      <div className="mt-4 space-y-4">{advice.architecture.patterns.map(pattern => <details key={pattern.choice.id} className="rounded-xl border border-white/10 p-5">
        <summary className="cursor-pointer font-medium">{titles[pattern.choice.id]} — {dispositions[pattern.choice.disposition]}</summary>
        <div className="mt-4"><Choice choice={pattern.choice} />
          <h5 className="mt-4 text-sm font-medium">Unverified design prerequisites</h5>
          <ul className="mt-2 space-y-2 text-sm text-slate-400">{pattern.prerequisites.checks.map(c => <li key={c.prerequisiteId} className="break-words">{label(c.prerequisiteId)}: {label(c.outcome)} ({c.reasonCode})</li>)}</ul>
        </div>
      </details>)}</div>
      <h4 className="mt-6 font-medium">API protection: {label(advice.architecture.apiProtection.status)}</h4>
      <TextList title="API conditions to verify" values={advice.architecture.apiProtection.conditions} />
      <OptionChecks checks={advice.architecture.apiProtection.optionChecks} />
    </div>
    <div><h3 className="text-xl font-medium">Provisioning and offboarding alternatives</h3>
      <p className="mt-3 text-sm leading-6 text-slate-300">Required SCIM is not replaced by JIT. A SCIM label alone does not prove User operations, Group transport, disablement or session/token revocation. These conditions remain separate from authentication.</p>
      <div className="mt-4 space-y-4">{advice.architecture.provisioning.map(choice => <details key={choice.id} className="rounded-xl border border-white/10 p-5">
        <summary className="cursor-pointer font-medium">{titles[choice.id]} — {dispositions[choice.disposition]}</summary><div className="mt-4"><Choice choice={choice} /></div>
      </details>)}</div>
    </div>
    <div><h3 className="text-xl font-medium">Responsibilities and cost uncertainty</h3>
      <p className="mt-3 text-sm leading-6 text-slate-300">Managed hosting does not remove application responsibilities for tenant mapping, authorization, lifecycle and operational review. Self-hosting adds responsibility for infrastructure, upgrades, backups and availability. Neither deployment mode proves a free tier, workload fit or total cost.</p>
      <ul className="mt-4 space-y-3">{advice.limitations.map((l, i) => <li key={i} className="rounded-lg border border-white/10 p-4 text-sm leading-6 text-slate-300">
        <p className="break-words font-medium">{l.profilePath ?? "Source authority"}{l.blocksDeploymentRecommendation ? " — blocks verified deployment recommendation" : ""}</p>
        <p className="mt-2">{l.explanation}</p><p className="mt-2 break-all text-xs text-slate-500">{l.reasonCode}</p>
      </li>)}</ul>
    </div>
    <div><h3 className="text-xl font-medium">Next actions from this saved calculation</h3><TextList title="Review before relying on this advice" values={advice.followUps} /></div>
  </section>;
}
