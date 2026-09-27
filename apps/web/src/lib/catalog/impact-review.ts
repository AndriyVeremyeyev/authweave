import type { CatalogProposalReview } from "./proposal-review.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const STATUSES = ["WOULD_SATISFY_CHECKED_REQUIREMENTS", "WOULD_VIOLATE_CHECKED_REQUIREMENTS",
  "INDETERMINATE", "OPTION_ABSENT"] as const;

type ConditionalStatus = typeof STATUSES[number];
export type ScenarioImpactRow = {
  scenarioId: string;
  optionId: string;
  scopeChanged: boolean;
  conditionalStatusChanged: boolean;
  before: ConditionalStatus;
  after: ConditionalStatus;
  affectedFactPaths: string[];
  changedCheckIds: string[];
};
export type CatalogImpactReview = {
  reportId: string;
  reportNumber: number;
  reportSha256: string;
  recordedAt: string;
  evaluatedAt: string;
  status: "ANALYZED" | "BLOCKED";
  policyVersion: string;
  ruleVersion: string;
  caseSetVersion: string;
  caseSetSha256: string;
  scenarioDefinitions: { id: string; description: string }[];
  scenarios: ScenarioImpactRow[];
  uncoveredChanges: { optionId: string; factPath: string }[];
};

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}
function string(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}
function instant(value: unknown): value is string {
  return string(value) && Number.isFinite(Date.parse(value));
}
function strings(value: unknown, max: number): string[] {
  if (!Array.isArray(value) || value.length > max || !value.every(string)) {
    throw new Error("Stored impact report is invalid");
  }
  return value as string[];
}
function status(value: unknown): value is ConditionalStatus {
  return STATUSES.includes(value as ConditionalStatus);
}

/** A historical conditional run, never evidence verification or approval. */
export function impactReviewFromCore(value: unknown, proposal: CatalogProposalReview): CatalogImpactReview {
  const snapshot = object(value);
  const report = object(snapshot?.report);
  const preview = object(report?.changePreview);
  if (!snapshot || !report || !preview || !UUID.test(String(snapshot.reportId)) ||
      snapshot.proposalId !== proposal.proposalId || snapshot.proposalVersion !== proposal.version ||
      snapshot.proposalSha256 !== proposal.proposalSha256 ||
      !Number.isSafeInteger(snapshot.reportNumber) || Number(snapshot.reportNumber) < 1 ||
      snapshot.reportSchemaVersion !== 1 ||
      snapshot.canonicalizationVersion !== "catalog-draft-canonical-json-1" ||
      typeof snapshot.reportSha256 !== "string" || !SHA256.test(snapshot.reportSha256) ||
      !instant(snapshot.recordedAt) || !instant(report.evaluatedAt) ||
      report.scope !== "CATALOG_PROFILE_SCENARIO_IMPACT" ||
      report.analysisBasis !== "ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS" ||
      report.proposalId !== proposal.proposalId || report.proposalSha256 !== proposal.proposalSha256 ||
      report.storedProposalVersion !== proposal.version || report.storedRequestDigestVerified !== true ||
      !string(report.policyVersion) || !string(report.ruleVersion) ||
      !string(report.caseSetVersion) || typeof report.caseSetSha256 !== "string" ||
      !SHA256.test(report.caseSetSha256) ||
      report.coverageComplete !== false || report.baselineVerified !== false ||
      report.sourceVerificationPerformed !== false || report.approvalGranted !== false ||
      report.writesPerformed !== false || report.evaluationReady !== false ||
      report.recommendationReady !== false ||
      preview.proposalId !== proposal.proposalId || preview.proposalSha256 !== proposal.proposalSha256 ||
      preview.proposalState !== "PROPOSED" || preview.approvalGranted !== false ||
      preview.baselineVerified !== false || preview.sourceVerificationPerformed !== false ||
      preview.writesPerformed !== false || preview.evaluationReady !== false ||
      preview.impactAnalysisPerformed !== false ||
      !Array.isArray(report.scenarioDefinitions) || report.scenarioDefinitions.length !== 3 ||
      !Array.isArray(report.scenarios) || report.scenarios.length > 600 ||
      !Array.isArray(report.uncoveredChanges) || report.uncoveredChanges.length > 13600 ||
      !["ANALYZED", "BLOCKED"].includes(String(report.status)) ||
      (report.status === "ANALYZED" &&
        (report.impactAnalysisPerformed !== true || report.hypotheticalEvaluationPerformed !== true)) ||
      (report.status === "BLOCKED" &&
        (report.impactAnalysisPerformed !== false || report.hypotheticalEvaluationPerformed !== false ||
         report.scenarios.length !== 0 || report.uncoveredChanges.length !== 0))) {
    throw new Error("Stored impact report is invalid");
  }
  const definitions = report.scenarioDefinitions.map((value: unknown) => {
    const row = object(value);
    if (!row || !string(row.id) || !string(row.description)) throw new Error("Stored impact report is invalid");
    return { id: row.id, description: row.description };
  });
  if (new Set(definitions.map(row => row.id)).size !== 3) throw new Error("Stored impact report is invalid");
  const scenarios = report.scenarios.map((value: unknown) => {
    const row = object(value);
    const before = object(row?.before);
    const after = object(row?.after);
    if (!row || !string(row.scenarioId) || !definitions.some(def => def.id === row.scenarioId) ||
        !string(row.optionId) || typeof row.scopeChanged !== "boolean" ||
        typeof row.conditionalStatusChanged !== "boolean" || !before || !after ||
        !status(before.conditionalStatus) || !status(after.conditionalStatus) ||
        row.conditionalStatusChanged !== (before.conditionalStatus !== after.conditionalStatus)) {
      throw new Error("Stored impact report is invalid");
    }
    return { scenarioId: row.scenarioId, optionId: row.optionId, scopeChanged: row.scopeChanged,
      conditionalStatusChanged: row.conditionalStatusChanged,
      before: before.conditionalStatus, after: after.conditionalStatus,
      affectedFactPaths: strings(row.affectedFactPaths, 68),
      changedCheckIds: strings(row.changedCheckIds, 100) };
  });
  const uncoveredChanges = report.uncoveredChanges.map((value: unknown) => {
    const row = object(value);
    if (!row || !string(row.optionId) || !string(row.factPath) || row.reason !== "NO_SCENARIO_DEPENDENCY") {
      throw new Error("Stored impact report is invalid");
    }
    return { optionId: row.optionId, factPath: row.factPath };
  });
  return { reportId: snapshot.reportId as string, reportNumber: snapshot.reportNumber as number,
    reportSha256: snapshot.reportSha256, recordedAt: snapshot.recordedAt, evaluatedAt: report.evaluatedAt,
    status: report.status as CatalogImpactReview["status"], policyVersion: report.policyVersion,
    ruleVersion: report.ruleVersion, caseSetVersion: report.caseSetVersion,
    caseSetSha256: report.caseSetSha256, scenarioDefinitions: definitions, scenarios, uncoveredChanges };
}
