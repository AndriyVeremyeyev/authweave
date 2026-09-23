// This local-only server-to-server call never exposes its credential to the browser.
import type { BrowserSession } from "./store.ts";
import { withCapabilityValues, type CapabilityValues } from "../assessment/capabilities.ts";
import { withEvaluationContextValues, type EvaluationContextValues } from "../assessment/evaluation-context.ts";
import { preferredCapabilities, weightsMatchPreferences, type CapabilityWeights,
  type SensitivityCapabilityDelta, type SensitivityCandidate, type SensitivityPreview,
  type WeightedCandidate, type WeightedContribution, type WeightedPreview } from "../assessment/weights.ts";

const CORE_ORIGIN = "http://127.0.0.1:8080";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export type PersonalAssessment = {
  id: string;
  status: "DRAFT" | "READY_FOR_EVALUATION" | "EVALUATED" | "DECIDED" | "ARCHIVED";
  version: number;
  profile: Record<string, unknown>;
};

export type PersonalAssessmentListItem = Omit<PersonalAssessment, "profile"> & {
  createdAt: string;
  updatedAt: string;
};

export type PersonalAssessmentListPage = {
  items: PersonalAssessmentListItem[];
  nextBeforeId: string | null;
};

export type ComparisonFinding = {
  dimension: string;
  profilePath: string;
  reasonCode: string;
  explanation: string;
};

export type ComparisonPreference = {
  capability: string;
  profilePath: string;
  outcome: "AVAILABLE" | "UNAVAILABLE" | "UNKNOWN";
  reasonCode: string;
  explanation: string;
};

export type ComparisonCandidate = {
  optionId: string;
  displayName: string;
  plan: string;
  region: string;
  hardVerdict: "EXCLUDED" | "UNRESOLVED" | "PASSES_CHECKED_REQUIREMENTS";
  exclusionReasons: ComparisonFinding[];
  informationGaps: ComparisonFinding[];
  capabilityPreferences: ComparisonPreference[];
};

export type SyntheticComparisonSummary = {
  assessmentVersion: number;
  catalogVersion: string;
  evaluatedAt: string;
  deferredPaths: string[];
  candidates: ComparisonCandidate[];
};

function serviceToken(): string {
  const token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  if (!token || token.length < 32) {
    throw new Error("Core service credential is not configured");
  }
  return token;
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
      !Number.isSafeInteger(body.version) || Number(body.version) < 0 || body.profileSchemaVersion !== 5 ||
      !body.profile || typeof body.profile !== "object" || Array.isArray(body.profile)) {
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
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments`, {
    method: "POST", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 201) throw new Error("Core assessment creation failed");
  return assessmentFromCore(await response.json(), session);
}

export async function readPersonalAssessment(session: BrowserSession, id: string): Promise<PersonalAssessment | null> {
  if (!UUID.test(id)) throw new Error("Assessment ID is invalid");
  const headers = assessmentHeaders(session);
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments/${id}`, {
    method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 404) return null;
  if (response.status !== 200) throw new Error("Core assessment read failed");
  return assessmentFromCore(await response.json(), session, id);
}

export type ProfileUpdateResult = "saved" | "conflict" | "invalid" | "not-found" | "not-editable";

