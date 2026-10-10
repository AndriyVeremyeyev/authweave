"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { resultReferenceQuery, type ResultItem } from "@/lib/assessment/decision-results";
import { parseRecordingForm, postRecording } from "@/lib/assessment/decision-recording";

const subscribe = () => () => {};
const clientSnapshot = () => true, serverSnapshot = () => false;

export function DecisionRecordingForm({ assessmentId, assessmentVersion, resultId, previous, preferred }: {
  assessmentId: string; assessmentVersion: number; resultId: string; previous: ResultItem | null; preferred: string[];
}) {
  const ready = useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot), router = useRouter();
  const [state, setState] = useState<"editing" | "sending" | "uncertain" | "denied">("editing");
  const [message, setMessage] = useState("");
  const attempt = useRef<string | null>(null), busy = useRef(false);
  useEffect(() => {
    if (state !== "sending" && state !== "uncertain") return;
    const protect = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener("beforeunload", protect); return () => window.removeEventListener("beforeunload", protect);
  }, [state]);
  async function submit(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault(); if (busy.current) return;
    if (!attempt.current) {
      const data = new FormData(event!.currentTarget), params = new URLSearchParams();
      for (const [key, value] of data) { if (typeof value !== "string") return; params.append(key, value); }
      try { parseRecordingForm(params); } catch { setMessage("Enter a complete exact catalog reference, confirm the operation and use positive integer weights totaling 100."); return; }
      attempt.current = params.toString();
    }
    busy.current = true; setState("sending"); setMessage("Recording once with the displayed profile and result head…");
    try {
      const outcome = await postRecording(assessmentId, attempt.current);
      if (outcome.outcome === "denied") {
        setState("denied"); setMessage("This operation was rejected. Review the requirements and reference, or sign in again. No automatic retry was made."); return;
      }
      if (outcome.outcome === "uncertain") {
        setState("uncertain"); setMessage(outcome.conflict ? "The profile/result head changed, the key conflicted, or publication/replay verification was unavailable. A write may exist. Inspect history before preparing another calculation; no inputs or catalog were substituted." : "The response was not confirmed. The write may already exist. Retry only this identical request/key, or inspect history before starting another calculation. Do not assume it failed."); return;
      }
      router.push(`/assessments/${assessmentId}/results/${outcome.ack.reference.resultId}?${resultReferenceQuery(outcome.ack.reference)}`);
    } catch {
      setState("uncertain"); setMessage("The response was not confirmed. The write may already exist. Retry only this identical request/key, or inspect history before starting another calculation. Do not assume it failed.");
    } finally { busy.current = false; }
  }
  const locked = state !== "editing";
  return <section className="mt-6">
    <p className="text-sm leading-6 text-slate-300">Core will calculate from saved profile version {assessmentVersion}, an exact workflow-verified publication and your explicit weights. This records advice, not source authentication, deployment verification, compliance approval or a final architecture decision. Synthetic previews and unreviewed drafts cannot substitute for a publication.</p>
    <p className="mt-3 text-sm leading-6 text-slate-300">{previous ? `A new result follows version ${previous.reference.version}. Older results stay unchanged. The previous catalog reference below is a convenience, not an automatic latest-catalog selection; explicitly confirm it or enter another exact publication.` : "This is the first result. Obtain the three exact catalog reference fields from a published curator receipt. No catalog is selected automatically. If no reviewed publication exists, recording remains unavailable."}</p>
    <form onSubmit={submit} className="mt-6">
      <fieldset disabled={locked} className="grid gap-5">
        <input type="hidden" name="resultId" value={resultId} /><input type="hidden" name="expectedAssessmentVersion" value={assessmentVersion} />
        <input type="hidden" name="previousResultId" value={previous?.reference.resultId ?? ""} />
        <input type="hidden" name="previousVersion" value={previous?.reference.version ?? ""} />
        <input type="hidden" name="previousResultSha256" value={previous?.reference.resultSha256 ?? ""} />
        <input type="hidden" name="weightMode" value={preferred.length ? "EXPLICIT" : "NONE"} />
        <legend className="mb-4 text-lg font-medium">Exact published catalog</legend>
        {(["snapshotId", "catalogVersion", "snapshotSha256"] as const).map((key, index) => <label key={key} className="grid gap-2 text-sm">
          {["Catalog snapshot ID", "Catalog version", "Catalog snapshot SHA-256"][index]}
          <input name={key} required autoComplete="off" spellCheck={false} defaultValue={previous?.catalog[key] ?? ""}
            maxLength={index === 0 ? 36 : index === 1 ? 100 : 64} className="min-w-0 rounded-lg border border-white/15 bg-slate-950 px-3 py-2 font-mono text-xs" />
        </label>)}
        <h2 className="mt-2 text-lg font-medium">Explicit preference weights</h2>
        <p className="text-sm text-slate-300">{preferred.length ? "Only saved Preferred capabilities receive points. Enter positive integer weights totaling 100. Points cannot compensate for a missing Required capability and do not measure confidence." : "No capabilities are saved as Preferred. This explicitly uses NONE with no points or ranking."}</p>
        {preferred.map(capability => <label key={capability} className="flex flex-wrap items-center justify-between gap-3 text-sm">{capability} weight
          <input type="number" name={`weight_${capability}`} required min={1} max={100} step={1} className="w-24 rounded-lg border border-white/15 bg-slate-950 px-3 py-2" />
        </label>)}
        <label className="flex items-start gap-3 rounded-lg border border-white/15 p-4 text-sm leading-6">
          <input type="checkbox" required name="confirmation" value={previous ? "REEVALUATE_DECISION_RESULT" : "RECORD_DECISION_RESULT"} className="mt-1" />
          I explicitly confirm this exact catalog, profile version and weights, and {previous ? `a new calculation after result version ${previous.reference.version}` : "the initial saved calculation"}. This does not approve a decision.
        </label>
        <button disabled={!ready} type="submit" className="rounded-lg border border-cyan-300/40 bg-cyan-300/10 px-4 py-3 text-sm text-cyan-100 disabled:opacity-50">{previous ? "Record a new result version" : "Record initial calculation"}</button>
      </fieldset>
    </form>
    {message && <p role="alert" className="mt-5 rounded-lg border border-amber-300/30 p-4 text-sm leading-6 text-amber-100">{message}</p>}
    {state === "uncertain" && <button type="button" onClick={() => void submit()} className="mt-4 rounded-lg border border-amber-300/40 px-4 py-2 text-sm">Retry identical request only</button>}
    {state === "denied" && <button type="button" onClick={() => window.location.reload()} className="mt-4 rounded-lg border border-white/20 px-4 py-2 text-sm">Reload current profile and result head</button>}
    <p className="mt-5 break-all text-xs text-slate-400">Idempotency key for this request: {resultId}. Retries reuse this key and every input; a new calculation requires a new key.</p>
    <Link prefetch={false} href={`/assessments/${assessmentId}/results`} className="mt-4 inline-block text-sm text-cyan-200 hover:underline">Inspect saved history without recording</Link>
  </section>;
}
