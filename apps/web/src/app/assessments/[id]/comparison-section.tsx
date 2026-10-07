import type { ReactNode } from "react";
import type { ComparisonCandidate, ComparisonFinding, ComparisonPreference, SyntheticComparisonSummary } from "@/lib/auth/core-client";
import { hasStaleSyntheticEvidence } from "@/lib/assessment/comparison-evidence";
import { savedRequirementGroups } from "@/lib/assessment/saved-requirements";
import type { SavedRequirementGroup } from "@/lib/assessment/saved-requirements";
import { comparisonVerdicts, deferredComparisonLabel, isComparisonEvidenceGap, relatedComparisonInput } from "@/lib/assessment/comparison-presentation";
import { AssessmentStepButton } from "./assessment-workflow";
import type { ComparisonProvenance, EvidenceGroup } from "@/lib/assessment/comparison-provenance";
import { evidenceFamilies, evidenceGateCopy } from "@/lib/assessment/comparison-matrix";
import { ComparisonMatrix } from "./comparison-matrix";
import { ComparisonFollowUps } from "./comparison-follow-ups";

const tones = {
  excluded: "border-rose-300/20 bg-rose-300/5 text-rose-100",
  unresolved: "border-amber-300/20 bg-amber-300/5 text-amber-100",
  partial: "border-cyan-300/20 bg-cyan-300/5 text-cyan-100",
};
const preferenceLabels = { AVAILABLE: "Preferred capability available", UNAVAILABLE: "Preferred capability unavailable", UNKNOWN: "Availability not established" };

export function ComparisonSection({ comparison, profile, editable, preferencePreview, evidence }: {
  comparison: SyntheticComparisonSummary; profile: Record<string, unknown>; editable: boolean; preferencePreview?: ReactNode; evidence?: ComparisonProvenance[];
}) {
  const groups = savedRequirementGroups(profile);
  const hasPreferences = comparison.candidates.some(candidate => candidate.capabilityPreferences.length > 0);
  return <section className="mt-6 min-w-0" aria-labelledby="comparison-heading">
    <div className="rounded-xl border border-cyan-300/20 bg-cyan-300/5 p-5">
      <p className="text-xs font-semibold uppercase tracking-[0.15em] text-cyan-200">Fictional catalog · Unranked preview</p>
      <h3 id="comparison-heading" className="mt-2 text-xl font-semibold">Understand each option</h3>
      <p className="mt-3 text-sm leading-6 text-slate-300">These are fictional plans for learning and testing the decision rules, not real provider recommendations. The checks do not cover every requirement or establish a winner.</p>
      <p className="mt-3 text-xs leading-5 text-slate-400">Saved assessment version {comparison.assessmentVersion}. Results use saved answers, not unsaved edits. Options remain in the order returned by Core, not a ranking.</p>
      <p className="mt-3 text-sm leading-6 text-slate-300">Auditability capability checks are included: a failed required criterion can exclude an option, and unknown evidence remains a gap. This does not verify deployed logs, export delivery or compliance. Auditability preferences are not scored.</p>
      <div className="mt-4"><AssessmentStepButton step="review">Review saved requirements →</AssessmentStepButton></div>
    </div>
    <dl aria-label="Option statuses, not scores" className="mt-5 grid gap-3 sm:grid-cols-3">
      {Object.entries(comparisonVerdicts).map(([key, verdict]) => {
        const count = comparison.candidates.filter(candidate => candidate.hardVerdict === key).length;
        return <div key={key} className={`rounded-xl border p-4 ${tones[verdict.tone]}`}>
        <dt className="text-sm font-medium">{verdict.short}</dt>
        <dd className="mt-1 text-2xl font-semibold">{count}<span className="ml-2 text-xs font-normal"> {count === 1 ? "option" : "options"}</span></dd>
      </div>; })}
    </dl>
    {hasStaleSyntheticEvidence(comparison.candidates) && <p role="status" className="mt-5 rounded-xl border border-amber-700 p-4 text-sm leading-6 text-amber-100">
      At least one fictional catalog fact is stale under the 90-day policy. It cannot prove support or a mismatch, and affected scores remain withheld. Changing its observation date alone would not verify the source.
    </p>}
    {!hasPreferences && <div className="mt-5 rounded-xl border border-slate-700 p-4 text-sm leading-6 text-slate-300">
      <p>No capability preferences are recorded in this draft. The optional weight preview requires at least one saved preference.</p>
      {editable && groups.find(group => group.id === "capabilities")?.rows && <div className="mt-3"><AssessmentStepButton step="capabilities">Review identity requirements →</AssessmentStepButton></div>}
    </div>}
    <ComparisonFollowUps comparison={comparison} groups={groups} editable={editable} />
    {evidence && <ComparisonMatrix key={`${comparison.assessmentVersion}-${comparison.catalogVersion}-${comparison.auditabilityEvidenceVersion}-${comparison.evaluatedAt}`}
      candidates={comparison.candidates.map(({ optionId, displayName, plan, region, hardVerdict }) => ({ optionId, displayName, plan, region, hardVerdict }))} evidence={evidence} />}
    <ul aria-label="Fictional options in Core order" className="mt-6 space-y-5">
      {comparison.candidates.map((candidate, index) => <ComparisonCard key={candidate.optionId} index={index} candidate={candidate} groups={groups} editable={editable}
        evidence={evidence?.find(item => item.optionId === candidate.optionId)?.groups} />)}
    </ul>
    {preferencePreview}
    <section aria-labelledby="comparison-scope-heading" className="mt-6 rounded-xl border border-slate-700 p-5">
      <h3 id="comparison-scope-heading" className="font-semibold">What this comparison does not check</h3>
      <p className="mt-3 text-sm leading-6 text-slate-300">A passing result applies only to checked constraints. These remaining boundaries still need separate review; recording an answer does not verify them.</p>
      <ul className="mt-4 space-y-2 text-sm text-slate-300">{comparison.deferredPaths.map((path, index) => <li key={`${path}-${index}`} className="rounded-lg bg-white/5 p-3">
        {deferredComparisonLabel(path)}<details className="mt-2 text-xs text-slate-400"><summary className="cursor-pointer">Technical scope path</summary><p className="mt-2 break-all">{path}</p></details>
      </li>)}</ul>
    </section>
    <details className="mt-5 rounded-xl border border-white/10 p-4 text-xs text-slate-400">
      <summary className="cursor-pointer font-medium">Comparison technical details</summary>
      <p className="mt-3 break-all">Catalog: {comparison.catalogVersion}</p>
      <p className="mt-2 break-all">Scoped auditability evidence: {comparison.auditabilityEvidenceVersion}</p>
      <p className="mt-2">Checked: {new Date(comparison.evaluatedAt).toLocaleString("en-US", { timeZone: "UTC" })} UTC</p>
    </details>
  </section>;
}

