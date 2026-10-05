"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";

import type { CapabilityWeights, SensitivityPreview, WeightedPreview } from "@/lib/assessment/weights";
import type { Capability } from "@/lib/assessment/capabilities";

type PreferredField = { capability: Capability; label: string };

const withheldReasons: Record<string, string> = {
  EXCLUDED: "Excluded by a checked hard requirement. Preferences cannot override that exclusion.",
  UNRESOLVED_HARD_CONSTRAINTS: "A checked hard requirement still needs evidence.",
  UNKNOWN_PREFERENCE_EVIDENCE: "At least one preferred capability lacks usable evidence.",
};

export function WeightedPreviewForm({ assessmentId, version, preferred }: {
  assessmentId: string; version: number; preferred: PreferredField[];
}) {
  const [preview, setPreview] = useState<WeightedPreview | null>(null);
  const [baselineWeights, setBaselineWeights] = useState<CapabilityWeights | null>(null);
  const [sensitivity, setSensitivity] = useState<SensitivityPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sensitivityError, setSensitivityError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [sensitivityPending, setSensitivityPending] = useState(false);
  const activeRequest = useRef<{ controller: AbortController; kind: "baseline" | "alternative" } | null>(null);
  const baseline = useRef<CapabilityWeights | null>(null);
  const busy = pending || sensitivityPending;

  useEffect(() => () => {
    activeRequest.current?.controller.abort();
    activeRequest.current = null;
    baseline.current = null;
  }, []);

  function cancelCalculation() {
    const request = activeRequest.current;
    if (!request) return;
    request.controller.abort();
    activeRequest.current = null;
    setPending(false);
    setSensitivityPending(false);
    const message = "Calculation canceled. Your weights are still here; submit again when ready.";
    if (request.kind === "baseline") setError(message);
    else setSensitivityError(message);
  }

  function changeWeights(alternative: boolean) {
    activeRequest.current?.controller.abort();
    activeRequest.current = null;
    setPending(false);
    setSensitivityPending(false);
    setSensitivity(null);
    setSensitivityError(null);
    if (!alternative) {
      baseline.current = null;
      setPreview(null);
      setBaselineWeights(null);
      setError(null);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (activeRequest.current) return;
    baseline.current = null;
    setPreview(null);
    setBaselineWeights(null);
    setSensitivity(null);
    setError(null);
    setSensitivityError(null);
    const form = new FormData(event.currentTarget);
    const params = new URLSearchParams({ expectedVersion: String(version) });
    const weights: CapabilityWeights = {};
    let total = 0;
    for (const field of preferred) {
      const raw = form.get(field.capability);
      if (typeof raw !== "string" || !/^(?:[1-9]|[1-9][0-9]|100)$/.test(raw)) {
        setError("Enter a whole-number weight from 1 to 100 for every preferred capability.");
        return;
      }
      total += Number(raw);
      params.set(field.capability, raw);
      weights[field.capability] = Number(raw);
    }
    if (total !== 100) {
      setError(`Weights must total exactly 100; the current total is ${total}.`);
      return;
    }
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]);
    activeRequest.current = { controller, kind: "baseline" };
    setPending(true);
    try {
      const response = await fetch(`/api/assessments/${assessmentId}/weighted-preview`, {
        method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
        headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: params,
        signal,
      });
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
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
      const result = await response.json() as WeightedPreview;
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (result.assessmentVersion !== version) throw new Error("Invalid preview version");
      setPreview(result);
      baseline.current = weights;
      setBaselineWeights(weights);
    } catch {
      if (!controller.signal.aborted) setError("The preview is temporarily unavailable. Try again later.");
    } finally {
      if (activeRequest.current?.controller === controller) {
        activeRequest.current = null;
        setPending(false);
      }
    }
  }

  async function compare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (activeRequest.current) return;
    const currentBaseline = baseline.current;
    setSensitivity(null);
    setSensitivityError(null);
    if (!currentBaseline) return;
    const form = new FormData(event.currentTarget);
    const params = new URLSearchParams({ expectedVersion: String(version) });
    let total = 0;
    for (const field of preferred) {
      const raw = form.get(`alternative_${field.capability}`);
      if (typeof raw !== "string" || !/^(?:[1-9]|[1-9][0-9]|100)$/.test(raw)) {
        setSensitivityError("Enter a whole-number alternative weight from 1 to 100 for every preferred capability.");
        return;
      }
      total += Number(raw);
      params.set(`baseline_${field.capability}`, String(currentBaseline[field.capability]));
      params.set(`alternative_${field.capability}`, raw);
    }
    if (total !== 100) {
      setSensitivityError(`Alternative weights must total exactly 100; the current total is ${total}.`);
      return;
    }
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]);
    activeRequest.current = { controller, kind: "alternative" };
    setSensitivityPending(true);
    try {
      const response = await fetch(`/api/assessments/${assessmentId}/weight-sensitivity`, {
        method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
        headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: params,
        signal,
      });
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (!response.ok) {
        setSensitivityError({
          400: "These weights no longer match the draft's preferred capabilities. Reload and try again.",
          401: "Your session has expired. Sign in again before comparing.",
          403: "The request was blocked by the same-origin check.",
          404: "This assessment is no longer available.",
          409: "The draft changed after this page loaded. Reload it before comparing.",
        }[response.status] ?? "The sensitivity preview is temporarily unavailable. Try again later.");
        return;
      }
      const result = await response.json() as SensitivityPreview;
      if (controller.signal.aborted) return;
      signal.throwIfAborted();
      if (result.assessmentVersion !== version) throw new Error("Invalid preview version");
      setSensitivity(result);
    } catch {
      if (!controller.signal.aborted) setSensitivityError("The sensitivity preview is temporarily unavailable. Try again later.");
    } finally {
      if (activeRequest.current?.controller === controller) {
        activeRequest.current = null;
        setSensitivityPending(false);
      }
    }
  }

  return (
    <section className="mt-8 rounded-xl border border-cyan-800 p-6" aria-labelledby="weighted-heading">
      <h3 id="weighted-heading" className="text-xl font-semibold">Optional weighted preview</h3>
      <p className="mt-2 text-slate-300">Assign your own positive whole-number weights to every Preferred capability. They must total 100. No weights are supplied by AuthWeave.</p>
      <p className="mt-2 text-sm text-slate-400">This is a temporary, read-only calculation over fictional options. Core withholds points when a checked hard requirement or preferred evidence remains unresolved. It does not rank providers or make a recommendation.</p>
      <form action={`/api/assessments/${assessmentId}/weighted-preview`} method="post" onSubmit={submit}
        onChange={() => changeWeights(false)} aria-busy={pending} className="mt-5">
        <input type="hidden" name="expectedVersion" value={version} />
        <div className="grid gap-4 sm:grid-cols-2">
          {preferred.map(field => (
            <div key={field.capability}>
              <label htmlFor={`weight-${field.capability}`} className="mb-2 block text-sm font-medium">
                {field.label} weight
              </label>
              <input id={`weight-${field.capability}`} name={field.capability} type="number"
                min="1" max="100" step="1" required disabled={busy}
                placeholder={preferred.length === 1 ? "100" : "1–100"}
                className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100" />
            </div>
          ))}
        </div>
        <button type="submit" disabled={busy}
          className="mt-5 rounded-lg bg-cyan-300 px-5 py-2 font-semibold text-slate-950 hover:bg-cyan-200 disabled:opacity-50">
          {pending ? "Calculating…" : "Preview points"}
        </button>
      </form>
      {busy && <div className="mt-4 flex flex-wrap items-center gap-3">
        <p role="status" className="text-sm text-slate-300">Calculating temporary weights. Inputs are locked until this finishes or you cancel.</p>
        <button type="button" onClick={cancelCalculation}
          className="rounded-lg border border-slate-500 px-4 py-2 text-sm hover:bg-slate-800">Cancel calculation</button>
      </div>}
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
      {baselineWeights && preferred.length > 1 && (
        <section className="mt-8 border-t border-cyan-800 pt-6" aria-labelledby="sensitivity-heading">
          <h4 id="sensitivity-heading" className="text-lg font-semibold">What if the weights change?</h4>
          <p className="mt-2 text-sm text-slate-300">Keep the first weights as the baseline and enter a complete alternative totaling 100. Core compares both on one evidence snapshot. Neither set is saved.</p>
          <form action={`/api/assessments/${assessmentId}/weight-sensitivity`} method="post"
            onSubmit={compare} onChange={() => changeWeights(true)} aria-busy={sensitivityPending} className="mt-5">
            <input type="hidden" name="expectedVersion" value={version} />
            {preferred.map(field => <input key={`baseline-${field.capability}`} type="hidden"
              name={`baseline_${field.capability}`} value={baselineWeights[field.capability]} />)}
            <div className="grid gap-4 sm:grid-cols-2">
              {preferred.map(field => (
                <div key={field.capability}>
                  <label htmlFor={`alternative-${field.capability}`} className="mb-2 block text-sm font-medium">
                    {field.label}: alternative weight
                  </label>
                  <input id={`alternative-${field.capability}`} name={`alternative_${field.capability}`}
                    type="number" min="1" max="100" step="1" required disabled={busy}
                    placeholder="1–100"
                    className="w-full rounded-lg border border-slate-500 bg-slate-900 px-3 py-2 text-slate-100" />
                </div>
              ))}
            </div>
            <button type="submit" disabled={busy}
              className="mt-5 rounded-lg border border-cyan-300 px-5 py-2 font-semibold text-cyan-200 hover:bg-slate-800 disabled:opacity-50">
              {sensitivityPending ? "Comparing…" : "Compare weight changes"}
            </button>
          </form>
          {sensitivityError && <p role="alert" className="mt-5 rounded-lg border border-amber-700 p-4 text-amber-100">{sensitivityError}</p>}
          {sensitivity && <SensitivityResults preview={sensitivity} preferred={preferred} />}
        </section>
      )}
    </section>
  );
}

