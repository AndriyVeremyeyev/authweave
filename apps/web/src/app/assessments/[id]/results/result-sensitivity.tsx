"use client";

import { useRef, useState, type FormEvent } from "react";
import type { ResultAdvice } from "@/lib/assessment/decision-advice";
import { postSensitivity, type ResultSensitivity } from "@/lib/assessment/decision-sensitivity";

export function ResultSensitivityView({ baseline }: { baseline: Pick<ResultAdvice, "summary" | "candidates" | "rankGroups"> }) {
  const { summary } = baseline, values = summary.weights.values;
  const [weights, setWeights] = useState(() => Object.fromEntries(values.map(w => [w.capability, String(w.weight)])));
  const [pending, setPending] = useState(false), busy = useRef(false);
  const [comparison, setComparison] = useState<ResultSensitivity | null>(null);
  const [failed, setFailed] = useState(false);
  const total = values.reduce((n, w) => n + (Number(weights[w.capability]) || 0), 0);
  const valid = total === 100 && values.every(w => /^[1-9][0-9]*$/.test(weights[w.capability]) && Number(weights[w.capability]) <= 100);
  const adjustable = values.length > 1;
  async function compare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!valid || busy.current || !adjustable) return;
    busy.current = true; setPending(true); setComparison(null); setFailed(false);
    const body = new URLSearchParams({ version: String(summary.item.reference.version), resultSha256: summary.item.reference.resultSha256, weightMode: "EXPLICIT" });
    values.forEach(w => body.set(`weight_${w.capability}`, weights[w.capability]));
    const result = await postSensitivity(summary.assessmentId, body.toString(), baseline);
    setComparison(result); setFailed(!result); busy.current = false; setPending(false);
  }
  return <section aria-labelledby="weight-sensitivity-title" className="mt-8 rounded-2xl border border-slate-700 bg-slate-900/70 p-5 sm:p-6">
    <h2 id="weight-sensitivity-title" className="text-xl font-semibold">What if the preference weights change?</h2>
    <p className="mt-3 text-sm text-slate-300">Only weights change. The original profile, catalog, policy, evidence dates and evaluation clock stay pinned.
      Hard constraints, eligibility and conditional architecture stay unchanged. This comparison does not save or approve a result.</p>
    {!adjustable ? <p className="mt-4 text-sm text-amber-200">{values.length === 0 ? "This saved profile has no explicit capability preferences to reweight." :
      `Only ${values[0].capability.replaceAll("_", " ")} is preferred: its weight must stay 100. There is no alternative weighting to compare.`}
      {" "}Changing preferences requires editing the profile and explicitly recording a separate result.</p> : <form onSubmit={compare} className="mt-5">
      <fieldset disabled={pending}>
        <legend className="text-sm font-medium">Comparison weights — positive integers, total 100</legend>
        <div className="mt-3 grid gap-3 sm:grid-cols-3">{values.map(w => <label key={w.capability} className="text-sm text-slate-200">
          {w.capability.replaceAll("_", " ")} <span className="text-slate-400">(saved {w.weight})</span>
          <input name={`weight_${w.capability}`} type="number" min="1" max="100" step="1" required value={weights[w.capability]}
            onChange={event => { setWeights({ ...weights, [w.capability]: event.target.value }); setComparison(null); setFailed(false); }}
            className="mt-1 block w-full rounded-lg border border-slate-600 bg-slate-950 px-3 py-2 focus:outline-2 focus:outline-cyan-300" />
        </label>)}</div>
        <p className="mt-3 text-sm text-slate-300" aria-live="polite">Comparison total: {total} / 100{!valid ? " — enter positive integers totaling 100." : ""}</p>
        <button type="submit" disabled={!valid} className="mt-4 rounded-lg bg-cyan-200 px-4 py-2 text-sm font-semibold text-slate-950 disabled:opacity-40">Compare weights without saving</button>
      </fieldset>
    </form>}
    <p role="status" aria-live="polite" className="mt-3 text-sm text-slate-300">{pending ? "Comparing on the original pinned inputs…" :
      failed ? "Comparison unavailable. Nothing was saved. Check your session and try again explicitly; no automatic retry was made." :
      comparison ? "Comparison complete. The saved result is unchanged." : ""}</p>
    {comparison && <div className="mt-4 space-y-4">
      <p className="text-sm text-slate-300">Saved: {comparison.before.status.replaceAll("_", " ")} · Comparison: {comparison.after.status.replaceAll("_", " ")}.</p>
      {comparison.after.candidates.map((candidate, i) => {
        const before = comparison.before.candidates[i], h = candidate.hardChecks;
        const rank = (side: ResultSensitivity["before"]) => side.rankGroups.find(g => g.optionIds.includes(h.optionId));
        const oldRank = rank(comparison.before), newRank = rank(comparison.after);
        const score = (s: typeof candidate.score) => !s ? "Not scored" : s.unknownWeight ? `${s.lowerBound}–${s.upperBound} points (${s.unknownWeight} unknown)` : `${s.lowerBound} points`;
        return <article key={h.optionId} className="rounded-xl border border-slate-700 p-4 text-sm">
          <h3 className="font-semibold">{h.product} · {h.plan}</h3>
          <p className="mt-2">Unchanged hard verdict: {h.hardVerdict}. Saved {score(before.score)} → comparison {score(candidate.score)}.</p>
          <p className="mt-2 text-slate-300">Saved rank: {oldRank?.rank ?? "unranked"}{oldRank && oldRank.optionIds.length > 1 ? " (tie)" : ""} → comparison rank: {newRank?.rank ?? "unranked"}{newRank && newRank.optionIds.length > 1 ? " (tie)" : ""}.
            No automatic winner or tie-breaker. Points are not confidence.</p>
          {candidate.score?.contributions.map((c, j) => <p key={c.capability} className="mt-2 text-slate-300">{c.capability.replaceAll("_", " ")}: unchanged {c.outcome.toLowerCase()} ·
            weight {before.score!.contributions[j].weight} → {c.weight} · earned {before.score!.contributions[j].earnedPoints} → {c.earnedPoints}.</p>)}
        </article>;
      })}
    </div>}
  </section>;
}