function ComparisonCard({ candidate, index, groups, editable, evidence }: { candidate: ComparisonCandidate; index: number; groups: SavedRequirementGroup[]; editable: boolean; evidence?: EvidenceGroup[] }) {
  const verdict = comparisonVerdicts[candidate.hardVerdict];
  return <li id={`comparison-option-${index}`} tabIndex={-1} className="min-w-0 scroll-mt-6 rounded-xl border border-slate-700 p-5 sm:p-6 focus-visible:outline-2 focus-visible:outline-cyan-200">
    <h3 className="break-words text-xl font-semibold">{candidate.displayName}</h3>
    <p className="mt-1 break-words text-sm text-slate-400">{candidate.plan} · {candidate.region}</p>
    <div className={`mt-4 rounded-xl border p-4 ${tones[verdict.tone]}`}>
      <p className="font-semibold">{verdict.label}</p><p className="mt-2 text-sm leading-6">{verdict.explanation}</p>
    </div>
    <FindingList title="Why this option is excluded" findings={candidate.exclusionReasons} groups={groups} editable={editable} />
    <FindingList title="What remains unresolved" findings={candidate.informationGaps} groups={groups} editable={editable} />
    {candidate.capabilityPreferences.length > 0 && <section className="mt-5">
      <h4 className="font-semibold">Preferences, separate from hard requirements</h4>
      <p className="mt-2 text-sm leading-6 text-slate-400">A preference alone never excludes an option or reverses an exclusion. No weights, scores or winner are inferred here.</p>
      <ul className="mt-3 space-y-3">{candidate.capabilityPreferences.map(preference => <li key={preference.capability} className="rounded-lg border border-white/10 bg-white/[0.025] p-4">
        <RelatedInput path={preference.profilePath} groups={groups} editable={editable} fallback={preference.capability.replaceAll("_", " ")} />
        <p className="mt-3 text-sm font-medium text-cyan-100">{preferenceLabels[preference.outcome]}</p>
        <p className="mt-2 break-words text-sm leading-6 text-slate-300">{preference.explanation}</p>
        {isComparisonEvidenceGap(preference.reasonCode) && <EvidenceNote />}
        <ReasonDetails finding={preference} />
      </li>)}</ul>
    </section>}
    {evidence && <ComparisonEvidence groups={evidence} plan={candidate.plan} region={candidate.region} />}
  </li>;
}

