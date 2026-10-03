export const assessmentSteps = [
  { id: "context", title: "Application context", short: "Context", input: true,
    description: "Who uses the application, how it is hosted and which security boundaries matter." },
  { id: "capabilities", title: "Identity requirements", short: "Requirements", input: true,
    description: "Separate hard requirements from preferences. Unknown is a valid answer, not a default choice." },
  { id: "auditability", title: "Audit and evidence", short: "Audit", input: true,
    description: "Record the events and retention your application needs, without assuming a provider meets them." },
  { id: "usage", title: "Usage and budget inputs", short: "Usage", input: true,
    description: "Capture expected usage and its assumptions. Missing information is not treated as zero cost." },
  { id: "comparison", title: "Compare options", short: "Comparison", input: false,
    description: "Explore the fictional options against your saved requirements. No real provider or winner is established." },
  { id: "architecture", title: "Explore architectures", short: "Architecture", input: false,
    description: "Review patterns, trade-offs and temporary what-if conditions. These are not saved architecture decisions." },
] as const;

export type AssessmentStep = typeof assessmentSteps[number]["id"];
export type WorkflowState = { step: AssessmentStep; dirty: boolean; pending: AssessmentStep | null };
export type WorkflowEvent = { type: "edit" | "cancel" | "discard" | "submit" }
  | { type: "navigate"; step: AssessmentStep };

export function assessmentStepFromQuery(query: Record<string, string | string[] | undefined>): AssessmentStep {
  for (const [key, step] of [["contextError", "context"], ["editError", "capabilities"],
    ["auditError", "auditability"], ["usageError", "usage"]] as const) {
    if (typeof query[key] === "string" && ["stale", "invalid", "locked"].includes(query[key])) return step;
  }
  return assessmentSteps.find(step => step.id === query.step)?.id ?? "context";
}

// Navigation state is not assessment completeness, a write receipt or evaluation authority.
export function workflowTransition(state: WorkflowState, event: WorkflowEvent): WorkflowState {
  switch (event.type) {
    case "edit": return assessmentSteps.find(step => step.id === state.step)?.input ? { ...state, dirty: true } : state;
    case "navigate":
      if (event.step === state.step) return state;
      return state.dirty ? { ...state, pending: event.step } : { step: event.step, dirty: false, pending: null };
    case "cancel": return { ...state, pending: null };
    case "discard": return state.pending ? { step: state.pending, dirty: false, pending: null } : state;
    case "submit": return { ...state, dirty: false, pending: null };
  }
}
