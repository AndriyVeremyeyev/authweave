import type { SyntheticComparisonSummary } from "@/lib/assessment/comparison";
import type { SavedRequirementGroup } from "@/lib/assessment/saved-requirements";
import { comparisonFollowUps, followUpCopy } from "@/lib/assessment/comparison-follow-ups";
import { relatedComparisonInput } from "@/lib/assessment/comparison-presentation";
import { AssessmentStepButton } from "./assessment-workflow";

export function ComparisonFollowUps({ comparison, groups, editable }: {
  comparison: SyntheticComparisonSummary; groups: SavedRequirementGroup[]; editable: boolean;
}) {
  const followUps = comparisonFollowUps(comparison);
  return <section aria-labelledby="comparison-follow-ups-heading" className="mt-6 rounded-xl border border-amber-300/20 p-4 sm:p-5">
    <h3 id="comparison-follow-ups-heading" className="text-lg font-semibold">What still needs clarification?</h3>
    <p className="mt-3 text-sm leading-6 text-slate-300">These are the unknowns returned for saved version {comparison.assessmentVersion}, grouped to avoid repeating the same topic across options. Counts are affected options, not severity, priority or completeness. Excluded options keep their gaps; resolving one cannot reverse an independent exclusion.</p>
    <p className="mt-2 text-xs leading-5 text-slate-400">Hard-check gaps and unknown preferences stay separate. This list does not include known failures or deferred topics; both remain below. No new question, verdict or score is generated.</p>
    {followUps.length === 0 ? <p className="mt-4 text-sm text-cyan-100">No unknowns were returned within these checks or capability preferences. Known exclusions and unchecked boundaries may still remain; this is not a complete suitability check.</p>
      : <div className="mt-5 space-y-5">{Object.entries(followUpCopy).map(([kind, copy]) => {
        const items = followUps.filter(item => item.kind === kind);
        if (items.length === 0) return null;
        return <section key={kind} aria-labelledby={`comparison-follow-ups-${kind}`}>
          <h4 id={`comparison-follow-ups-${kind}`} className="font-semibold text-amber-100">{copy.heading}</h4>
          <p className="mt-2 text-xs leading-5 text-slate-400">{copy.next}</p>
          <ul className="mt-3 space-y-3">{items.map((item, index) => {
            const input = relatedComparisonInput(item.profilePath, groups);
            const affected = new Set(item.occurrences.map(occurrence => occurrence.optionIndex)).size;
            return <li key={index} className="min-w-0 rounded-lg border border-white/10 p-3">
              <details>
                <summary className="cursor-pointer break-words text-sm font-medium">{input?.label ?? "Checked scope"} · {affected} {affected === 1 ? "option" : "options"}<span className="mt-1 block text-xs font-normal text-slate-400">{item.source === "HARD_CHECK" ? "Hard-check information gap" : "Unknown preference — not a hard failure"} · {item.reasonCode}</span></summary>
                <ul className="mt-3 space-y-3">{item.occurrences.map((occurrence, occurrenceIndex) => {
                  const option = comparison.candidates[occurrence.optionIndex];
                  return <li key={occurrenceIndex} className="min-w-0 rounded-lg bg-white/[0.025] p-3 text-xs leading-5">
                    <a href={`#comparison-option-${occurrence.optionIndex}`} className="break-words font-medium text-cyan-100 underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">{option.displayName}</a>
                    <p className="mt-1 break-words text-slate-400">{option.plan} · {option.region}</p>
                    <p className="mt-2 break-words text-slate-300">{occurrence.explanation}</p>
                  </li>;
                })}</ul>
                <p className="mt-3 break-all text-xs leading-5 text-slate-500">Check group: {item.dimension} · Profile path: {item.profilePath}</p>
                {input?.rows === null && <p className="mt-3 text-xs text-amber-100">Related saved inputs cannot be read safely. No answer or editor is inferred.</p>}
                {input?.step && <div className="mt-3"><AssessmentStepButton step={editable ? input.step : "review"}>
                  {editable && input.step !== "review" ? "Inspect related saved input →" : "Review saved requirements →"}
                </AssessmentStepButton></div>}
              </details>
            </li>;
          })}</ul>
        </section>;
      })}</div>}
  </section>;
}