export function ComparisonEvidence({ groups, plan, region }: { groups: EvidenceGroup[]; plan: string; region: string }) {
  const rows = groups.flatMap(group => group.rows), missing = rows.filter(row => row.gate === "MISSING").length;
  return <details className="mt-5 rounded-xl border border-cyan-300/20 bg-cyan-300/[0.025] p-4">
    <summary className="cursor-pointer text-sm font-semibold text-cyan-100">Inspect fictional evidence · {rows.length - missing} recorded, {missing} missing</summary>
    <p className="mt-3 text-sm leading-6 text-slate-300">These recorded claims belong to this exact plan ({plan}) and region ({region}). Not every listed fact is applied to your requirements. The comparison verdict above is unchanged.</p>
    <p className="mt-3 text-xs leading-5 text-amber-100">All sources are fictional .invalid references, shown as text and never fetched. REVIEWED is a fixture label, not real source verification. Usability is checked at the comparison time under the 90-day policy; an unknown claim still needs information. Missing evidence is not a negative claim.</p>
    <div className="mt-4 space-y-3">{groups.map(group => <details key={group.family} className="rounded-lg border border-white/10 p-3">
      <summary className="cursor-pointer text-sm font-medium">{evidenceFamilies[group.family]}</summary>
      {group.family === "RESIDENCY" && <p className="mt-3 text-xs leading-5 text-slate-400">Recorded destinations, not a region menu. PARTIAL cannot rule out other storage locations; this does not verify processing, transfers or compliance.</p>}
      {group.family === "AUTHENTICATION_CONTROL" && <p className="mt-3 text-xs leading-5 text-slate-400">Availability and enforcement capability are distinct; neither proves configured controls, enrollment/recovery security or an assurance level.</p>}
      {group.family === "AUDITABILITY" && <p className="mt-3 text-xs leading-5 text-slate-400">Identity-provider scope only, not application logs. A documented retention minimum is not deployed retention, export delivery or compliance verification.</p>}
      <ul className="mt-3 space-y-3">{group.rows.map(row => <li key={row.path} className="min-w-0 rounded-lg bg-white/[0.025] p-3">
        <h5 className="break-words text-sm font-medium">{row.label}</h5>
        <p className={`mt-2 text-xs leading-5 ${row.gate === "CURRENT" ? "text-slate-300" : "text-amber-100"}`}>{evidenceGateCopy[row.gate]}</p>
        {row.claim !== null && <p className="mt-2 break-words text-xs leading-5 text-slate-300">Recorded claim: {row.claim}</p>}
        {row.configuration && <p className="mt-2 break-words text-xs leading-5 text-slate-400">Exact configuration scope: {row.configuration}</p>}
        {row.observedAt && <p className="mt-2 break-words text-xs text-slate-400">Observation: <time dateTime={row.observedAt}>{row.observedAt}</time> · {row.evidenceStatus}</p>}
        {row.sourceUrl && <p className="mt-2 break-all text-xs leading-5 text-slate-400">Fictional source: {row.sourceUrl}</p>}
        <p className="mt-2 break-all text-xs text-slate-500">Fact path: {row.path}</p>
      </li>)}</ul>
    </details>)}</div>
  </details>;
}

function FindingList({ title, findings, groups, editable }: { title: string; findings: ComparisonFinding[]; groups: SavedRequirementGroup[]; editable: boolean }) {
  if (findings.length === 0) return null;
  return <section className="mt-5"><h4 className="font-semibold">{title}</h4>
    <ul className="mt-3 space-y-3">{findings.map((finding, index) => <li key={`${finding.profilePath}-${finding.reasonCode}-${index}`} className="rounded-lg border border-white/10 bg-white/[0.025] p-4">
      <RelatedInput path={finding.profilePath} groups={groups} editable={editable} fallback="Checked requirement" />
      <p className="mt-3 break-words text-sm leading-6 text-slate-300">{finding.explanation}</p>
      {isComparisonEvidenceGap(finding.reasonCode) && <EvidenceNote />}
      <ReasonDetails finding={finding} />
    </li>)}</ul>
  </section>;
}

function RelatedInput({ path, groups, editable, fallback }: { path: string; groups: SavedRequirementGroup[]; editable: boolean; fallback: string }) {
  const input = relatedComparisonInput(path, groups);
  return <div>
    <h5 className="font-medium">{input?.label ?? fallback}</h5>
    {input?.rows === null ? <p className="mt-2 text-xs text-amber-100">Related saved inputs cannot be read safely. No value is inferred.</p>
      : input?.rows && input.rows.length > 0 && <dl className="mt-2 space-y-1 text-xs leading-5 text-slate-400">
        {input.rows.map(row => <div key={row.label}><dt className="inline">Saved {row.label}: </dt><dd className="inline break-words text-slate-200">{row.value}</dd></div>)}
      </dl>}
    {input?.step && <div className="mt-3"><AssessmentStepButton step={editable ? input.step : "review"}>
      {editable && input.step !== "review" ? "Review related input →" : "Review saved requirements →"}
    </AssessmentStepButton></div>}
  </div>;
}

function EvidenceNote() {
  return <p className="mt-3 text-xs leading-5 text-amber-100">This gap concerns catalog evidence. Reviewing your input does not verify a provider fact. Do not weaken a requirement just to remove the gap.</p>;
}

function ReasonDetails({ finding }: { finding: ComparisonFinding | ComparisonPreference }) {
  return <details className="mt-3 text-xs leading-5 text-slate-400"><summary className="cursor-pointer">Technical reason</summary>
    <p className="mt-2 break-all">Reason: {finding.reasonCode}</p><p className="break-all">Profile path: {finding.profilePath}</p>
    {"dimension" in finding && <p>Check group: {finding.dimension}</p>}
  </details>;
}