function SensitivityResults({ preview, preferred }: {
  preview: SensitivityPreview; preferred: PreferredField[];
}) {
  return (
    <div className="mt-7" aria-live="polite">
      <p className="text-sm text-slate-400">Draft version {preview.assessmentVersion} · Fictional catalog {preview.catalogVersion} · Same evidence snapshot</p>
      <ul className="mt-4 space-y-4">
        {preview.candidates.map(candidate => (
          <li key={candidate.optionId} className="rounded-lg border border-slate-700 p-4">
            <h5 className="font-semibold">{candidate.displayName}</h5>
            <p className="text-sm text-slate-400">{candidate.plan} · {candidate.region}</p>
            {candidate.status === "SCORED" ? (
              <>
                <p className="mt-3 font-semibold text-cyan-200">
                  {candidate.baselineScore} → {candidate.alternativeScore} / 100 diagnostic points
                  {" "}({candidate.scoreDelta! > 0 ? "+" : ""}{candidate.scoreDelta})
                </p>
                <ul className="mt-2 space-y-1 text-sm text-slate-300">
                  {candidate.capabilityDeltas.map(delta => (
                    <li key={delta.capability}>
                      {preferred.find(field => field.capability === delta.capability)?.label ?? delta.capability}:
                      {" "}weight {delta.baselineWeight} → {delta.alternativeWeight};
                      {" "}{delta.pointChange > 0 ? "+" : ""}{delta.pointChange} points ({delta.outcome.toLowerCase()})
                    </li>
                  ))}
                </ul>
              </>
            ) : (
              <p className="mt-3 text-amber-100">No score or delta: {withheldReasons[candidate.status]}</p>
            )}
          </li>
        ))}
      </ul>
      <p className="mt-4 text-sm text-slate-400">A positive delta means only that this fictional option earned more points under your alternative weights. It is not a ranking or recommendation.</p>
    </div>
  );
}
