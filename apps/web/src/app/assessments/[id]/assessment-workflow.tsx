"use client";

import { createContext, useContext, useEffect, useReducer, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { assessmentSteps, workflowTransition, type AssessmentStep } from "@/lib/assessment/workflow";

const AssessmentNavigation = createContext<((step: AssessmentStep, button: HTMLButtonElement) => void) | null>(null);
const AssessmentSave = createContext<{ setSaving: (busy: boolean) => void; isSaving: () => boolean; allowReload: () => void } | null>(null);
export function useAssessmentSave() { return useContext(AssessmentSave); }

const subscribe = () => () => {};
const clientSnapshot = () => true;
const serverSnapshot = () => false;

// Server-rendered cards use the same in-memory navigation and dirty guard as the sidebar.
export function AssessmentStepButton({ step, children }: { step: AssessmentStep; children: ReactNode }) {
  const navigate = useContext(AssessmentNavigation);
  return <button type="button" disabled={!navigate} aria-controls="assessment-step-panel"
    onClick={event => navigate?.(step, event.currentTarget)}
    className="rounded-lg border border-cyan-300/30 px-3 py-2 text-sm font-medium text-cyan-200 hover:bg-cyan-300/10 disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">
    {children}
  </button>;
}

export function AssessmentWorkflow({ initialStep, panels, editable }: {
  initialStep: AssessmentStep; panels: Record<AssessmentStep, ReactNode>; editable: boolean;
}) {
  const [state, dispatch] = useReducer(workflowTransition, { step: initialStep, dirty: false, pending: null });
  const router = useRouter();
  const dirty = useRef(false);
  const saving = useRef(false);
  // JavaScript-only controls stay disabled in server HTML and during hydration.
  const ready = useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot);
  const [savePending, setSavePending] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const sourceButton = useRef<HTMLButtonElement | null>(null);
  const previousStep = useRef(initialStep);
  const index = assessmentSteps.findIndex(step => step.id === state.step);
  const step = assessmentSteps[index];

  useEffect(() => {
    const protectEdits = (event: BeforeUnloadEvent) => {
      if (!dirty.current && !saving.current) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", protectEdits);
    return () => window.removeEventListener("beforeunload", protectEdits);
  }, []);

  useEffect(() => {
    if (state.pending !== null) dialog.current?.showModal();
    else dialog.current?.close();
  }, [state.pending]);

  useEffect(() => {
    if (previousStep.current === state.step) return;
    previousStep.current = state.step;
    const url = new URL(window.location.href);
    url.searchParams.set("step", state.step);
    for (const key of ["contextError", "editError", "auditError", "usageError", "operationsError"]) url.searchParams.delete(key);
    window.history.replaceState(null, "", url);
    heading.current?.focus();
  }, [state.step]);

  function navigate(target: AssessmentStep, button: HTMLButtonElement) {
    if (!ready || saving.current) return;
    sourceButton.current = button;
    dispatch({ type: "navigate", step: target });
  }
  function cancel() {
    dialog.current?.close();
    dispatch({ type: "cancel" });
    requestAnimationFrame(() => sourceButton.current?.focus());
  }
  function discard() {
    // Closed dialogs and pending saves cannot authorize discarding inputs.
    if (!state.pending || !dialog.current?.open || saving.current) return;
    const destination = state.pending;
    dialog.current.close();
    dirty.current = false;
    dispatch({ type: "discard" });
    if (destination === "assessments") router.push("/assessments");
  }

  return (
    <div className="mt-8 grid items-start gap-6 lg:grid-cols-[250px_minmax(0,1fr)]">
      <aside className="rounded-2xl border border-white/10 bg-white/[0.03] p-4 lg:sticky lg:top-6">
        <button type="button" disabled={!ready || savePending} onClick={event => {
          if (!ready || saving.current) return;
          sourceButton.current = event.currentTarget;
          if (dirty.current) dispatch({ type: "leave" });
          else router.push("/assessments");
        }}
          className="mb-5 text-sm text-cyan-200 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">
          ← Your assessments
        </button>
        <p className="px-2 text-xs font-semibold uppercase tracking-[0.15em] text-slate-400">Decision workspace</p>
        <nav aria-label="Assessment steps" className="mt-3">
          <ol className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-1">
            {assessmentSteps.map((item, position) => (
              <li key={item.id}>
                <button type="button" disabled={!ready || savePending} aria-current={state.step === item.id ? "step" : undefined}
                  aria-controls="assessment-step-panel" onClick={event => navigate(item.id, event.currentTarget)}
                  className={`flex w-full items-center gap-3 rounded-xl border px-3 py-3 text-left text-sm transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200 ${state.step === item.id
                    ? "border-cyan-300/30 bg-cyan-300/10 text-cyan-100"
                    : "border-transparent text-slate-300 hover:border-white/10 hover:bg-white/5"}`}>
                  <span aria-hidden="true" className={`grid size-7 shrink-0 place-items-center rounded-lg text-xs ${state.step === item.id ? "bg-cyan-300 text-slate-950" : "bg-white/5 text-slate-400"}`}>{position + 1}</span>
                  {item.short}
                </button>
              </li>
            ))}
          </ol>
        </nav>
        <p className="mt-5 border-t border-white/10 px-2 pt-4 text-xs leading-5 text-slate-400">
          {editable ? "Save each edited section before moving on. There is no autosave." : "This assessment is read-only. Explore the saved inputs and previews."}
          {" "}Step numbers show your location, not completion.
        </p>
      </aside>

      <section id="assessment-step-panel" aria-labelledby="assessment-step-heading"
        className="min-w-0 rounded-2xl border border-white/10 bg-white/[0.025] p-5 sm:p-8">
        <div className="border-b border-white/10 pb-6">
          <p className="text-xs font-semibold uppercase tracking-[0.15em] text-cyan-200">Step {index + 1} of {assessmentSteps.length}</p>
          <h2 id="assessment-step-heading" ref={heading} tabIndex={-1}
            className="mt-3 rounded text-3xl font-semibold tracking-tight focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">{step.title}</h2>
          <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-300">{step.description}</p>
          <p role="status" className={`mt-4 text-xs ${state.dirty ? "text-amber-200" : "text-slate-400"}`}>
            {savePending ? "Saving this section. Wait for the response before moving on." : state.dirty ? "Unsaved changes in this section. Follow its save feedback before moving on." : !editable && step.input
              ? "Showing a read-only saved assessment. No editing is available."
              : step.input
              ? "Showing saved inputs. Changes are saved only when you submit this section."
              : state.step === "review"
              ? "Only saved answers are shown here. This is not a validation result or a completeness score."
              : "These previews use saved requirements. Temporary what-if inputs reset when you switch steps."}
          </p>
        </div>
        <noscript><p className="mt-5 rounded-xl border border-amber-700 p-4 text-sm text-amber-100">Step navigation needs JavaScript.{step.input && " You can still submit the form in the current section."}</p></noscript>
        <div key={state.step} onChangeCapture={() => {
          if (!editable || !step.input) return;
          dirty.current = true;
          dispatch({ type: "edit" });
        }} onSubmit={event => {
          if (!step.input || event.defaultPrevented) return;
          dirty.current = false;
          dispatch({ type: "submit" });
        }}>
          <AssessmentSave.Provider value={{
            setSaving: busy => { saving.current = busy; setSavePending(busy); },
            isSaving: () => saving.current,
            // Release only for a checked acknowledgement or explicit discard-and-reload.
            allowReload: () => { dirty.current = false; saving.current = false; setSavePending(false); dispatch({ type: "submit" }); },
          }}><AssessmentNavigation.Provider value={ready ? navigate : null}>{panels[state.step]}</AssessmentNavigation.Provider></AssessmentSave.Provider>
        </div>
        <div className="mt-8 flex flex-wrap items-center justify-between gap-4 border-t border-white/10 pt-6">
          <button type="button" disabled={!ready || index === 0 || savePending}
            onClick={event => navigate(assessmentSteps[index - 1].id, event.currentTarget)}
            className="rounded-lg border border-slate-600 px-4 py-2 text-sm font-medium text-slate-200 hover:border-cyan-200 disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">← Previous</button>
          <button type="button" disabled={!ready || savePending} onClick={event => navigate(index < assessmentSteps.length - 1 ? assessmentSteps[index + 1].id : "comparison", event.currentTarget)}
            className="rounded-lg bg-white px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-100 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">
            {index < assessmentSteps.length - 1 ? `Next: ${assessmentSteps[index + 1].short} →` : "Return to comparison →"}
          </button>
        </div>
      </section>

      <dialog ref={dialog} aria-labelledby="discard-edits-heading" aria-describedby="discard-edits-description"
        onCancel={event => { event.preventDefault(); cancel(); }}
        className="m-auto w-[min(32rem,calc(100%_-_2rem))] rounded-2xl border border-slate-600 bg-slate-900 p-6 text-slate-100 shadow-2xl backdrop:bg-black/70">
        <h2 id="discard-edits-heading" className="text-xl font-semibold">Keep your unsaved changes?</h2>
        <p id="discard-edits-description" className="mt-3 text-sm leading-6 text-slate-300">{state.pending === "assessments"
          ? "Leaving this assessment will discard the edits in this section. Stay here to review them and any save feedback, or return to your assessment list without saving these changes."
          : "Moving to another step will discard the edits in this section. Stay here to review them and any save feedback, or continue with the previously saved version."}</p>
        <div className="mt-6 flex flex-wrap gap-3">
          <button type="button" onClick={cancel} className="rounded-lg bg-cyan-300 px-4 py-2 text-sm font-semibold text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">Stay and review</button>
          <button type="button" onClick={discard}
            className="rounded-lg border border-slate-500 px-4 py-2 text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200">{state.pending === "assessments" ? "Discard and leave" : "Discard and continue"}</button>
        </div>
      </dialog>
    </div>
  );
}
