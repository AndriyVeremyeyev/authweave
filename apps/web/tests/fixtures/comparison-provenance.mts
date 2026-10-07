import { createHash } from "node:crypto";
import { comparisonAuditFixture, noAuditRequirement } from "./comparison-auditability.mts";

// Independent wire-fixture canonicalization: sort object keys and JSON values in unordered arrays.
export function fixtureCatalogDigest(value: unknown): string {
  function json(v: unknown): string {
    if (Array.isArray(v)) return "[" + v.map(json).sort().join(",") + "]";
    if (v !== null && typeof v === "object") return "{" + Object.keys(v).sort().map(k => JSON.stringify(k) + ":" + json((v as Record<string, unknown>)[k])).join(",") + "}";
    return JSON.stringify(v);
  }
  return createHash("sha256").update(json(value)).digest("hex");
}
export const provenanceBinding = { workspaceId: "70000000-0000-4000-8000-000000000001",
  assessmentId: "80000000-0000-4000-8000-000000000001", expectedVersion: 2, values: noAuditRequirement };
export function provenanceFixture() {
  const fact = { evidenceStatus: "REVIEWED", sourceUrl: "https://catalog.invalid/fictional-plan", observedAt: "2026-10-02T12:00:00.123456789Z" };
  const catalog = { schemaVersion: 4, catalogVersion: "synthetic-provenance-fixture", kind: "SYNTHETIC", options: [{
    id: "fictional-plan", displayName: "Fictional Plan", plan: "Demo", region: "Synthetic region",
    facts: { OIDC: { ...fact, availability: "OPTIONAL" }, SCIM: { ...fact, availability: "UNKNOWN", evidenceStatus: "UNREVIEWED" } },
    compatibility: { applications: { B2B_SAAS: { ...fact, support: "SUPPORTED" } }, clients: {}, populations: {}, tenancy: {}, membership: {} },
    residency: { USER_PROFILES: { ...fact, coverage: "PARTIAL", storageCountries: ["DE", "US"] } },
    authenticationControls: { BROWSER: { EMPLOYEES: { PHISHING_RESISTANCE: { ...fact, availability: "SUPPORTED", enforcement: "UNKNOWN" } } } },
  }] };
  const comparison = comparisonAuditFixture({ workspaceId: provenanceBinding.workspaceId, assessmentId: provenanceBinding.assessmentId,
    assessmentVersion: 2, catalogVersion: catalog.catalogVersion,
    catalogKind: "SYNTHETIC", preferencePolicyVersion: "capability-preference-1", evaluatedAt: fact.observedAt,
    scope: "SYNTHETIC_UNRANKED_COMPARISON", recommendationReady: false, rankingPerformed: false,
    deferredPaths: ["security.browserTokenExposureMinimization", "security.auditability", "security.assurance", "security.complianceTargets", "operations"],
    candidates: [{ optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo", region: "Synthetic region", hardVerdict: "UNRESOLVED",
      exclusionReasons: [], informationGaps: [{ dimension: "COVERAGE", profilePath: "assessment", reasonCode: "NO_AFFIRMATIVE_CHECKS", explanation: "No affirmative checks." }],
      capabilityPreferences: [{ capability: "OIDC", profilePath: "protocols.federation.OIDC", outcome: "AVAILABLE", reasonCode: "PREFERRED_CAPABILITY_AVAILABLE",
        explanation: "Fictional OIDC preference.", evidence: { ...catalog.options[0].facts.OIDC } }] }],
  });
  return { schemaVersion: 1, scope: "SYNTHETIC_COMPARISON_EVIDENCE", policyVersion: "comparison-evidence-preview-1",
    catalogSha256: fixtureCatalogDigest(catalog), comparison, catalog,
    sourceVerificationPerformed: false, publicationReady: false, recommendationReady: false, writesPerformed: false };
}
