import { resultAdviceFromCore, type ResultAdvice } from "./decision-advice.ts";

export const publicationReviewContext = "Core verified the recorded catalog review and publication workflow for this exact saved result. That historical review is complete; opening the result does not repeat it or refresh evidence dates. This is not independent verification of vendor claims, a tested deployment, compliance certification or decision approval.";
const kernelSourceFollowUp = "Obtain authenticated source review for each exact option claim; calculation assertions are not curator approvals.";
const reviewedSourceFollowUp = "Inspect the recorded sources and conditions for the chosen option. Resolve UNKNOWN, stale or disputed claims through a new explicit review before a new calculation; the existing publication review does not need to be repeated just to read this result.";

/** Display context only after verified transport. Never changes a hash-bound result or kernel policy. */
export function reviewedAdviceText(input: ResultAdvice) {
  const s = input.summary;
  const advice = resultAdviceFromCore(input, s.workspaceId, s.assessmentId, s.item.reference);
  return {
    context: publicationReviewContext,
    limitations: advice.limitations.map(l => l.reasonCode === "SOURCE_AUTHORITY_UNVERIFIED" && l.profilePath === null
      ? { ...l, explanation: "The catalog's recorded review and publication workflow was verified. External source truth and the actual provider configuration remain unverified; recorded claims support conditional advice, not a deployment guarantee." } : l),
    followUps: advice.followUps.map(text => text === kernelSourceFollowUp ? reviewedSourceFollowUp : text),
  };
}