async function updatePersonalProfile(session: BrowserSession, id: string, expectedVersion: number,
  patch: (profile: Record<string, unknown>) => Record<string, unknown>): Promise<ProfileUpdateResult> {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("Profile update request is invalid");
  }
  const current = await readPersonalAssessment(session, id);
  if (!current) return "not-found";
  if (current.status !== "DRAFT") return "not-editable";
  if (current.version !== expectedVersion) return "conflict";
  const profile = patch(current.profile);
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments/${id}/profile`, {
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
      saved.version > expectedVersion + 1) {
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
    profile => withEvaluationContextValues(profile, values));
}

export async function listPersonalAssessments(
  session: BrowserSession, beforeId?: string,
): Promise<PersonalAssessmentListPage> {
  if (beforeId !== undefined && !UUID.test(beforeId)) throw new Error("Assessment cursor is invalid");
  const headers = assessmentHeaders(session);
  const url = new URL(`/api/v5/workspaces/${session.workspaceId}/assessments`, CORE_ORIGIN);
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
  if (Object.keys(page).some((key) => !["items", "nextBeforeId"].includes(key)) ||
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
    if (Object.keys(item).some((key) => !["id", "status", "version", "createdAt", "updatedAt"].includes(key)) ||
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
    };
  });
  if (page.nextBeforeId !== null &&
      (items.length === 0 || items.at(-1)?.id !== page.nextBeforeId)) {
    throw new Error("Core assessment list response is invalid");
  }
  return { items, nextBeforeId: page.nextBeforeId };
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

function findings(value: unknown): ComparisonFinding[] {
  if (!Array.isArray(value) || value.length > 500) throw new Error("Core comparison response is invalid");
  return value.map(item => {
    const finding = object(item);
    exactKeys(finding, ["dimension", "profilePath", "reasonCode", "explanation"]);
    if (!(["CAPABILITY", "CONTEXT", "RESIDENCY", "AUTHENTICATION_CONTROL", "COMPLIANCE_SCOPE", "COVERAGE"] as unknown[]).includes(finding.dimension)) {
      throw new Error("Core comparison response is invalid");
    }
    return {
      dimension: finding.dimension as string,
      profilePath: boundedText(finding.profilePath, 200),
      reasonCode: boundedText(finding.reasonCode, 100),
      explanation: boundedText(finding.explanation, 1000),
    };
  });
}

function preferences(value: unknown): ComparisonPreference[] {
  if (!Array.isArray(value) || value.length > 9) throw new Error("Core comparison response is invalid");
  const seen = new Set<string>();
  return value.map(item => {
    const preference = object(item);
    exactKeys(preference, ["capability", "profilePath", "outcome", "reasonCode", "explanation", "evidence"]);
    const capability = boundedText(preference.capability, 100);
    if (!(["OIDC", "SAML", "OAUTH2_APIS", "SOCIAL_LOGIN", "ENTERPRISE_SSO", "SCIM", "JIT", "GROUP_SYNC", "MFA"] as string[]).includes(capability) ||
        seen.has(capability) ||
        !(["AVAILABLE", "UNAVAILABLE", "UNKNOWN"] as unknown[]).includes(preference.outcome) ||
        (preference.evidence !== null && (typeof preference.evidence !== "object" || Array.isArray(preference.evidence)))) {
      throw new Error("Core comparison response is invalid");
    }
    seen.add(capability);
    return {
      capability,
      profilePath: boundedText(preference.profilePath, 200),
      outcome: preference.outcome as ComparisonPreference["outcome"],
      reasonCode: boundedText(preference.reasonCode, 100),
      explanation: boundedText(preference.explanation, 300),
    };
  });
}

function comparisonFromCore(value: unknown, session: BrowserSession, id: string,
  expectedVersion: number): SyntheticComparisonSummary {
  const body = object(value);
  exactKeys(body, ["workspaceId", "assessmentId", "assessmentVersion", "catalogVersion", "catalogKind",
    "policyVersion", "hardConstraintPolicyVersion", "preferencePolicyVersion", "evaluatedAt", "scope",
    "recommendationReady", "rankingPerformed", "deferredPaths", "candidates"]);
  if (body.workspaceId !== session.workspaceId || body.assessmentId !== id ||
      body.assessmentVersion !== expectedVersion || body.catalogKind !== "SYNTHETIC" ||
      body.policyVersion !== "synthetic-comparison-1" ||
      body.hardConstraintPolicyVersion !== "hard-constraint-preflight-1" ||
      body.preferencePolicyVersion !== "capability-preference-1" ||
      body.scope !== "SYNTHETIC_UNRANKED_COMPARISON" ||
      body.recommendationReady !== false || body.rankingPerformed !== false ||
      !Array.isArray(body.deferredPaths) || body.deferredPaths.length < 1 ||
      !body.deferredPaths.every(path => typeof path === "string" && path.length > 0) ||
      !Array.isArray(body.candidates) || body.candidates.length < 1 || body.candidates.length > 100) {
    throw new Error("Core comparison response is invalid");
  }
  const evaluatedAt = boundedText(body.evaluatedAt, 100);
  if (Number.isNaN(Date.parse(evaluatedAt))) throw new Error("Core comparison response is invalid");
  const seen = new Set<string>();
  const candidates = body.candidates.map((item: unknown): ComparisonCandidate => {
    const candidate = object(item);
    exactKeys(candidate, ["optionId", "displayName", "plan", "region", "hardVerdict",
      "exclusionReasons", "informationGaps", "capabilityPreferences"]);
    const optionId = boundedText(candidate.optionId, 100);
    if (!/^[a-z0-9][a-z0-9.-]{0,99}$/.test(optionId) || seen.has(optionId) ||
        !(["EXCLUDED", "UNRESOLVED", "PASSES_CHECKED_REQUIREMENTS"] as unknown[]).includes(candidate.hardVerdict)) {
      throw new Error("Core comparison response is invalid");
    }
    seen.add(optionId);
    const exclusionReasons = findings(candidate.exclusionReasons);
    const informationGaps = findings(candidate.informationGaps);
    const hardVerdict = candidate.hardVerdict as ComparisonCandidate["hardVerdict"];
    if ((hardVerdict === "EXCLUDED" && exclusionReasons.length === 0) ||
        (hardVerdict === "UNRESOLVED" && informationGaps.length === 0) ||
        (hardVerdict === "PASSES_CHECKED_REQUIREMENTS" && (exclusionReasons.length > 0 || informationGaps.length > 0))) {
      throw new Error("Core comparison response is invalid");
    }
    return {
      optionId,
      displayName: boundedText(candidate.displayName, 120),
      plan: boundedText(candidate.plan, 120),
      region: boundedText(candidate.region, 120),
      hardVerdict,
      exclusionReasons,
      informationGaps,
      capabilityPreferences: preferences(candidate.capabilityPreferences),
    };
  });
  return {
    assessmentVersion: expectedVersion,
    catalogVersion: boundedText(body.catalogVersion, 100),
    evaluatedAt,
    deferredPaths: [...body.deferredPaths] as string[],
    candidates,
  };
}

export async function readSyntheticComparison(session: BrowserSession, id: string,
  expectedVersion: number): Promise<SyntheticComparisonSummary> {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("Comparison request is invalid");
  }
  const headers = assessmentHeaders(session);
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments/${id}/comparison-preflight`, {
    method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 200) throw new Error("Core comparison read failed");
  return comparisonFromCore(await response.json(), session, id, expectedVersion);
}

