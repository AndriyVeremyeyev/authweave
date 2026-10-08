// This local-only server-to-server call never exposes its credential to the browser.
import { randomUUID } from "node:crypto";
import type { BrowserSession } from "./store.ts";
import type { AuthConfiguration } from "./config.ts";
import { freshCuratorGrant } from "./curator.ts";
import { bootstrapCandidate, bootstrapPreparationFromCore, bootstrapReviewInput, bootstrapReceiptFromCore,
  bootstrapReceiptReference, boundedBootstrapJson, type BootstrapPreparation, type BootstrapReceipt,
  type BootstrapReviewInput } from "../catalog/bootstrap-review.ts";
import { factReviewInput, factReviewFromCore, type FactReviewInput, type FactReviewReceipt } from "../catalog/fact-review.ts";
import { factReviewHistoryFromCore, type FactReviewHistoryCursor, type FactReviewHistoryPage } from "../catalog/fact-review-history.ts";
import { factReviewSummaryFromCore, type FactReviewSummaryPage } from "../catalog/fact-review-summary.ts";
import { catalogReviewPrerequisites, type CatalogReviewPrerequisites } from "../catalog/review-prerequisites.ts";
import { impactReviewFromCore, type CatalogImpactReview } from "../catalog/impact-review.ts";
import { evidencePageFromCore, type CandidateEvidencePage } from "../catalog/evidence-review.ts";
import { parseProposalReviewCursor, proposalIndexFromCore,
  type ProposalReviewIndexPage } from "../catalog/proposal-index.ts";
import { proposalReviewFromCore, type CatalogProposalReview } from "../catalog/proposal-review.ts";
import { publicationReference, publicationReviewByteLimit, publicationReviewFromCore,
  type PublicationReference, type PublicationReview } from "../catalog/publication-preflight.ts";
import { withCapabilityValues, type CapabilityValues } from "../assessment/capabilities.ts";
import { auditabilityValues, withAuditabilityValues, type AuditabilityValues } from "../assessment/auditability.ts";
import { auditabilityPreviewBinding, auditabilityPreviewByteLimit, auditabilityPreviewFromCore,
  type AuditabilityPreview } from "../assessment/auditability-preview.ts";
import { comparisonFromCore, type SyntheticComparisonSummary } from "../assessment/comparison.ts";
import { comparisonEvidenceByteLimit, comparisonEvidenceFromCore, type ComparisonEvidenceSummary } from "../assessment/comparison-provenance.ts";
import { applicationTypes, clientTypes, populations, evaluationContextValues, assuranceExpectationSaveMatches,
  withEvaluationContextValues, type EvaluationContextValues } from "../assessment/evaluation-context.ts";
import { boundedPrerequisiteText, parsePrerequisiteForm, prerequisiteAnalysis,
  type PrerequisiteInput, type PrerequisitePreview } from "../assessment/architecture-prerequisites.ts";
import { validateLifecycleInput, lifecycleByteLimit, lifecyclePreviewFromCore, provisioningRequirements,
  type LifecycleInput, type LifecyclePreview } from "../assessment/provisioning-lifecycle.ts";
import { validateLifecycleV2Input, lifecycleV2ByteLimit, lifecycleV2PreviewFromCore,
  type LifecycleV2Input, type LifecycleV2Preview } from "../assessment/provisioning-lifecycle-v2.ts";
import { architectureConfigurationByteLimit, architectureConfigurationFromCore, validateArchitectureConfigurationInput,
  type ArchitectureConfigurationInput, type ArchitectureConfigurationPreview } from "../assessment/architecture-configuration.ts";
import { usageMetrics, usagePlanningValues, withUsagePlanningValues,
  type UsageMetric, type UsagePlanningValues } from "../assessment/usage-planning.ts";
import { operationsPlanningBinding, operationsPlanningByteLimit, operationsPlanningFromCore,
  type OperationsPlanningValues, type OperationsPlanningPreview } from "../assessment/operations-planning.ts";
import { withOperationalPreferences, operationalPreferencesSaveMatches } from "../assessment/operational-preferences.ts";
import type { OperationsInputs } from "../assessment/operations-planning.ts";
import { assurancePlanningBinding, assurancePlanningByteLimit, assurancePlanningFromCore,
  type AssurancePlanningValues, type AssurancePlanningPreview } from "../assessment/assurance-compliance-planning.ts";
import { preferredCapabilities, weightsMatchPreferences, type CapabilityWeights,
  type SensitivityCapabilityDelta, type SensitivityCandidate, type SensitivityPreview,
  type WeightedCandidate, type WeightedContribution, type WeightedPreview } from "../assessment/weights.ts";

const CORE_ORIGIN = "http://127.0.0.1:8080";

export type CuratorProbeStatus = "not-configured" | "not-granted" | "reauth-required" |
  "core-rejected" | "core-unavailable" | "ready";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const rejectionReasons = ["INSUFFICIENT_EVIDENCE", "INACCURATE_FACTS", "OUT_OF_SCOPE", "OTHER"] as const;
export type CatalogRejectionReason = (typeof rejectionReasons)[number];
export type CatalogRejectionInput = {
  expectedVersion: number;
  expectedSha256: string;
  reasonCode: CatalogRejectionReason;
};
export type CatalogRejection = {
  decisionId: string;
  proposalId: string;
  proposalVersion: number;
  proposalSha256: string;
  decision: "REJECTED";
  reasonCode: CatalogRejectionReason;
  recordedAt: string;
};
export type CatalogRejectionResult =
  | { kind: "rejected"; decision: CatalogRejection }
  | { kind: Exclude<CuratorProbeStatus, "ready"> | "not-found" | "conflict" | "invalid" };
export type CatalogReviewResult =
  | { kind: "ready"; review: CatalogProposalReview; rejection: CatalogRejection | null;
      impact: CatalogImpactReview | null; evidence: CandidateEvidencePage; factReviews: FactReviewHistoryPage;
      factReviewSummary: FactReviewSummaryPage; prerequisites: CatalogReviewPrerequisites }
  | { kind: Exclude<CuratorProbeStatus, "ready"> | "not-found" | "invalid-review-cursor" | "stale-review-cursor" };
export type CatalogReviewIndexResult =
  | { kind: "ready"; page: ProposalReviewIndexPage }
  | { kind: Exclude<CuratorProbeStatus, "ready"> | "invalid-cursor" };

export type PersonalAssessment = {
  id: string;
  status: "DRAFT" | "READY_FOR_EVALUATION" | "EVALUATED" | "DECIDED" | "ARCHIVED";
  version: number;
  profile: Record<string, unknown>;
};

export type PersonalAssessmentListItem = Omit<PersonalAssessment, "profile"> & {
  createdAt: string;
  updatedAt: string;
  context: {
    applicationType: (typeof applicationTypes)[number];
    clients: (typeof clientTypes)[number][];
    userPopulations: (typeof populations)[number][];
  } | null;
};

export type PersonalAssessmentListPage = {
  items: PersonalAssessmentListItem[];
  nextBeforeId: string | null;
};

export type { ComparisonFinding, ComparisonPreference, ComparisonCandidate, SyntheticComparisonSummary } from "../assessment/comparison.ts";

export type ArchitecturePatternSummary = {
  patternId: "BFF_SESSION" | "SERVER_SIDE_SESSION" | "SPA_CODE_PKCE" |
    "NATIVE_CODE_PKCE" | "M2M_CLIENT_CREDENTIALS";
  displayName: string;
  clientType: "BROWSER" | "NATIVE_MOBILE" | "MACHINE_TO_MACHINE";
  tokenHandling: "SERVER_SIDE" | "BROWSER" | "NATIVE_APP" | "WORKLOAD";
  status: "MATCHES_CHECKED_REQUIREMENTS" | "NEEDS_INFORMATION" | "NOT_APPLICABLE";
  checks: { profilePath: string; outcome: "PASS" | "UNKNOWN" | "NOT_APPLIED";
    reasonCode: string; explanation: string }[];
  advantages: string[];
  tradeoffs: string[];
  prerequisites: string[];
  references: string[];
};

export type ArchitecturePatternPreflightSummary = {
  assessmentVersion: number;
  selectedClients: EvaluationContextValues["clients"];
  browserTokenExposureRequirement: EvaluationContextValues["browserTokenExposureMinimization"];
  checkedPaths: string[];
  deferredPaths: string[];
  patterns: ArchitecturePatternSummary[];
};

export type UsageMissingPath = "operations.usagePlanning.scopeDescription" |
  "operations.usagePlanning.assumptions" | `operations.usagePlanning.volumes.${UsageMetric}`;

