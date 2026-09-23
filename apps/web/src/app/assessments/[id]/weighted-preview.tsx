"use client";

import { useState, type FormEvent } from "react";

import type { WeightedPreview } from "@/lib/assessment/weights";

type PreferredField = { capability: string; label: string };

const withheldReasons: Record<string, string> = {
  EXCLUDED: "Excluded by a checked hard requirement. Preferences cannot override that exclusion.",
  UNRESOLVED_HARD_CONSTRAINTS: "A checked hard requirement still needs evidence.",
  UNKNOWN_PREFERENCE_EVIDENCE: "At least one preferred capability lacks usable evidence.",
};

export function WeightedPreviewForm({ assessmentId, version, preferred }: {
  assessmentId: string; version: number; preferred: PreferredField[];
}) {
  const [preview, setPreview] = useState<WeightedPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPreview(null);
    setError(null);
    const form = new FormData(event.currentTarget);
    const params = new URLSearchParams({ expectedVersion: String(version) });
    let total = 0;
    for (const field of preferred) {
      const raw = form.get(field.capability);
      if (typeof raw !== "string" || !/^(?:[1-9]|[1-9][0-9]|100)$/.test(raw)) {
        setError("Enter a whole-number weight from 1 to 100 for every preferred capability.");
        return;
      }
      total += Number(raw);
      params.set(field.capability, raw);
    }
    if (total !== 100) {
      setError(`Weights must total exactly 100; the current total is ${total}.`);
      return;
    }
    setPending(true);
    try {
      const response = await fetch(`/api/assessments/${assessmentId}/weighted-preview`, {
        method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
        headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: params,
      });
      if (!response.ok) {
        setError({
          400: "These weights no longer match the draft's preferred capabilities. Reload and try again.",
          401: "Your session has expired. Sign in again before previewing.",
          403: "The request was blocked by the same-origin check.",
          404: "This assessment is no longer available.",
          409: "The draft changed after this page loaded. Reload it before previewing.",
        }[response.status] ?? "The preview is temporarily unavailable. Try again later.");
        return;
      }
      setPreview(await response.json() as WeightedPreview);
    } catch {
      setError("The preview is temporarily unavailable. Try again later.");
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="mt-8 rounded-xl border border-cyan-800 p-6" aria-labelledby="weighted-heading">
      <h3 id="weighted-heading" className="text-xl font-semibold">Optional weighted preview</h3>
      <p className="mt-2 text-slate-300">Assign your own positive whole-number weights to every Preferred capability. They must total 100. No weights are supplied by AuthWeave.</p>
      <p className="mt-2 text-sm text-slate-400">This is a temporary, read-only calculation over fictional options. Core withholds points when a checked hard requirement or preferred evidence remains unresolved. It does not rank providers or make a recommendation.</p>
      <form action={`/api/assessments/${assessmentId}/weighted-preview`} method="post" onSubmit={submit}
        onChange={() => { setPreview(null); setError(null); }} className="mt-5">
        <input type="hidden" name="expectedVersion" value={version} />
        <div className="grid gap-4 sm:grid-cols-2">
          {preferred.map(field => (
            <div key={field.capability}>
              <label htmlFor={`weight-${field.capability}`} className="mb-2 block text-sm font-medium">
                {field.label} weight
              </label>
              <input id={`weight-${field.capability}`} name={field.capability} type="number"
                min="1" max="100" step="1" required placeholder={preferred.length === 1 ? "100" : "1–100"}
                className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100" />
            </div>
          ))}
        </div>
        <button type="submit" disabled={pending}
          className="mt-5 rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200 disabled:opacity-50">
          {pending ? "Calculating…" : "Preview points"}
        </button>
      </form>
      {error && <p role="alert" className="mt-5 rounded-lg border border-amber-700 p-4 text-amber-100">{error}</p>}
      {preview && (
        <div className="mt-7" aria-live="polite">
          <p className="text-sm text-slate-400">Draft version {preview.assessmentVersion} · Fictional catalog {preview.catalogVersion}</p>
          <ul className="mt-4 space-y-4">
            {preview.candidates.map(candidate => (
              <li key={candidate.optionId} className="rounded-lg border border-slate-700 p-4">
                <h4 className="font-semibold">{candidate.displayName}</h4>
                <p className="text-sm text-slate-400">{candidate.plan} · {candidate.region}</p>
                {candidate.status === "SCORED" ? (
                  <>
                    <p className="mt-3 font-semibold text-cyan-200">{candidate.score} / 100 diagnostic points</p>
                    <ul className="mt-2 space-y-1 text-sm text-slate-300">
                      {candidate.contributions.map(contribution => (
                        <li key={contribution.capability}>
                          {preferred.find(field => field.capability === contribution.capability)?.label ?? contribution.capability}:
                          {" "}{contribution.earnedPoints} / {contribution.weight} points ({contribution.outcome.toLowerCase()})
                        </li>
                      ))}
                    </ul>
                  </>
                ) : (
                  <p className="mt-3 text-amber-100">No score: {withheldReasons[candidate.status]}</p>
                )}
              </li>
            ))}
          </ul>
          <p className="mt-4 text-sm text-slate-400">Points cover only these explicit preferences. Exclusions, unresolved evidence, cost and other deferred requirements remain outside the score.</p>
        </div>
      )}
    </section>
  );
}
