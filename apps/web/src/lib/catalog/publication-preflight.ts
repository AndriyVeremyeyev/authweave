const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[a-f0-9]{64}$/;
export const publicationReviewByteLimit = 16_384;
export const publicationBlockers = [
  "PROPOSAL_NOT_FOUND",
  "PROPOSAL_READ_BUDGET_EXCEEDED",
  "PROPOSAL_FORMAT_INVALID",
  "PROPOSAL_DIGEST_MISMATCH",
  "PROPOSAL_NOT_CURRENT",
  "PROPOSAL_REJECTED",
  "PROPOSAL_ALREADY_PUBLISHED",
  "CHANGE_NOT_REVIEWABLE",
  "CANDIDATE_INVALID",
  "CATALOG_LABEL_ALREADY_USED",
  "FACT_REVIEW_LEDGER_INVALID",
  "FACT_OBSERVATIONS_MISSING",
  "SOURCE_CONTRADICTION",
  "INSUFFICIENT_SOURCE_EVIDENCE",
  "FACT_EVIDENCE_STALE",
  "FACT_EVIDENCE_FUTURE",
  "BASELINE_REFERENCE_MISSING",
  "BASELINE_INTEGRITY_UNAVAILABLE",
  "BASELINE_CONTENT_MISMATCH",
  "BASELINE_NOT_TIP",
  "BASELINE_AUTHORITY_UNAVAILABLE",
  "BOOTSTRAP_REGISTRY_NOT_EMPTY",
  "BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE",
  "BOOTSTRAP_REVIEW_UNAVAILABLE",
  "BOOTSTRAP_IMPACT_WORKFLOW_UNAVAILABLE",
  "BOOTSTRAP_IMPACT_ANALYSIS_BLOCKED",
  "BOOTSTRAP_IMPACT_ANALYSIS_INCOMPLETE",
  "BOOTSTRAP_IMPACT_RECEIPT_MISSING",
  "BOOTSTRAP_IMPACT_REPORT_READ_BUDGET_EXCEEDED",
  "BOOTSTRAP_IMPACT_RECEIPT_INVALID",
  "BOOTSTRAP_IMPACT_RULES_INCOMPATIBLE",
  "BOOTSTRAP_IMPACT_REPLAY_MISMATCH",
  "STORED_BOOTSTRAP_IMPACT_ANALYSIS_BLOCKED",
  "STORED_BOOTSTRAP_IMPACT_ANALYSIS_INCOMPLETE",
  "IMPACT_RECEIPT_MISSING",
  "IMPACT_REPORT_READ_BUDGET_EXCEEDED",
  "IMPACT_RECEIPT_INVALID",
  "IMPACT_RULES_INCOMPATIBLE",
  "IMPACT_REPLAY_MISMATCH",
  "IMPACT_ANALYSIS_BLOCKED",
  "FACT_PATH_REGRESSION_BLOCKED",
  "FACT_PATH_REGRESSION_INCOMPLETE",
  "IMPACT_COVERAGE_INCOMPLETE",
  "FACT_PATH_RECEIPT_MISSING",
  "FACT_PATH_REPORT_READ_BUDGET_EXCEEDED",
  "FACT_PATH_RECEIPT_INVALID",
  "FACT_PATH_RULES_INCOMPATIBLE",
  "FACT_PATH_REPLAY_MISMATCH",
  "STORED_FACT_PATH_ANALYSIS_BLOCKED",
  "STORED_FACT_PATH_ANALYSIS_INCOMPLETE",
  "SCOPED_PROFILE_REGRESSION_BLOCKED",
  "SCOPED_PROFILE_REGRESSION_INCOMPLETE",
  "CURATOR_AUTHORIZATION_NOT_PERFORMED",
  "PUBLICATION_WORKFLOW_UNAVAILABLE"
] as const;
export type PublicationBlocker = (typeof publicationBlockers)[number];
export type PublicationReference = { mode: "PROPOSAL_APPROVAL" | "CURATED_BOOTSTRAP"; inputId: string; inputVersion: number | null; inputSha256: string };
export const planningFamilies = [
  { family: "ARCHITECTURE_CONFIGURATION", checkedCases: 252 },
  { family: "PROVISIONING_LIFECYCLE", checkedCases: 2016 },
  { family: "OPERATIONS_PLANNING", checkedCases: 140 },
  { family: "ASSURANCE_COMPLIANCE", checkedCases: 36 },
] as const;
export type PublicationReview = PublicationReference & {
  evaluatedAt: string; reviewThroughNumber: number;
  facts: { total: number; unobserved: number; supporting: number; contradicting: number; insufficient: number; stale: number; future: number; allFactsHaveSupportingObservation: boolean };
  planning: { evaluatedAt: string; analysisSha256: string; checkedDimensions: number; structuralVerificationGaps: number; planningVerificationGaps: number; regressions: { family: string; checkedCases: number }[]; status: "INCOMPLETE"; policyVersion: string } | null;
  blockers: PublicationBlocker[];
};
const flags = ["coverageComplete", "baselineVerified", "sourceVerificationPerformed", "approvalGranted", "publicationReady", "evaluationReady", "writesPerformed"];
function invalid(): never { throw new Error("Invalid publication preflight review"); }
function exact(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.getPrototypeOf(value) !== Object.prototype) return invalid();
  const row = value as Record<string, unknown>;
  if (Object.keys(row).length !== keys.length || keys.some(key => !Object.hasOwn(row, key))) return invalid();
  return row;
}
export function publicationReference(value: PublicationReference): PublicationReference {
  exact(value, ["mode", "inputId", "inputVersion", "inputSha256"]);
  if (!["PROPOSAL_APPROVAL", "CURATED_BOOTSTRAP"].includes(value.mode) || typeof value.inputId !== "string" || !UUID.test(value.inputId)
      || typeof value.inputSha256 !== "string" || !SHA256.test(value.inputSha256)
      || (value.mode === "CURATED_BOOTSTRAP" ? value.inputVersion !== null : !Number.isSafeInteger(value.inputVersion) || Number(value.inputVersion) < 0)) return invalid();
  return { ...value };
}
function instant(value: unknown): string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return invalid();
  const millis = Date.parse(value);
  if (!Number.isFinite(millis) || new Date(millis).toISOString().slice(0, 19) !== value.slice(0, 19)) return invalid();
  return value;
}
/** Validate only this bounded Core display contract; do not infer full coverage from its counts or digest. */
export function publicationReviewFromCore(value: unknown, reference: PublicationReference, now: Date): PublicationReview {
  const binding = publicationReference(reference);
  const raw = exact(value, ["schemaVersion", "scope", "policyVersion", "status", "mode", "inputId", "inputVersion", "inputSha256",
    "evaluatedAt", "facts", "reviewThroughNumber", "planning", "blockers", ...flags]);
  if (raw.schemaVersion !== 1 || raw.scope !== "CATALOG_PUBLICATION_PREFLIGHT_REVIEW" || raw.policyVersion !== "catalog-publication-preflight-12"
      || raw.status !== "BLOCKED" || flags.some(flag => raw[flag] !== false)
      || Object.entries(binding).some(([key, expected]) => raw[key] !== expected)) return invalid();
  const evaluatedAt = instant(raw.evaluatedAt);
  if (!Number.isFinite(now.getTime()) || Math.abs(Date.parse(evaluatedAt) - now.getTime()) > 30_000) return invalid();
  if (!Number.isSafeInteger(raw.reviewThroughNumber) || Number(raw.reviewThroughNumber) < 0) return invalid();
  const facts = exact(raw.facts, ["total", "unobserved", "supporting", "contradicting", "insufficient", "stale", "future", "allFactsHaveSupportingObservation"]);
  for (const key of ["total", "unobserved", "supporting", "contradicting", "insufficient", "stale", "future"])
    if (!Number.isSafeInteger(facts[key]) || Number(facts[key]) < 0 || Number(facts[key]) > 6800) return invalid();
  if (Number(facts.unobserved) + Number(facts.supporting) + Number(facts.contradicting) + Number(facts.insufficient) !== facts.total
      || Number(facts.stale) + Number(facts.future) > Number(facts.total)
      || facts.allFactsHaveSupportingObservation !== (Number(facts.total) > 0 && facts.supporting === facts.total)) return invalid();
  if (!Array.isArray(raw.blockers) || raw.blockers.length === 0 || raw.blockers.length > publicationBlockers.length
      || new Set(raw.blockers).size !== raw.blockers.length || raw.blockers.some(b => !publicationBlockers.includes(b))
      || JSON.stringify(raw.blockers) !== JSON.stringify(publicationBlockers.filter(b => raw.blockers instanceof Array && raw.blockers.includes(b)))
      || ["IMPACT_COVERAGE_INCOMPLETE", "CURATOR_AUTHORIZATION_NOT_PERFORMED", "PUBLICATION_WORKFLOW_UNAVAILABLE"].some(b => !(raw.blockers as string[]).includes(b))) return invalid();
  const notChecked = ["PROPOSAL_NOT_FOUND", "PROPOSAL_READ_BUDGET_EXCEEDED", "PROPOSAL_FORMAT_INVALID", "PROPOSAL_DIGEST_MISMATCH", "BOOTSTRAP_REVIEW_UNAVAILABLE"]
    .some(b => (raw.blockers as string[]).includes(b));
  if (notChecked && (facts.total !== 0 || raw.reviewThroughNumber !== 0)
      || raw.blockers.some(b => binding.mode === "PROPOSAL_APPROVAL" ? b.includes("BOOTSTRAP") : b.startsWith("PROPOSAL_") || b.startsWith("BASELINE_"))) return invalid();
  for (const [count, code] of [["unobserved", "FACT_OBSERVATIONS_MISSING"], ["contradicting", "SOURCE_CONTRADICTION"],
    ["insufficient", "INSUFFICIENT_SOURCE_EVIDENCE"], ["stale", "FACT_EVIDENCE_STALE"], ["future", "FACT_EVIDENCE_FUTURE"]])
    if ((Number(facts[count]) > 0) !== raw.blockers.includes(code)) return invalid();
  let planning: PublicationReview["planning"] = null;
  if ((raw.planning === null) !== notChecked) return invalid();
  if (raw.planning !== null) {
    const p = exact(raw.planning, ["evaluatedAt", "analysisSha256", "checkedDimensions", "structuralVerificationGaps", "planningVerificationGaps", "regressions", "status", "policyVersion"]);
    if (p.evaluatedAt !== evaluatedAt || p.status !== "INCOMPLETE" || p.policyVersion !== "catalog-profile-planning-coverage-1"
        || typeof p.analysisSha256 !== "string" || !SHA256.test(p.analysisSha256) || p.checkedDimensions !== 136
        || p.structuralVerificationGaps !== 40 || p.planningVerificationGaps !== 22 || !Array.isArray(p.regressions) || p.regressions.length !== 4) return invalid();
    const regressions = p.regressions.map((r, i) => {
      const row = exact(r, ["family", "checkedCases"]), expected = planningFamilies[i];
      if (row.family !== expected.family || row.checkedCases !== expected.checkedCases) return invalid();
      return { ...expected };
    });
    planning = { evaluatedAt, analysisSha256: p.analysisSha256, checkedDimensions: 136, structuralVerificationGaps: 40,
      planningVerificationGaps: 22, regressions, status: "INCOMPLETE", policyVersion: "catalog-profile-planning-coverage-1" };
  }
  return { ...binding, evaluatedAt, reviewThroughNumber: Number(raw.reviewThroughNumber), facts: { ...facts } as PublicationReview["facts"], planning,
    blockers: [...raw.blockers] as PublicationBlocker[] };
}
export function publicationBlockerTitle(code: PublicationBlocker): string {
  const titles: Partial<Record<PublicationBlocker, string>> = {
    IMPACT_COVERAGE_INCOMPLETE: "Full impact coverage is incomplete",
    CURATOR_AUTHORIZATION_NOT_PERFORMED: "Publication-write authorization has not been performed",
    PUBLICATION_WORKFLOW_UNAVAILABLE: "This legacy preflight cannot invoke the publication workflow",
    BASELINE_AUTHORITY_UNAVAILABLE: "An authoritative baseline is not available",
    BASELINE_REFERENCE_MISSING: "No trusted baseline reference was supplied by Core",
    FACT_OBSERVATIONS_MISSING: "Some candidate facts have no manual source observation",
    SOURCE_CONTRADICTION: "A manual observation contradicts a candidate claim",
    INSUFFICIENT_SOURCE_EVIDENCE: "Some manual observations report insufficient evidence",
    FACT_EVIDENCE_STALE: "Some candidate evidence is older than the date policy permits",
    FACT_EVIDENCE_FUTURE: "Some candidate evidence is dated in the future",
  };
  return titles[code] ?? code.charAt(0) + code.slice(1).toLowerCase().replaceAll("_", " ");
}
