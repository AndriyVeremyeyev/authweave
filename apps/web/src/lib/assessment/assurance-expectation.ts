// Internal planning vocabulary, not a mapping to assurance standards or provider evidence.
export const assuranceExpectations = ["BASELINE", "ELEVATED", "HIGH", "UNKNOWN"] as const;
export type AssuranceExpectation = typeof assuranceExpectations[number];

export const assuranceExpectationLabels: Record<AssuranceExpectation, string> = {
  BASELINE: "Baseline", ELEVATED: "Elevated", HIGH: "High", UNKNOWN: "Unknown / not recorded",
};

export const assuranceExpectationGuidance: Record<AssuranceExpectation, string> = {
  BASELINE: "A starting planning expectation. Your team still needs to define the controls and evidence it requires; this does not mean a provider is safe enough.",
  ELEVATED: "A stronger planning expectation than Baseline. Record the specific controls separately; this label does not enable MFA or phishing resistance.",
  HIGH: "The strongest of these internal planning labels. Your security reviewer must define what it means for this application; the label alone is not a verified assurance level.",
  UNKNOWN: "No expectation has been recorded. Keep this choice when the scope is unresolved; it is not an exemption from security requirements.",
};
