import { comparisonEvidenceFromCore } from "../../src/lib/assessment/comparison-provenance.ts";
import { comparisonAuditFixture } from "./comparison-auditability.mts";
import { provenanceFixture, provenanceBinding, fixtureCatalogDigest } from "./comparison-provenance.mts";

// Declared fictional wire fixture, not a Core calculation or real provider baseline.
export function comparisonMatrixFixture() {
  const raw = provenanceFixture(), option = raw.catalog.options[0], candidate = raw.comparison.candidates[0];
  raw.catalog.options = ["A", "B", "C", "D"].map((suffix, index) => ({ ...structuredClone(option),
    id: `fictional-${suffix.toLowerCase()}`, displayName: `Fictional Plan ${suffix}`, plan: `Demo ${index + 1}`, region: `Synthetic region ${suffix}` }));
  Object.assign(raw.catalog.options[1].facts.SCIM, { availability: "OPTIONAL", evidenceStatus: "REVIEWED", observedAt: "2026-01-01T00:00:00Z" });
  Object.assign(raw.catalog.options[2].facts.SCIM, { availability: "MANDATORY", evidenceStatus: "REVIEWED", observedAt: "2026-10-03T00:00:00Z" });
  Reflect.deleteProperty(raw.catalog.options[3].facts, "SCIM");
  const comparison = comparisonAuditFixture({ ...raw.comparison, candidates: raw.catalog.options.map((o, index) => ({ ...structuredClone(candidate),
    optionId: o.id, displayName: o.displayName, plan: o.plan, region: o.region,
    exclusionReasons: index === 0 ? [{ dimension: "CAPABILITY", profilePath: "provisioning.scim", reasonCode: "REQUIRED_CAPABILITY_UNAVAILABLE", explanation: "Declared fictional hard-check mismatch." }] : [],
    informationGaps: index === 2 ? [] : structuredClone(candidate.informationGaps),
  })) });
  raw.catalogSha256 = fixtureCatalogDigest(raw.catalog);
  const projected = comparisonEvidenceFromCore({ ...raw, comparison }, provenanceBinding);
  return { comparison: projected.comparison, evidence: projected.evidence };
}
