import { auditCriteria } from "./auditability.ts";
import { auditabilityPreviewBinding, auditabilityPreviewFromCore, auditabilityReasons,
  type AuditabilityPreviewBinding } from "./auditability-preview.ts";

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
  auditabilityEvidenceVersion: string;
  evaluatedAt: string;
  deferredPaths: string[];
  candidates: ComparisonCandidate[];
};

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
    if (!(["CAPABILITY", "CONTEXT", "RESIDENCY", "AUTHENTICATION_CONTROL", "COMPLIANCE_SCOPE", "COVERAGE", "AUDITABILITY"] as unknown[]).includes(finding.dimension)) {
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

/** Pure personal v6 binding; does not promote facts or infer a provider recommendation. */
export function comparisonFromCore(value: unknown, input: AuditabilityPreviewBinding): SyntheticComparisonSummary {
  const binding = auditabilityPreviewBinding(input.workspaceId, input.assessmentId, input.expectedVersion, input.values);
  const body = object(value);
  exactKeys(body, ["workspaceId", "assessmentId", "assessmentVersion", "catalogVersion", "catalogKind",
    "policyVersion", "hardConstraintPolicyVersion", "preferencePolicyVersion", "evaluatedAt", "scope",
    "recommendationReady", "rankingPerformed", "deferredPaths", "candidates", "auditability"]);
  if (body.workspaceId !== binding.workspaceId || body.assessmentId !== binding.assessmentId ||
      body.assessmentVersion !== binding.expectedVersion || body.catalogKind !== "SYNTHETIC" ||
      body.policyVersion !== "synthetic-comparison-2" ||
      body.hardConstraintPolicyVersion !== "hard-constraint-preflight-2" ||
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
  const auditability = auditabilityPreviewFromCore(body.auditability, binding);
  if (auditability.baseCatalogVersion !== body.catalogVersion || auditability.evaluatedAt !== evaluatedAt ||
      auditability.candidates.length !== body.candidates.length) throw new Error("Core comparison response is invalid");
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
  for (const candidate of candidates) {
    const matches = auditability.candidates.filter(c => c.scope.optionId === candidate.optionId);
    if (matches.length !== 1) throw new Error("Core comparison response is invalid");
    const scoped = matches[0];
    if (scoped.displayName !== candidate.displayName || scoped.scope.plan !== candidate.plan ||
        scoped.scope.region !== candidate.region) throw new Error("Core comparison response is invalid");
    const bindFindings = (items: ComparisonFinding[], outcome: "FAIL" | "UNKNOWN") => {
      const expected = scoped.checks.filter(c => c.outcome === outcome);
      const actual = items.filter(f => f.dimension === "AUDITABILITY");
      if (actual.length !== expected.length) throw new Error("Core comparison response is invalid");
      expected.forEach((check, index) => {
        const finding = actual[index];
        const path = ["REQUIREMENT_UNKNOWN", "AUDIT_INTENT_UNCLEAR"].includes(check.reasonCode)
          ? "security.auditability" : "security.auditabilityRequirements";
        if (finding.reasonCode !== check.reasonCode || finding.profilePath !== path ||
            !finding.explanation.startsWith(check.criterion + ": ")) throw new Error("Core comparison response is invalid");
        const label = auditCriteria.find(c => c.key === check.criterion)!.label;
        // Render fixed educational copy from the replayed reason, not arbitrary nested claim text.
        finding.explanation = label + ": " + (check.reasonCode === "RETENTION_BELOW_MINIMUM"
          ? "The documented provider minimum of " + check.documentedMinimumRetentionDays + " days is below your requested " +
            binding.values.minimumRetentionDays + " days. Deployed retention is not verified."
          : auditabilityReasons[check.reasonCode]);
      });
    };
    bindFindings(candidate.exclusionReasons, "FAIL");
    bindFindings(candidate.informationGaps, "UNKNOWN");
    const expectedVerdict = candidate.exclusionReasons.length > 0 ? "EXCLUDED" :
      candidate.informationGaps.length > 0 ? "UNRESOLVED" : "PASSES_CHECKED_REQUIREMENTS";
    if (candidate.hardVerdict !== expectedVerdict || scoped.checks.some(c => c.outcome === "PASS") &&
        candidate.informationGaps.some(f => f.dimension === "COVERAGE" && f.reasonCode === "NO_AFFIRMATIVE_CHECKS"))
      throw new Error("Core comparison response is invalid");
  }
  return {
    assessmentVersion: binding.expectedVersion,
    auditabilityEvidenceVersion: auditability.evidenceVersion,
    catalogVersion: boundedText(body.catalogVersion, 100),
    evaluatedAt,
    deferredPaths: [...body.deferredPaths] as string[],
    candidates,
  };
}