function weightedPreviewFromCore(value: unknown, session: BrowserSession, id: string,
  expectedVersion: number, weights: CapabilityWeights): WeightedPreview {
  const body = object(value);
  exactKeys(body, ["comparison", "scoringPolicyVersion", "weights", "rankingPerformed", "recommendationReady", "scores"]);
  const comparison = comparisonFromCore(body.comparison, session, id, expectedVersion);
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
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments/${id}/weighted-comparison-preview`, {
    method: "POST",
    headers: { ...assessmentHeaders(session), "Content-Type": "application/json" },
    body: JSON.stringify({ weights }),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 400) return { kind: "conflict" };
  if (response.status === 404) return { kind: "not-found" };
  if (response.status !== 200) throw new Error("Core weighted preview failed");
  const body: unknown = await response.json();
  const comparison = object(object(body).comparison);
  if (comparison.workspaceId === session.workspaceId && comparison.assessmentId === id &&
      Number.isSafeInteger(comparison.assessmentVersion) &&
      comparison.assessmentVersion !== expectedVersion) return { kind: "conflict" };
  return { kind: "preview", preview: weightedPreviewFromCore(body, session, id,
    expectedVersion, weights) };
}

function sensitivityFromCore(value: unknown, session: BrowserSession, id: string,
  expectedVersion: number, baselineWeights: CapabilityWeights,
  alternativeWeights: CapabilityWeights): SensitivityPreview {
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
  }, session, id, expectedVersion, weights);
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
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments/${id}/weight-sensitivity-preview`, {
    method: "POST",
    headers: { ...assessmentHeaders(session), "Content-Type": "application/json" },
    body: JSON.stringify({ baselineWeights, alternativeWeights }),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 400) return { kind: "conflict" };
  if (response.status === 404) return { kind: "not-found" };
  if (response.status !== 200) throw new Error("Core sensitivity preview failed");
  const body: unknown = await response.json();
  const comparison = object(object(body).comparison);
  if (comparison.workspaceId === session.workspaceId && comparison.assessmentId === id &&
      Number.isSafeInteger(comparison.assessmentVersion) &&
      comparison.assessmentVersion !== expectedVersion) return { kind: "conflict" };
  return { kind: "preview", preview: sensitivityFromCore(body, session, id,
    expectedVersion, baselineWeights, alternativeWeights) };
}