export type UsagePlanningPreflightSummary = {
  assessmentVersion: number;
  evaluatedAt: string;
  status: "NEEDS_INFORMATION" | "INPUTS_RECORDED";
  missingPaths: UsageMissingPath[];
  quantityChecks: {
    metric: UsageMetric;
    unit: (typeof usageMetrics)[number]["unit"];
    status: "UNKNOWN" | "ASSUMED" | "OBSERVED";
    value: number | null;
  }[];
};

function serviceToken(): string {
  const token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  if (!token || token.length < 32) {
    throw new Error("Core service credential is not configured");
  }
  return token;
}

// Only the BFF may assert a login-time role from its server-side session to Core.
export async function readCuratorAuthorization(
  session: BrowserSession, config: Pick<AuthConfiguration, "issuer" | "curatorScope">,
  now: Date = new Date(),
): Promise<CuratorProbeStatus> {
  const scope = config.curatorScope;
  if (!scope) return "not-configured";
  if (session.issuer !== config.issuer.href.replace(/\/$/, "") || !session.curatorScope ||
      session.curatorScope.projectId !== scope.projectId ||
      session.curatorScope.organizationId !== scope.organizationId) return "not-granted";
  if (!freshCuratorGrant(session.curatorScope, scope, session.authenticatedAt, now)) {
    return "reauth-required";
  }
  if (!session.issuer || !session.subject) return "core-rejected";
  try {
    const response = await fetch(`${CORE_ORIGIN}/internal/v1/catalog-curator/authorization`, {
      method: "GET",
      headers: curatorHeaders(session, scope),
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    if (response.status === 204) return "ready";
    return response.status === 401 || response.status === 403 ? "core-rejected" : "core-unavailable";
  } catch {
    return "core-unavailable";
  }
}

function curatorHeaders(session: BrowserSession, scope: NonNullable<AuthConfiguration["curatorScope"]>):
    Record<string, string> {
  return {
    Authorization: `Bearer ${serviceToken()}`,
    "X-AuthWeave-Oidc-Issuer": session.issuer,
    "X-AuthWeave-Oidc-Subject": session.subject,
    "X-AuthWeave-Curator-Role": "catalog_curator",
    "X-AuthWeave-Curator-Project-Id": scope.projectId,
    "X-AuthWeave-Curator-Org-Id": scope.organizationId,
    "X-AuthWeave-Authenticated-At": session.authenticatedAt.toISOString(),
  };
}

type BootstrapFailure = Exclude<CuratorProbeStatus, "ready"> | "invalid" | "not-found" | "conflict";
export type BootstrapPreparationResult = { kind: "ready"; preparation: BootstrapPreparation } | { kind: BootstrapFailure };
export type BootstrapRecordResult = { kind: "recorded"; receipt: BootstrapReceipt; created: boolean } | { kind: BootstrapFailure };
export type BootstrapReadResult = { kind: "ready"; receipt: BootstrapReceipt } | { kind: BootstrapFailure };

export async function prepareCatalogBootstrapReview(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, value: unknown, now: Date = new Date()): Promise<BootstrapPreparationResult> {
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  const candidate = bootstrapCandidate(value);
  if (!candidate) return { kind: "invalid" };
  try {
    // Read-only validation owns the typed digest; it neither fetches sources nor stores reviews.
    const response = await fetch(`${CORE_ORIGIN}/api/v1/catalog-drafts/validate`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(candidate),
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    if (response.status === 400) return { kind: "invalid" };
    if (response.status !== 200) return { kind: "core-unavailable" };
    const body = await boundedBootstrapJson(response);
    if (body && typeof body === "object" && "status" in body && body.status === "INVALID_DRAFT") return { kind: "invalid" };
    return { kind: "ready", preparation: bootstrapPreparationFromCore(body, candidate, randomUUID()) };
  } catch { return { kind: "core-unavailable" }; }
}

export async function recordCatalogBootstrapReview(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, value: BootstrapReviewInput, now: Date = new Date()): Promise<BootstrapRecordResult> {
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  const input = bootstrapReviewInput(value);
  if (!input) return { kind: "invalid" };
  if (!config.curatorScope) return { kind: "not-configured" };
  try {
    const response = await fetch(`${CORE_ORIGIN}/api/v1/catalog-bootstrap-reviews`, {
      method: "POST", headers: { ...curatorHeaders(session, config.curatorScope), "Content-Type": "application/json" },
      body: JSON.stringify(input), cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    if (response.status === 400) return { kind: "invalid" };
    if (response.status === 409) return { kind: "conflict" };
    if (response.status === 401 || response.status === 403) return { kind: "core-rejected" };
    if (response.status !== 200 && response.status !== 201) return { kind: "core-unavailable" };
    return { kind: "recorded", created: response.status === 201,
      receipt: bootstrapReceiptFromCore(await boundedBootstrapJson(response), { id: input.reviewId }, input) };
  } catch { return { kind: "core-unavailable" }; }
}

export async function readCatalogBootstrapReview(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, id: unknown, digest: unknown, now: Date = new Date()): Promise<BootstrapReadResult> {
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  const reference = bootstrapReceiptReference(id, digest);
  if (!reference) return { kind: "invalid" };
  if (!config.curatorScope) return { kind: "not-configured" };
  try {
    const url = new URL(`${CORE_ORIGIN}/api/v1/catalog-bootstrap-reviews/${reference.id}`);
    url.searchParams.set("expectedSha256", reference.digest);
    const response = await fetch(url, { headers: curatorHeaders(session, config.curatorScope),
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000) });
    if (response.status === 404) return { kind: "not-found" };
    if (response.status === 409) return { kind: "conflict" };
    if (response.status === 400) return { kind: "invalid" };
    if (response.status === 401 || response.status === 403) return { kind: "core-rejected" };
    if (response.status !== 200) return { kind: "core-unavailable" };
    return { kind: "ready", receipt: bootstrapReceiptFromCore(await boundedBootstrapJson(response), reference) };
  } catch { return { kind: "core-unavailable" }; }
}

export type FactReviewResult =
  | { kind: "recorded"; review: FactReviewReceipt; created: boolean }
  | { kind: Exclude<CuratorProbeStatus, "ready"> | "not-found" | "conflict" | "invalid" };

export async function recordCatalogFactReview(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, id: string, input: FactReviewInput,
  now: Date = new Date()): Promise<FactReviewResult> {
  if (!UUID.test(id) || !factReviewInput(input)) return { kind: "invalid" };
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  if (!config.curatorScope) return { kind: "not-configured" };
  try {
    const response = await fetch(`${CORE_ORIGIN}/api/v1/catalog-change-proposals/${id}/fact-reviews`, {
      method: "POST", headers: { ...curatorHeaders(session, config.curatorScope), "Content-Type": "application/json" },
      body: JSON.stringify(input), cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    if (response.status === 404) return { kind: "not-found" };
    if (response.status === 409) return { kind: "conflict" };
    if (response.status === 400) return { kind: "invalid" };
    if (response.status === 401 || response.status === 403) return { kind: "core-rejected" };
    if (response.status !== 201 && response.status !== 200) return { kind: "core-unavailable" };
    return { kind: "recorded", created: response.status === 201,
      review: factReviewFromCore(await response.json(), id, input) };
  } catch { return { kind: "core-unavailable" }; }
}

function rejectionFromCore(value: unknown, id: string, input: CatalogRejectionInput): CatalogRejection {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Core rejection response is invalid");
  const body = value as Record<string, unknown>;
  if (typeof body.decisionId !== "string" || !UUID.test(body.decisionId) || body.proposalId !== id ||
      body.proposalVersion !== input.expectedVersion || body.proposalSha256 !== input.expectedSha256 ||
      body.decision !== "REJECTED" || body.reasonCode !== input.reasonCode ||
      typeof body.recordedAt !== "string" || !Number.isFinite(Date.parse(body.recordedAt))) {
    throw new Error("Core rejection response is invalid");
  }
  return body as CatalogRejection;
}

export async function listCatalogProposalsForReview(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, before: unknown = null,
  now: Date = new Date()): Promise<CatalogReviewIndexResult> {
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  if (!config.curatorScope) return { kind: "not-configured" };
  let cursor;
  try { cursor = parseProposalReviewCursor(before); }
  catch { return { kind: "invalid-cursor" }; }
  try {
    const url = new URL(`${CORE_ORIGIN}/api/v1/catalog-change-proposals`);
    if (cursor) {
      url.searchParams.set("beforeCreatedAt", cursor.createdAt);
      url.searchParams.set("beforeId", cursor.id);
    }
    const response = await fetch(url, {
      method: "GET", headers: curatorHeaders(session, config.curatorScope),
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    if (response.status === 401 || response.status === 403) return { kind: "core-rejected" };
    if (response.status !== 200) return { kind: "core-unavailable" };
    return { kind: "ready", page: proposalIndexFromCore(await response.json()) };
  } catch {
    return { kind: "core-unavailable" };
  }
}

export async function readCatalogProposalReview(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, id: string,
  now: Date = new Date(), evidenceOffset = 0,
  historyCursor: FactReviewHistoryCursor | null = null): Promise<CatalogReviewResult> {
  if (!UUID.test(id)) return { kind: "not-found" };
  if (!Number.isSafeInteger(evidenceOffset) || evidenceOffset < 0 || evidenceOffset > 6800) {
    return { kind: "core-unavailable" };
  }
  if (historyCursor && (!Number.isSafeInteger(historyCursor.version) || historyCursor.version < 0 ||
      !Number.isSafeInteger(historyCursor.afterReviewNumber) || historyCursor.afterReviewNumber < 0)) {
    return { kind: "invalid-review-cursor" };
  }
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  if (!config.curatorScope) return { kind: "not-configured" };
  try {
    const headers = curatorHeaders(session, config.curatorScope);
    const base = `${CORE_ORIGIN}/api/v1/catalog-change-proposals/${id}`;
    const proposalResponse = await fetch(base, {
      method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    if (proposalResponse.status === 404) return { kind: "not-found" };
    if (proposalResponse.status !== 200) return { kind: "core-unavailable" };
    const review = proposalReviewFromCore(await proposalResponse.json(), id);
    if (historyCursor && historyCursor.version !== review.version) return { kind: "stale-review-cursor" };
    const afterReviewNumber = historyCursor?.afterReviewNumber ?? 0;
    const [decisionResponse, impactResponse, evidenceResponse, historyResponse, summaryResponse] = await Promise.all([
      fetch(`${base}/decisions/current`, {
        method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
      }),
      fetch(`${base}/revisions/${review.version}/impact-reports/latest`, {
        method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
      }),
      fetch(`${base}/revisions/${review.version}/evidence-review?offset=${evidenceOffset}`, {
        method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
      }),
      fetch(`${base}/revisions/${review.version}/fact-reviews?afterReviewNumber=${afterReviewNumber}`, {
        method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
      }),
      fetch(`${base}/revisions/${review.version}/fact-reviews/summary?offset=${evidenceOffset}`, {
        method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
      }),
    ]);
    if ([decisionResponse, impactResponse, evidenceResponse, historyResponse, summaryResponse].some(r => r.status === 401 || r.status === 403)) {
      return { kind: "core-rejected" };
    }
    if (![200, 204].includes(decisionResponse.status) || ![200, 204].includes(impactResponse.status) ||
        evidenceResponse.status !== 200 || historyResponse.status !== 200 || summaryResponse.status !== 200) {
      return { kind: "core-unavailable" };
    }
    let rejection: CatalogRejection | null = null;
    if (decisionResponse.status === 200) {
      const value: unknown = await decisionResponse.json();
      if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid decision");
      const reasonCode = (value as Record<string, unknown>).reasonCode;
      if (typeof reasonCode !== "string" || !rejectionReasons.includes(reasonCode as CatalogRejectionReason)) {
        throw new Error("Invalid decision reason");
      }
      rejection = rejectionFromCore(value, id, {
        expectedVersion: review.version, expectedSha256: review.proposalSha256,
        reasonCode: reasonCode as CatalogRejectionReason,
      });
    }
    const impact = impactResponse.status === 200
      ? impactReviewFromCore(await impactResponse.json(), review) : null;
    const evidence = evidencePageFromCore(await evidenceResponse.json(), review, evidenceOffset);
    const factReviews = factReviewHistoryFromCore(await historyResponse.json(), review, afterReviewNumber);
    const factReviewSummary = factReviewSummaryFromCore(await summaryResponse.json(), review, evidence);
    const prerequisites = catalogReviewPrerequisites(review, evidence, factReviewSummary, impact, rejection !== null);
    return { kind: "ready", review, rejection, impact, evidence, factReviews, factReviewSummary, prerequisites };
  } catch {
    return { kind: "core-unavailable" };
  }
}

export type PublicationReviewResult =
  | { kind: "ready"; report: PublicationReview }
  | { kind: Exclude<CuratorProbeStatus, "ready"> | "invalid" };

/** Separate fresh read: a historical receipt or another review panel is never a fallback. */
export async function readCatalogPublicationPreflight(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, input: PublicationReference,
  now: Date = new Date()): Promise<PublicationReviewResult> {
  let reference: PublicationReference;
  try { reference = publicationReference(input); } catch { return { kind: "invalid" }; }
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  if (!config.curatorScope) return { kind: "not-configured" };
  try {
    const path = reference.mode === "PROPOSAL_APPROVAL"
      ? `proposals/${reference.inputId}/revisions/${reference.inputVersion}` : `bootstrap-reviews/${reference.inputId}`;
    const url = new URL(`${CORE_ORIGIN}/internal/v1/catalog-curator/${path}/publication-preflight`);
    url.searchParams.set("expectedSha256", reference.inputSha256);
    const response = await fetch(url, { method: "GET", headers: curatorHeaders(session, config.curatorScope),
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(10_000) });
    if (response.status === 401 || response.status === 403) return { kind: "core-rejected" };
    if (response.status !== 200) return { kind: "core-unavailable" };
    const body: unknown = JSON.parse(await boundedPrerequisiteText(response, publicationReviewByteLimit));
    return { kind: "ready", report: publicationReviewFromCore(body, reference, now) };
  } catch { return { kind: "core-unavailable" }; }
}

export async function rejectCatalogProposal(session: BrowserSession,
  config: Pick<AuthConfiguration, "issuer" | "curatorScope">, id: string,
  input: CatalogRejectionInput, now: Date = new Date()): Promise<CatalogRejectionResult> {
  if (!UUID.test(id) || !Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 0 ||
      !SHA256.test(input.expectedSha256) || !rejectionReasons.includes(input.reasonCode)) {
    return { kind: "invalid" };
  }
  const authorization = await readCuratorAuthorization(session, config, now);
  if (authorization !== "ready") return { kind: authorization };
  if (!config.curatorScope) return { kind: "not-configured" };
  try {
    const response = await fetch(`${CORE_ORIGIN}/api/v1/catalog-change-proposals/${id}/decisions/rejection`, {
      method: "POST",
      headers: { ...curatorHeaders(session, config.curatorScope), "Content-Type": "application/json" },
      body: JSON.stringify(input),
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
    });
    if (response.status === 404) return { kind: "not-found" };
    if (response.status === 409) return { kind: "conflict" };
    if (response.status === 400) return { kind: "invalid" };
    if (response.status === 401 || response.status === 403) return { kind: "core-rejected" };
    if (response.status !== 201) return { kind: "core-unavailable" };
    return { kind: "rejected", decision: rejectionFromCore(await response.json(), id, input) };
  } catch {
    return { kind: "core-unavailable" };
  }
}

export async function provisionPersonalWorkspace(
  identity: Omit<BrowserSession, "workspaceId">,
): Promise<string> {
  const response = await fetch(`${CORE_ORIGIN}/internal/v1/personal-workspaces`, {
    method: "POST",
    headers: { Authorization: `Bearer ${serviceToken()}`, "Content-Type": "application/json" },
    body: JSON.stringify({ issuer: identity.issuer, subject: identity.subject }),
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (!response.ok) throw new Error("Core workspace provisioning failed");
  const body: unknown = await response.json();
  if (!body || typeof body !== "object" || !("workspaceId" in body) ||
      typeof body.workspaceId !== "string" ||
      !UUID.test(body.workspaceId)) {
    throw new Error("Core workspace response is invalid");
  }
  return body.workspaceId;
}

function assessmentHeaders(session: BrowserSession): Record<string, string> {
  if (!UUID.test(session.workspaceId) || !session.issuer || !session.subject) {
    throw new Error("Personal workspace session is invalid");
  }
  return {
    Authorization: `Bearer ${serviceToken()}`,
    "X-AuthWeave-Oidc-Issuer": session.issuer,
    "X-AuthWeave-Oidc-Subject": session.subject,
  };
}

function assessmentFromCore(value: unknown, session: BrowserSession, id?: string): PersonalAssessment {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Core assessment response is invalid");
  }
  const body = value as Record<string, unknown>;
  if (typeof body.id !== "string" || !UUID.test(body.id) || (id && body.id !== id) ||
      body.workspaceId !== session.workspaceId ||
      !["DRAFT", "READY_FOR_EVALUATION", "EVALUATED", "DECIDED", "ARCHIVED"].includes(String(body.status)) ||
      !Number.isSafeInteger(body.version) || Number(body.version) < 0 || body.profileSchemaVersion !== 6 ||
      !body.profile || typeof body.profile !== "object" || Array.isArray(body.profile) ||
      !auditabilityValues(body.profile as Record<string, unknown>)) {
    throw new Error("Core assessment response is invalid");
  }
  return {
    id: body.id,
    status: body.status as PersonalAssessment["status"],
    version: body.version as number,
    profile: body.profile as Record<string, unknown>,
  };
}

export async function createPersonalAssessment(session: BrowserSession): Promise<PersonalAssessment> {
  const headers = assessmentHeaders(session);
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments`, {
    method: "POST", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 201) throw new Error("Core assessment creation failed");
  return assessmentFromCore(await response.json(), session);
}

export async function readPersonalAssessment(session: BrowserSession, id: string): Promise<PersonalAssessment | null> {
  if (!UUID.test(id)) throw new Error("Assessment ID is invalid");
  const headers = assessmentHeaders(session);
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments/${id}`, {
    method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 404) return null;
  if (response.status !== 200) throw new Error("Core assessment read failed");
  return assessmentFromCore(await response.json(), session, id);
}

export type ProfileUpdateResult = "saved" | "conflict" | "invalid" | "not-found" | "not-editable";

async function updatePersonalProfile(session: BrowserSession, id: string, expectedVersion: number,
  patch: (profile: Record<string, unknown>) => Record<string, unknown>,
  savedMatches: (expected: Record<string, unknown>, saved: Record<string, unknown>) => boolean = () => true): Promise<ProfileUpdateResult> {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("Profile update request is invalid");
  }
  const current = await readPersonalAssessment(session, id);
  if (!current) return "not-found";
  if (current.status !== "DRAFT") return "not-editable";
  if (current.version !== expectedVersion) return "conflict";
  const profile = patch(current.profile);
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments/${id}/profile`, {
    method: "PUT",
    headers: { ...assessmentHeaders(session), "Content-Type": "application/json" },
    body: JSON.stringify({ expectedVersion, profile }),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 409) return "conflict";
  if (response.status === 400 || response.status === 422) return "invalid";
  if (response.status === 404) return "not-found";
  if (response.status !== 200) throw new Error("Core profile update failed");
  const saved = assessmentFromCore(await response.json(), session, id);
  if (saved.status !== "DRAFT" || saved.version < expectedVersion ||
      saved.version > expectedVersion + 1 ||
      JSON.stringify(auditabilityValues(saved.profile)) !== JSON.stringify(auditabilityValues(profile)) ||
      !savedMatches(profile, saved.profile)) {
    throw new Error("Core profile update response is invalid");
  }
  return "saved";
}

export async function updatePersonalCapabilities(
  session: BrowserSession, id: string, expectedVersion: number, values: CapabilityValues,
): Promise<ProfileUpdateResult> {
  return updatePersonalProfile(session, id, expectedVersion,
    profile => withCapabilityValues(profile, values));
}

export async function updatePersonalEvaluationContext(
  session: BrowserSession, id: string, expectedVersion: number, values: EvaluationContextValues,
): Promise<ProfileUpdateResult> {
  return updatePersonalProfile(session, id, expectedVersion,
    profile => withEvaluationContextValues(profile, values), assuranceExpectationSaveMatches);
}

export async function updatePersonalUsagePlanning(
  session: BrowserSession, id: string, expectedVersion: number, values: UsagePlanningValues,
): Promise<ProfileUpdateResult> {
  return updatePersonalProfile(session, id, expectedVersion,
    profile => withUsagePlanningValues(profile, values));
}

export async function updatePersonalOperationalPreferences(
  session: BrowserSession, id: string, expectedVersion: number, values: OperationsInputs,
): Promise<ProfileUpdateResult> {
  return updatePersonalProfile(session, id, expectedVersion,
    profile => withOperationalPreferences(profile, values), operationalPreferencesSaveMatches);
}

export async function updatePersonalAuditability(
  session: BrowserSession, id: string, expectedVersion: number, values: AuditabilityValues,
): Promise<ProfileUpdateResult> {
  return updatePersonalProfile(session, id, expectedVersion,
    profile => withAuditabilityValues(profile, values));
}

export async function listPersonalAssessments(
  session: BrowserSession, beforeId?: string,
): Promise<PersonalAssessmentListPage> {
  if (beforeId !== undefined && !UUID.test(beforeId)) throw new Error("Assessment cursor is invalid");
  const headers = assessmentHeaders(session);
  const url = new URL(`/api/v6/workspaces/${session.workspaceId}/assessments/context-index`, CORE_ORIGIN);
  url.searchParams.set("limit", "20");
  if (beforeId) url.searchParams.set("beforeId", beforeId);
  const response = await fetch(url.toString(), {
    method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core assessment list failed");
  const body: unknown = await response.json();
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new Error("Core assessment list response is invalid");
  }
  const page = body as Record<string, unknown>;
  if (Object.keys(page).length !== 2 || Object.keys(page).some((key) => !["items", "nextBeforeId"].includes(key)) ||
      !Array.isArray(page.items) || page.items.length > 20 ||
      (page.nextBeforeId !== null &&
        (typeof page.nextBeforeId !== "string" || !UUID.test(page.nextBeforeId)))) {
    throw new Error("Core assessment list response is invalid");
  }
  const items = page.items.map((value: unknown): PersonalAssessmentListItem => {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error("Core assessment list response is invalid");
    }
    const item = value as Record<string, unknown>;
    if (Object.keys(item).length !== 6 ||
        Object.keys(item).some((key) => !["id", "status", "version", "createdAt", "updatedAt", "context"].includes(key)) ||
        typeof item.id !== "string" || !UUID.test(item.id) ||
        typeof item.status !== "string" ||
        !["DRAFT", "READY_FOR_EVALUATION", "EVALUATED", "DECIDED", "ARCHIVED"].includes(item.status) ||
        !Number.isSafeInteger(item.version) || Number(item.version) < 0 ||
        typeof item.createdAt !== "string" || Number.isNaN(Date.parse(item.createdAt)) ||
        typeof item.updatedAt !== "string" || Number.isNaN(Date.parse(item.updatedAt))) {
      throw new Error("Core assessment list response is invalid");
    }
    return {
      id: item.id, status: item.status as PersonalAssessment["status"],
      version: item.version as number, createdAt: item.createdAt, updatedAt: item.updatedAt,
      context: assessmentListContext(item.context),
    };
  });
  if (new Set(items.map(item => item.id)).size !== items.length ||
      items.some(item => item.id === beforeId) ||
      (page.nextBeforeId !== null &&
      (items.length === 0 || items.at(-1)?.id !== page.nextBeforeId))) {
    throw new Error("Core assessment list response is invalid");
  }
  return { items, nextBeforeId: page.nextBeforeId };
}

function assessmentListContext(value: unknown): PersonalAssessmentListItem["context"] {
  if (value === null) return null;
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Core assessment list response is invalid");
  }
  const context = value as Record<string, unknown>;
  const selected = <T extends string>(input: unknown, choices: readonly T[]): T[] => {
    if (!Array.isArray(input) || input.length > choices.length || new Set(input).size !== input.length ||
        input.some(item => typeof item !== "string" || !choices.includes(item as T))) {
      throw new Error("Core assessment list response is invalid");
    }
    return [...input] as T[];
  };
  if (Object.keys(context).length !== 3 ||
      Object.keys(context).some(key => !["applicationType", "clients", "userPopulations"].includes(key)) ||
      typeof context.applicationType !== "string" ||
      !applicationTypes.includes(context.applicationType as (typeof applicationTypes)[number])) {
    throw new Error("Core assessment list response is invalid");
  }
  return { applicationType: context.applicationType as (typeof applicationTypes)[number],
    clients: selected(context.clients, clientTypes), userPopulations: selected(context.userPopulations, populations) };
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Core comparison response is invalid");
  }
  return value as Record<string, unknown>;
}

function exactKeys(value: Record<string, unknown>, keys: string[]): void {
  if (Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) {
    throw new Error("Core comparison response is invalid");
  }
}

function boundedText(value: unknown, max: number): string {
  if (typeof value !== "string" || value.length < 1 || value.length > max) {
    throw new Error("Core comparison response is invalid");
  }
  return value;
}

export async function readSyntheticComparison(session: BrowserSession, id: string,
  expectedVersion: number, values: AuditabilityValues): Promise<SyntheticComparisonSummary> {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("Comparison request is invalid");
  }
  const headers = assessmentHeaders(session);
  const binding = auditabilityPreviewBinding(session.workspaceId, id, expectedVersion, values);
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments/${id}/comparison-preflight`, {
    method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core comparison read failed");
  return comparisonFromCore(JSON.parse(await boundedPrerequisiteText(response, auditabilityPreviewByteLimit)), binding);
}

export async function readComparisonEvidence(session: BrowserSession, id: string,
  expectedVersion: number, values: AuditabilityValues): Promise<ComparisonEvidenceSummary> {
  const binding = auditabilityPreviewBinding(session.workspaceId, id, expectedVersion, values);
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments/${id}/comparison-evidence-preview`, {
    method: "GET", headers: assessmentHeaders(session), cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core comparison evidence read failed");
  return comparisonEvidenceFromCore(JSON.parse(await boundedPrerequisiteText(response, comparisonEvidenceByteLimit)), binding);
}

const patternMetadata = {
  BFF_SESSION: ["BROWSER", "SERVER_SIDE"],
  SERVER_SIDE_SESSION: ["BROWSER", "SERVER_SIDE"],
  SPA_CODE_PKCE: ["BROWSER", "BROWSER"],
  NATIVE_CODE_PKCE: ["NATIVE_MOBILE", "NATIVE_APP"],
  M2M_CLIENT_CREDENTIALS: ["MACHINE_TO_MACHINE", "WORKLOAD"],
} as const;
const patternChecks = ["application.clients", "security.browserTokenExposureMinimization"];
const patternDeferred = ["application.type", "audience", "protocols", "provisioning",
  "security.multiFactorAuthentication", "security.auditability", "security.dataResidency",
  "security.assurance", "security.complianceTargets", "operations"];
const patternReasons = ["CLIENT_SELECTED", "CLIENT_NOT_SELECTED", "CLIENT_CONTEXT_UNKNOWN",
  "PATTERN_NOT_APPLICABLE", "BROWSER_CRITERION_NOT_APPLICABLE", "TOKENS_HELD_SERVER_SIDE",
  "ACCEPTABLE_EXPOSURE_UNDEFINED", "MINIMIZATION_PROHIBITION_UNDEFINED", "REQUIREMENT_UNKNOWN",
  "PREFERENCE_NOT_SCORED", "NO_REQUIREMENT"];

function architecturePatternsFromCore(value: unknown, session: BrowserSession, id: string,
  expectedVersion: number, context: Pick<EvaluationContextValues,
    "clients" | "browserTokenExposureMinimization">): ArchitecturePatternPreflightSummary {
  const body = object(value);
  exactKeys(body, ["workspaceId", "assessmentId", "assessmentVersion", "policyVersion", "evaluatedAt",
    "scope", "recommendationReady", "selectedClients", "browserTokenExposureRequirement",
    "checkedPaths", "deferredPaths", "patterns"]);
  const expectedClients = [...context.clients].sort();
  const checked = body.checkedPaths;
  const deferred = body.deferredPaths;
  if (body.workspaceId !== session.workspaceId || body.assessmentId !== id ||
      body.assessmentVersion !== expectedVersion || body.policyVersion !== "architecture-pattern-preflight-1" ||
      body.scope !== "ARCHITECTURE_PATTERN_PREFLIGHT" || body.recommendationReady !== false ||
      body.browserTokenExposureRequirement !== context.browserTokenExposureMinimization ||
      !Array.isArray(body.selectedClients) ||
      JSON.stringify([...body.selectedClients].sort()) !== JSON.stringify(expectedClients) ||
      !Array.isArray(checked) || JSON.stringify(checked) !== JSON.stringify(patternChecks) ||
      !Array.isArray(deferred) || JSON.stringify(deferred) !== JSON.stringify(patternDeferred) ||
      !Array.isArray(body.patterns) || body.patterns.length !== 5 ||
      Number.isNaN(Date.parse(boundedText(body.evaluatedAt, 100)))) {
    throw new Error("Core architecture pattern response is invalid");
  }
  const seen = new Set<string>();
  const textList = (value: unknown): string[] => {
    if (!Array.isArray(value) || value.length < 1 || value.length > 4 ||
        new Set(value).size !== value.length) throw new Error("Core architecture pattern response is invalid");
    return value.map(item => boundedText(item, 400));
  };
  const patterns = body.patterns.map((entry: unknown): ArchitecturePatternSummary => {
    const raw = object(entry);
    exactKeys(raw, ["patternId", "displayName", "clientType", "tokenHandling", "status",
      "checks", "advantages", "tradeoffs", "prerequisites", "references"]);
    const id = String(raw.patternId);
    const meta = patternMetadata[id as keyof typeof patternMetadata];
    if (!meta || seen.has(id) || raw.clientType !== meta[0] || raw.tokenHandling !== meta[1] ||
        !Array.isArray(raw.checks) || raw.checks.length !== 2 ||
        !Array.isArray(raw.references) || raw.references.length < 1 || raw.references.length > 2) {
      throw new Error("Core architecture pattern response is invalid");
    }
    seen.add(id);
    const applicable = context.clients.includes(meta[0]);
    if (!["MATCHES_CHECKED_REQUIREMENTS", "NEEDS_INFORMATION",
      "NOT_APPLICABLE"].includes(String(raw.status))) {
      throw new Error("Core architecture pattern response is invalid");
    }
    const checks = raw.checks.map((item: unknown, index: number) => {
      const check = object(item);
      exactKeys(check, ["profilePath", "outcome", "reasonCode", "explanation"]);
      if (check.profilePath !== patternChecks[index] ||
          !["PASS", "UNKNOWN", "NOT_APPLIED"].includes(String(check.outcome)) ||
          !patternReasons.includes(String(check.reasonCode))) {
        throw new Error("Core architecture pattern response is invalid");
      }
      return { profilePath: check.profilePath as string,
        outcome: check.outcome as ArchitecturePatternSummary["checks"][number]["outcome"],
        reasonCode: check.reasonCode as string, explanation: boundedText(check.explanation, 400) };
    });
    const expectedStatus = context.clients.length === 0 ? "NEEDS_INFORMATION" :
      !applicable ? "NOT_APPLICABLE" :
        checks.some(check => check.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" :
          "MATCHES_CHECKED_REQUIREMENTS";
    if (raw.status !== expectedStatus) {
      throw new Error("Core architecture pattern response is invalid");
    }
    const references = raw.references.map((item: unknown) => {
      const url = new URL(boundedText(item, 2048));
      if (url.protocol !== "https:" || url.username || url.password ||
          !["www.ietf.org", "www.rfc-editor.org"].includes(url.hostname)) {
        throw new Error("Core architecture pattern response is invalid");
      }
      return url.toString();
    });
    return { patternId: id as ArchitecturePatternSummary["patternId"],
      displayName: boundedText(raw.displayName, 400), clientType: meta[0], tokenHandling: meta[1],
      status: raw.status as ArchitecturePatternSummary["status"], checks,
      advantages: textList(raw.advantages), tradeoffs: textList(raw.tradeoffs),
      prerequisites: textList(raw.prerequisites), references };
  });
  if (seen.size !== 5) throw new Error("Core architecture pattern response is invalid");
  return { assessmentVersion: expectedVersion, selectedClients: [...context.clients],
    browserTokenExposureRequirement: context.browserTokenExposureMinimization,
    checkedPaths: [...patternChecks], deferredPaths: [...patternDeferred], patterns };
}

export async function readPersonalArchitecturePatterns(session: BrowserSession, id: string,
  expectedVersion: number, context: Pick<EvaluationContextValues,
    "clients" | "browserTokenExposureMinimization">): Promise<ArchitecturePatternPreflightSummary> {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0 ||
      !Array.isArray(context.clients) || !context.browserTokenExposureMinimization) {
    throw new Error("Architecture pattern request is invalid");
  }
  const response = await fetch(`${CORE_ORIGIN}/api/v1/workspaces/${session.workspaceId}/assessments/${id}/architecture-pattern-preflight`, {
    method: "GET", headers: assessmentHeaders(session), cache: "no-store", redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core architecture pattern read failed");
  return architecturePatternsFromCore(await response.json(), session, id, expectedVersion, context);
}

export async function previewPersonalProvisioningLifecycle(session: BrowserSession, id: string,
  input: LifecycleInput): Promise<{ kind: "preview"; preview: LifecyclePreview } | { kind: "not-found" | "conflict" | "invalid" }> {
  if (!UUID.test(id)) throw new Error("Invalid provisioning preview request");
  // Validate runtime callers before any network request; do not accept caller-supplied requirements.
  validateLifecycleInput(input);
  const assessment = await readPersonalAssessment(session, id);
  if (!assessment) return { kind: "not-found" };
  if (assessment.version !== input.expectedVersion) return { kind: "conflict" };
  const requirements = provisioningRequirements(assessment.profile);
  const response = await fetch(`${CORE_ORIGIN}/api/v1/workspaces/${session.workspaceId}/assessments/${id}/provisioning-lifecycle-preview`, {
    method: "POST", headers: { ...assessmentHeaders(session), "Content-Type": "application/json" }, body: JSON.stringify(input),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 404) return { kind: "not-found" };
  if (response.status === 409) return { kind: "conflict" };
  if (response.status === 400) return { kind: "invalid" };
  if (response.status !== 200) throw new Error("Core provisioning preview failed");
  return { kind: "preview", preview: lifecyclePreviewFromCore(JSON.parse(await boundedPrerequisiteText(response, lifecycleByteLimit)),
    { workspaceId: session.workspaceId, assessmentId: id, input, requirements }) };
}

export async function previewPersonalProvisioningLifecycleV2(session: BrowserSession, id: string,
  input: LifecycleV2Input): Promise<{ kind: "preview"; preview: LifecycleV2Preview } | { kind: "not-found" | "conflict" | "invalid" }> {
  if (!UUID.test(id)) throw new Error("Invalid group and offboarding preview request");
  validateLifecycleV2Input(input);
  const assessment = await readPersonalAssessment(session, id);
  if (!assessment) return { kind: "not-found" };
  if (assessment.version !== input.expectedVersion) return { kind: "conflict" };
  const requirements = provisioningRequirements(assessment.profile);
  const response = await fetch(`${CORE_ORIGIN}/api/v2/workspaces/${session.workspaceId}/assessments/${id}/provisioning-lifecycle-preview`, {
    method: "POST", headers: { ...assessmentHeaders(session), "Content-Type": "application/json" }, body: JSON.stringify(input),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 404) return { kind: "not-found" };
  if (response.status === 409) return { kind: "conflict" };
  if (response.status === 400) return { kind: "invalid" };
  if (response.status !== 200) throw new Error("Core group and offboarding preview failed");
  return { kind: "preview", preview: lifecycleV2PreviewFromCore(JSON.parse(await boundedPrerequisiteText(response, lifecycleV2ByteLimit)),
    { workspaceId: session.workspaceId, assessmentId: id, input, requirements }) };
}

export async function previewPersonalArchitectureConfiguration(session: BrowserSession, id: string,
  input: ArchitectureConfigurationInput): Promise<{ kind: "preview"; preview: ArchitectureConfigurationPreview } | { kind: "not-found" | "conflict" | "invalid" }> {
  if (!UUID.test(id)) throw new Error("Invalid architecture settings request");
  validateArchitectureConfigurationInput(input);
  const assessment = await readPersonalAssessment(session, id);
  if (!assessment) return { kind: "not-found" };
  if (assessment.version !== input.expectedVersion) return { kind: "conflict" };
  const context = evaluationContextValues(assessment.profile);
  if (!context) throw new Error("Core profile cannot be read safely");
  const response = await fetch(`${CORE_ORIGIN}/api/v1/workspaces/${session.workspaceId}/assessments/${id}/architecture-configuration-preview`, {
    method: "POST", headers: { ...assessmentHeaders(session), "Content-Type": "application/json" }, body: JSON.stringify(input),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 404) return { kind: "not-found" };
  if (response.status === 409) return { kind: "conflict" };
  if (response.status === 400) return { kind: "invalid" };
  if (response.status !== 200) throw new Error("Core architecture settings preview failed");
  return { kind: "preview", preview: architectureConfigurationFromCore(JSON.parse(await boundedPrerequisiteText(response, architectureConfigurationByteLimit)),
    { workspaceId: session.workspaceId, assessmentId: id, input, context }) };
}

export async function previewPersonalArchitecturePrerequisites(session: BrowserSession, id: string,
  input: PrerequisiteInput): Promise<{ kind: "preview"; preview: PrerequisitePreview } |
  { kind: "not-found" | "conflict" | "invalid" }> {
  if (!UUID.test(id)) throw new Error("Invalid prerequisite request");
  // Recheck typed input for callers other than the form route.
  const params = new URLSearchParams({ expectedVersion: String(input.expectedVersion), patternId: input.patternId });
  for (const [key, value] of Object.entries(input.declarations)) params.append(key, value);
  parsePrerequisiteForm(params);
  const assessment = await readPersonalAssessment(session, id);
  if (!assessment) return { kind: "not-found" };
  if (assessment.version !== input.expectedVersion) return { kind: "conflict" };
  const context = evaluationContextValues(assessment.profile);
  if (!context) throw new Error("Core profile cannot be read safely");
  const response = await fetch(`${CORE_ORIGIN}/api/v1/workspaces/${session.workspaceId}/assessments/${id}/architecture-prerequisite-preview`, {
    method: "POST", headers: { ...assessmentHeaders(session), "Content-Type": "application/json" },
    body: JSON.stringify(input), cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 404) return { kind: "not-found" };
  if (response.status === 409) return { kind: "conflict" };
  if (response.status === 400) return { kind: "invalid" };
  if (response.status !== 200) throw new Error("Core prerequisite preview failed");
  const body = object(JSON.parse(await boundedPrerequisiteText(response, 32_768)));
  exactKeys(body, ["preflight", "declarations", "analysis"]);
  const declared = object(body.declarations);
  exactKeys(declared, Object.keys(input.declarations));
  if (Object.keys(declared).some(key => declared[key] !== input.declarations[key as keyof typeof input.declarations])) {
    throw new Error("Core prerequisite declaration binding is invalid");
  }
  const preflight = architecturePatternsFromCore(body.preflight, session, id, input.expectedVersion, context);
  const pattern = preflight.patterns.find(p => p.patternId === input.patternId)!;
  const scope = context.clients.length === 0 ? "UNKNOWN" :
    context.clients.includes(pattern.clientType) ? "SELECTED" : "NOT_SELECTED";
  return { kind: "preview", preview: { assessmentVersion: input.expectedVersion,
    analysis: prerequisiteAnalysis(body.analysis, input, scope) } };
}

function usagePlanningFromCore(value: unknown, session: BrowserSession, id: string,
  expectedVersion: number, planning: UsagePlanningValues): UsagePlanningPreflightSummary {
  const body = object(value);
  exactKeys(body, ["workspaceId", "assessmentId", "assessmentVersion", "policyVersion", "evaluatedAt",
    "scope", "pricingEvaluated", "recommendationReady", "status", "scopeDescription", "assumptions",
    "missingPaths", "quantityChecks", "explanation"]);
  const missing: UsageMissingPath[] = [];
  if (planning.scopeDescription.trim() === "") missing.push("operations.usagePlanning.scopeDescription");
  for (const metric of usageMetrics) {
    if (!planning.volumes[metric.key]) missing.push(`operations.usagePlanning.volumes.${metric.key}`);
  }
  if (planning.assumptions.length === 0 &&
      Object.values(planning.volumes).some(input => input?.basis === "ASSUMED")) {
    missing.push("operations.usagePlanning.assumptions");
  }
  const expectedStatus = missing.length === 0 ? "INPUTS_RECORDED" : "NEEDS_INFORMATION";
  if (body.workspaceId !== session.workspaceId || body.assessmentId !== id ||
      body.assessmentVersion !== expectedVersion || body.policyVersion !== "usage-planning-preflight-1" ||
      body.scope !== "USAGE_PLANNING_PREFLIGHT" || body.pricingEvaluated !== false ||
      body.recommendationReady !== false || body.status !== expectedStatus ||
      body.scopeDescription !== planning.scopeDescription ||
      JSON.stringify(body.assumptions) !== JSON.stringify(planning.assumptions) ||
      JSON.stringify(body.missingPaths) !== JSON.stringify(missing) ||
      !Array.isArray(body.quantityChecks) || body.quantityChecks.length !== usageMetrics.length) {
    throw new Error("Core usage planning response is invalid");
  }
  const evaluatedAt = boundedText(body.evaluatedAt, 100);
  if (Number.isNaN(Date.parse(evaluatedAt))) throw new Error("Core usage planning response is invalid");
  boundedText(body.explanation, 350);
  const quantityChecks = body.quantityChecks.map((item: unknown, index: number) => {
    const raw = object(item);
    exactKeys(raw, ["metric", "unit", "definition", "status", "input"]);
    const metric = usageMetrics[index];
    const expected = planning.volumes[metric.key];
    if (raw.metric !== metric.key || raw.unit !== metric.unit ||
        raw.status !== (expected?.basis ?? "UNKNOWN")) {
      throw new Error("Core usage planning response is invalid");
    }
    boundedText(raw.definition, 200);
    let input: number | null = null;
    if (expected) {
      const quantity = object(raw.input);
      exactKeys(quantity, ["basis", "value"]);
      if (quantity.basis !== expected.basis || quantity.value !== expected.value) {
        throw new Error("Core usage planning response is invalid");
      }
      input = expected.value;
    } else if (raw.input !== null) {
      throw new Error("Core usage planning response is invalid");
    }
    return { metric: metric.key, unit: metric.unit,
      status: raw.status as UsagePlanningPreflightSummary["quantityChecks"][number]["status"], value: input };
  });
  return { assessmentVersion: expectedVersion, evaluatedAt, status: expectedStatus,
    missingPaths: missing, quantityChecks };
}

export async function readPersonalAuditability(session: BrowserSession, id: string,
  expectedVersion: number, values: AuditabilityValues): Promise<AuditabilityPreview> {
  const binding = auditabilityPreviewBinding(session.workspaceId, id, expectedVersion, values);
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments/${id}/auditability-capability-preflight`, {
    method: "GET", headers: assessmentHeaders(session), cache: "no-store", redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core auditability capability read failed");
  return auditabilityPreviewFromCore(JSON.parse(await boundedPrerequisiteText(response, auditabilityPreviewByteLimit)), binding);
}

export async function readPersonalAssurancePlanning(session: BrowserSession, id: string,
  expectedVersion: number, values: AssurancePlanningValues): Promise<AssurancePlanningPreview> {
  const binding = assurancePlanningBinding(session.workspaceId, id, expectedVersion, values);
  const response = await fetch(`${CORE_ORIGIN}/api/v1/workspaces/${session.workspaceId}/assessments/${id}/assurance-compliance-planning-preflight`, {
    method: "GET", headers: assessmentHeaders(session), cache: "no-store", redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core assurance/compliance planning read failed");
  return assurancePlanningFromCore(JSON.parse(await boundedPrerequisiteText(response, assurancePlanningByteLimit)), binding);
}

export async function readPersonalOperationsPlanning(session: BrowserSession, id: string,
  expectedVersion: number, values: OperationsPlanningValues): Promise<OperationsPlanningPreview> {
  const binding = operationsPlanningBinding(session.workspaceId, id, expectedVersion, values);
  const response = await fetch(`${CORE_ORIGIN}/api/v1/workspaces/${session.workspaceId}/assessments/${id}/operations-planning-preflight`, {
    method: "GET", headers: assessmentHeaders(session), cache: "no-store", redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core operations planning read failed");
  return operationsPlanningFromCore(JSON.parse(await boundedPrerequisiteText(response, operationsPlanningByteLimit)), binding);
}

export async function readPersonalUsagePlanning(session: BrowserSession, id: string,
  expectedVersion: number, planning: UsagePlanningValues): Promise<UsagePlanningPreflightSummary> {
  const checkedPlanning = usagePlanningValues({ operations: { usagePlanning: planning } });
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0 || !checkedPlanning) {
    throw new Error("Usage planning request is invalid");
  }
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments/${id}/usage-planning-preflight`, {
    method: "GET", headers: assessmentHeaders(session), cache: "no-store", redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core usage planning read failed");
  return usagePlanningFromCore(await response.json(), session, id, expectedVersion, checkedPlanning);
}

function weightedPreviewFromCore(value: unknown, session: BrowserSession, id: string,
  expectedVersion: number, weights: CapabilityWeights, values: AuditabilityValues): WeightedPreview {
  const body = object(value);
  exactKeys(body, ["comparison", "scoringPolicyVersion", "weights", "rankingPerformed", "recommendationReady", "scores"]);
  const comparison = comparisonFromCore(body.comparison, auditabilityPreviewBinding(session.workspaceId, id, expectedVersion, values));
  const echoedWeights = object(body.weights);
  const expectedKeys = Object.keys(weights);
  if (body.scoringPolicyVersion !== "explicit-capability-weights-1" ||
      body.rankingPerformed !== false || body.recommendationReady !== false ||
      Object.keys(echoedWeights).length !== expectedKeys.length ||
      expectedKeys.some(key => echoedWeights[key] !== weights[key as keyof CapabilityWeights]) ||
      !Array.isArray(body.scores) || body.scores.length !== comparison.candidates.length) {
    throw new Error("Core weighted preview response is invalid");
  }
  const scoreById = new Map<string, WeightedCandidate>();
  for (const item of body.scores) {
    const raw = object(item);
    exactKeys(raw, ["optionId", "status", "score", "contributions"]);
    const candidate = comparison.candidates.find(entry => entry.optionId === raw.optionId);
    if (!candidate || scoreById.has(candidate.optionId) ||
        candidate.capabilityPreferences.length !== expectedKeys.length ||
        candidate.capabilityPreferences.some(preference => !expectedKeys.includes(preference.capability)) ||
        !["SCORED", "EXCLUDED", "UNRESOLVED_HARD_CONSTRAINTS", "UNKNOWN_PREFERENCE_EVIDENCE"].includes(String(raw.status)) ||
        !Array.isArray(raw.contributions)) {
      throw new Error("Core weighted preview response is invalid");
    }
    const status = raw.status as WeightedCandidate["status"];
    const expectedStatus = candidate.hardVerdict === "EXCLUDED" ? "EXCLUDED" :
      candidate.hardVerdict === "UNRESOLVED" ? "UNRESOLVED_HARD_CONSTRAINTS" :
        candidate.capabilityPreferences.some(preference => preference.outcome === "UNKNOWN") ?
          "UNKNOWN_PREFERENCE_EVIDENCE" : "SCORED";
    if (status !== expectedStatus) throw new Error("Core weighted preview response is invalid");
    let contributions: WeightedContribution[] = [];
    if (status === "SCORED") {
      if (!Number.isSafeInteger(raw.score) || Number(raw.score) < 0 || Number(raw.score) > 100 ||
          raw.contributions.length !== expectedKeys.length) {
        throw new Error("Core weighted preview response is invalid");
      }
      const seen = new Set<string>();
      contributions = raw.contributions.map((entry: unknown): WeightedContribution => {
        const contribution = object(entry);
        exactKeys(contribution, ["capability", "weight", "outcome", "earnedPoints"]);
        const preference = candidate.capabilityPreferences.find(p => p.capability === contribution.capability);
        const capability = String(contribution.capability);
        const weight = weights[capability as keyof CapabilityWeights];
        if (!preference || seen.has(capability) || weight === undefined || contribution.weight !== weight ||
            contribution.outcome !== preference.outcome ||
            contribution.earnedPoints !== (preference.outcome === "AVAILABLE" ? weight : 0)) {
          throw new Error("Core weighted preview response is invalid");
        }
        seen.add(capability);
        return {
          capability, weight, outcome: contribution.outcome as WeightedContribution["outcome"],
          earnedPoints: contribution.earnedPoints as number,
        };
      });
      if (contributions.reduce((sum, entry) => sum + entry.earnedPoints, 0) !== raw.score) {
        throw new Error("Core weighted preview response is invalid");
      }
    } else if (raw.score !== null || raw.contributions.length !== 0) {
      throw new Error("Core weighted preview response is invalid");
    }
    scoreById.set(candidate.optionId, {
      optionId: candidate.optionId, displayName: candidate.displayName, plan: candidate.plan,
      region: candidate.region, status, score: raw.score as number | null, contributions,
    });
  }
  if (scoreById.size !== comparison.candidates.length) throw new Error("Core weighted preview response is invalid");
  return {
    assessmentVersion: comparison.assessmentVersion,
    catalogVersion: comparison.catalogVersion,
    scoringPolicyVersion: body.scoringPolicyVersion as string,
    candidates: comparison.candidates.map(candidate => scoreById.get(candidate.optionId)!),
  };
}

export type WeightedPreviewResult =
  | { kind: "preview"; preview: WeightedPreview }
  | { kind: "conflict" }
  | { kind: "invalid" }
  | { kind: "not-found" };

export async function previewPersonalWeightedComparison(
  session: BrowserSession, id: string, expectedVersion: number, weights: CapabilityWeights,
): Promise<WeightedPreviewResult> {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("Weighted preview request is invalid");
  }
  const current = await readPersonalAssessment(session, id);
  if (!current) return { kind: "not-found" };
  if (current.version !== expectedVersion) return { kind: "conflict" };
  const preferred = preferredCapabilities(current.profile);
  if (!preferred) throw new Error("Core profile cannot be read safely");
  if (!weightsMatchPreferences(weights, preferred)) return { kind: "invalid" };
  const audit = auditabilityValues(current.profile);
  if (!audit) throw new Error("Core profile cannot be read safely");
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments/${id}/weighted-comparison-preview`, {
    method: "POST",
    headers: { ...assessmentHeaders(session), "Content-Type": "application/json" },
    body: JSON.stringify({ weights }),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 400) return { kind: "conflict" };
  if (response.status === 404) return { kind: "not-found" };
  if (response.status !== 200) throw new Error("Core weighted preview failed");
  const body: unknown = JSON.parse(await boundedPrerequisiteText(response, auditabilityPreviewByteLimit));
  const comparison = object(object(body).comparison);
  if (comparison.workspaceId === session.workspaceId && comparison.assessmentId === id &&
      Number.isSafeInteger(comparison.assessmentVersion) &&
      comparison.assessmentVersion !== expectedVersion) return { kind: "conflict" };
  return { kind: "preview", preview: weightedPreviewFromCore(body, session, id,
    expectedVersion, weights, audit) };
}

function sensitivityFromCore(value: unknown, session: BrowserSession, id: string,
  expectedVersion: number, baselineWeights: CapabilityWeights,
  alternativeWeights: CapabilityWeights, values: AuditabilityValues): SensitivityPreview {
  const body = object(value);
  exactKeys(body, ["comparison", "scoringPolicyVersion", "sensitivityPolicyVersion", "baseline",
    "alternative", "deltas", "rankingPerformed", "recommendationReady"]);
  if (body.scoringPolicyVersion !== "explicit-capability-weights-1" ||
      body.sensitivityPolicyVersion !== "explicit-weight-sensitivity-1" ||
      body.rankingPerformed !== false || body.recommendationReady !== false) {
    throw new Error("Core sensitivity response is invalid");
  }
  const baseline = object(body.baseline);
  const alternative = object(body.alternative);
  exactKeys(baseline, ["weights", "scores"]);
  exactKeys(alternative, ["weights", "scores"]);
  const scenario = (raw: Record<string, unknown>, weights: CapabilityWeights) => weightedPreviewFromCore({
    comparison: body.comparison, scoringPolicyVersion: body.scoringPolicyVersion,
    weights: raw.weights, rankingPerformed: false, recommendationReady: false, scores: raw.scores,
  }, session, id, expectedVersion, weights, values);
  const before = scenario(baseline, baselineWeights);
  const after = scenario(alternative, alternativeWeights);
  if (!Array.isArray(body.deltas) || body.deltas.length !== before.candidates.length) {
    throw new Error("Core sensitivity response is invalid");
  }
  const deltaById = new Map<string, SensitivityCandidate>();
  for (const item of body.deltas) {
    const raw = object(item);
    exactKeys(raw, ["optionId", "status", "scoreDelta", "capabilityDeltas"]);
    const first = before.candidates.find(candidate => candidate.optionId === raw.optionId);
    const second = after.candidates.find(candidate => candidate.optionId === raw.optionId);
    if (!first || !second || deltaById.has(first.optionId) ||
        first.status !== second.status || raw.status !== first.status ||
        !Array.isArray(raw.capabilityDeltas)) {
      throw new Error("Core sensitivity response is invalid");
    }
    let capabilityDeltas: SensitivityCapabilityDelta[] = [];
    if (first.status === "SCORED") {
      if (!Number.isSafeInteger(raw.scoreDelta) ||
          raw.scoreDelta !== second.score! - first.score! ||
          raw.capabilityDeltas.length !== first.contributions.length) {
        throw new Error("Core sensitivity response is invalid");
      }
      const seen = new Set<string>();
      capabilityDeltas = raw.capabilityDeltas.map((entry: unknown): SensitivityCapabilityDelta => {
        const delta = object(entry);
        exactKeys(delta, ["capability", "baselineWeight", "alternativeWeight", "outcome", "pointChange"]);
        const base = first.contributions.find(contribution => contribution.capability === delta.capability);
        const alt = second.contributions.find(contribution => contribution.capability === delta.capability);
        const capability = String(delta.capability);
        if (!base || !alt || seen.has(capability) || base.outcome !== alt.outcome ||
            delta.baselineWeight !== base.weight || delta.alternativeWeight !== alt.weight ||
            delta.outcome !== base.outcome ||
            delta.pointChange !== alt.earnedPoints - base.earnedPoints) {
          throw new Error("Core sensitivity response is invalid");
        }
        seen.add(capability);
        return {
          capability, baselineWeight: base.weight, alternativeWeight: alt.weight,
          outcome: base.outcome, pointChange: delta.pointChange as number,
        };
      });
      if (capabilityDeltas.reduce((sum, delta) => sum + delta.pointChange, 0) !== raw.scoreDelta) {
        throw new Error("Core sensitivity response is invalid");
      }
    } else if (raw.scoreDelta !== null || raw.capabilityDeltas.length !== 0 ||
        first.score !== null || second.score !== null) {
      throw new Error("Core sensitivity response is invalid");
    }
    deltaById.set(first.optionId, {
      optionId: first.optionId, displayName: first.displayName, plan: first.plan,
      region: first.region, status: first.status, baselineScore: first.score,
      alternativeScore: second.score, scoreDelta: raw.scoreDelta as number | null,
      capabilityDeltas,
    });
  }
  if (deltaById.size !== before.candidates.length) throw new Error("Core sensitivity response is invalid");
  return {
    assessmentVersion: before.assessmentVersion, catalogVersion: before.catalogVersion,
    sensitivityPolicyVersion: body.sensitivityPolicyVersion as string,
    candidates: before.candidates.map(candidate => deltaById.get(candidate.optionId)!),
  };
}

export type SensitivityPreviewResult =
  | { kind: "preview"; preview: SensitivityPreview }
  | { kind: "conflict" }
  | { kind: "invalid" }
  | { kind: "not-found" };

export async function previewPersonalWeightSensitivity(
  session: BrowserSession, id: string, expectedVersion: number,
  baselineWeights: CapabilityWeights, alternativeWeights: CapabilityWeights,
): Promise<SensitivityPreviewResult> {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("Sensitivity preview request is invalid");
  }
  const current = await readPersonalAssessment(session, id);
  if (!current) return { kind: "not-found" };
  if (current.version !== expectedVersion) return { kind: "conflict" };
  const preferred = preferredCapabilities(current.profile);
  if (!preferred) throw new Error("Core profile cannot be read safely");
  if (!weightsMatchPreferences(baselineWeights, preferred) ||
      !weightsMatchPreferences(alternativeWeights, preferred)) return { kind: "invalid" };
  const audit = auditabilityValues(current.profile);
  if (!audit) throw new Error("Core profile cannot be read safely");
  const response = await fetch(`${CORE_ORIGIN}/api/v6/workspaces/${session.workspaceId}/assessments/${id}/weight-sensitivity-preview`, {
    method: "POST",
    headers: { ...assessmentHeaders(session), "Content-Type": "application/json" },
    body: JSON.stringify({ baselineWeights, alternativeWeights }),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 400) return { kind: "conflict" };
  if (response.status === 404) return { kind: "not-found" };
  if (response.status !== 200) throw new Error("Core sensitivity preview failed");
  const body: unknown = JSON.parse(await boundedPrerequisiteText(response, auditabilityPreviewByteLimit));
  const comparison = object(object(body).comparison);
  if (comparison.workspaceId === session.workspaceId && comparison.assessmentId === id &&
      Number.isSafeInteger(comparison.assessmentVersion) &&
      comparison.assessmentVersion !== expectedVersion) return { kind: "conflict" };
  return { kind: "preview", preview: sensitivityFromCore(body, session, id,
    expectedVersion, baselineWeights, alternativeWeights, audit) };
}
