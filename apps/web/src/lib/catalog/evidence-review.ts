import type { CatalogProposalReview } from "./proposal-review.ts";

type Support = "SUPPORTED" | "UNSUPPORTED" | "UNKNOWN";
export type CandidateClaim =
  | { kind: "CAPABILITY"; availability: "OPTIONAL" | "MANDATORY" | "UNAVAILABLE" | "UNKNOWN" }
  | { kind: "COMPATIBILITY"; support: Support }
  | { kind: "RESIDENCY"; coverage: "COMPLETE" | "PARTIAL" | "UNKNOWN"; storageCountries: string[] }
  | { kind: "AUTHENTICATION_CONTROL"; availability: Support; enforcement: Support };

export type CandidateEvidenceItem = {
  optionId: string; path: string;
  claim: CandidateClaim;
  scope: { providerId: string; product: string; plan: string; deployment: "MANAGED" | "SELF_HOSTED";
    region: string; configuration: string };
  evidenceStatus: "UNREVIEWED"; freshness: "CURRENT" | "STALE" | "FUTURE";
  conditions: string[];
  evidence: { sourceUrl: string; observedAt: string; summary: string };
};
export type CandidateEvidencePage = {
  evaluatedAt: string; factCount: number; freshness: { current: number; stale: number; future: number };
  offset: number; items: CandidateEvidenceItem[]; nextOffset: number | null;
};

export function evidenceOffsetFromQuery(value: unknown): number {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]*)$/.test(value)) return 0;
  const offset = Number(value);
  return Number.isSafeInteger(offset) && offset <= 6800 && offset % 20 === 0 ? offset : 0;
}
function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function text(value: unknown, max: number): value is string {
  return typeof value === "string" && value.trim().length > 0 && [...value].length <= max;
}
function count(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= 6800;
}
function instant(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function claimFromCore(value: unknown, path: string): CandidateClaim {
  const claim = object(value);
  const support = (value: unknown): value is Support =>
    typeof value === "string" && ["SUPPORTED", "UNSUPPORTED", "UNKNOWN"].includes(value);
  const keys = (expected: string[]) => claim && Object.keys(claim).length === expected.length &&
    expected.every(key => Object.hasOwn(claim, key));
  if (claim?.kind === "CAPABILITY" && /^facts\.[A-Z0-9_]+$/.test(path) && keys(["kind", "availability"]) &&
      ["OPTIONAL", "MANDATORY", "UNAVAILABLE", "UNKNOWN"].includes(String(claim.availability))) {
    return { kind: claim.kind, availability: claim.availability as Extract<CandidateClaim, { kind: "CAPABILITY" }>["availability"] };
  }
  if (claim?.kind === "COMPATIBILITY" &&
      /^compatibility\.(applications|clients|populations|tenancy|membership)\.[A-Z0-9_]+$/.test(path) &&
      keys(["kind", "support"]) && support(claim.support)) {
    return { kind: claim.kind, support: claim.support };
  }
  if (claim?.kind === "RESIDENCY" && /^residency\.[A-Z0-9_]+$/.test(path) &&
      keys(["kind", "coverage", "storageCountries"]) && ["COMPLETE", "PARTIAL", "UNKNOWN"].includes(String(claim.coverage)) &&
      Array.isArray(claim.storageCountries) && claim.storageCountries.length <= 249 &&
      claim.storageCountries.every(v => typeof v === "string" && /^[A-Z]{2}$/.test(v)) &&
      new Set(claim.storageCountries).size === claim.storageCountries.length) {
    return { kind: claim.kind, coverage: claim.coverage as Extract<CandidateClaim, { kind: "RESIDENCY" }>["coverage"],
      storageCountries: [...claim.storageCountries] };
  }
  if (claim?.kind === "AUTHENTICATION_CONTROL" && /^authenticationControls\.[A-Z0-9_]+\.[A-Z0-9_]+\.[A-Z0-9_]+$/.test(path) &&
      keys(["kind", "availability", "enforcement"]) && support(claim.availability) && support(claim.enforcement)) {
    return { kind: claim.kind, availability: claim.availability, enforcement: claim.enforcement };
  }
  throw new Error("Core candidate claim is invalid");
}

/** Labels describe submitted values, not source verification or a provider verdict. */
export function candidateClaimSummary(claim: CandidateClaim): string[] {
  const supportLabels: Record<Support, string> = {
    SUPPORTED: "Supported", UNSUPPORTED: "Unsupported", UNKNOWN: "Unknown (not an unsupported claim)",
  };
  switch (claim.kind) {
    case "CAPABILITY": return [`Availability: ${ {
      OPTIONAL: "Optional (can be enabled or disabled)", MANDATORY: "Mandatory (cannot be disabled)",
      UNAVAILABLE: "Unavailable", UNKNOWN: "Unknown (not an unavailable claim)",
    }[claim.availability]}`];
    case "COMPATIBILITY": return [`Compatibility: ${supportLabels[claim.support]}`];
    case "RESIDENCY": return [
      `Storage coverage: ${ { COMPLETE: "Complete", PARTIAL: "Partial (does not rule out other countries)",
        UNKNOWN: "Unknown (no complete country claim)" }[claim.coverage]}`,
      `Recorded storage countries: ${claim.storageCountries.length ? claim.storageCountries.join(", ") : "None recorded"}`,
    ];
    case "AUTHENTICATION_CONTROL": return [`Availability: ${supportLabels[claim.availability]}`,
      `Enforcement: ${supportLabels[claim.enforcement]}`];
  }
}

/** Accept a bounded, unverified evidence page bound to the displayed revision and digest. */
export function evidencePageFromCore(value: unknown, review: CatalogProposalReview,
  offset: number): CandidateEvidencePage {
  const body = object(value);
  const totals = object(body?.freshness);
  if (!body || body.proposalId !== review.proposalId || body.proposalVersion !== review.version ||
      body.proposalSha256 !== review.proposalSha256 || body.catalogVersion !== review.candidateCatalogVersion ||
      body.policyVersion !== "catalog-proposal-evidence-review-2" || body.maxEvidenceAgeDays !== 90 ||
      body.sourceVerificationPerformed !== false || body.approvalGranted !== false ||
      body.writesPerformed !== false || body.evaluationReady !== false || !instant(body.evaluatedAt) ||
      !count(body.factCount) || !totals || !count(totals.current) || !count(totals.stale) || !count(totals.future) ||
      totals.current + totals.stale + totals.future !== body.factCount || body.offset !== offset ||
      !Array.isArray(body.items) || body.items.length !== Math.min(20, Math.max(body.factCount - offset, 0))) {
    throw new Error("Core candidate evidence page is invalid");
  }
  const nextOffset = offset + body.items.length < body.factCount ? offset + body.items.length : null;
  if (body.nextOffset !== nextOffset) throw new Error("Core candidate evidence cursor is invalid");
  let previousKey = "";
  const items = body.items.map((value: unknown): CandidateEvidenceItem => {
    const row = object(value); const scope = object(row?.scope); const evidence = object(row?.evidence);
    if (!row || !text(row.optionId, 100) || !text(row.path, 200) || !scope || !text(scope.providerId, 100) ||
        !text(scope.product, 120) || !text(scope.plan, 120) || !text(scope.region, 120) ||
        !text(scope.configuration, 120) || !["MANAGED", "SELF_HOSTED"].includes(String(scope.deployment)) ||
        row.evidenceStatus !== "UNREVIEWED" || !["CURRENT", "STALE", "FUTURE"].includes(String(row.freshness)) ||
        !Array.isArray(row.conditions) || row.conditions.length > 10 || !row.conditions.every(v => text(v, 500)) ||
        new Set(row.conditions).size !== row.conditions.length || !evidence || !text(evidence.sourceUrl, 2048) ||
        !/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]+)?(?:[/?#][^\s]*)?$/.test(evidence.sourceUrl) ||
        !instant(evidence.observedAt) || !text(evidence.summary, 1000)) {
      throw new Error("Core candidate evidence item is invalid");
    }
    const key = `${row.optionId}\0${row.path}`;
    if (key <= previousKey) throw new Error("Core candidate evidence order is invalid");
    previousKey = key;
    return { optionId: row.optionId, path: row.path, claim: claimFromCore(row.claim, row.path),
      scope: { providerId: scope.providerId, product: scope.product, plan: scope.plan,
        deployment: scope.deployment as CandidateEvidenceItem["scope"]["deployment"],
        region: scope.region, configuration: scope.configuration },
      evidenceStatus: "UNREVIEWED", freshness: row.freshness as CandidateEvidenceItem["freshness"],
      conditions: row.conditions as string[], evidence: { sourceUrl: evidence.sourceUrl,
        observedAt: evidence.observedAt, summary: evidence.summary } };
  });
  return { evaluatedAt: body.evaluatedAt, factCount: body.factCount,
    freshness: { current: totals.current, stale: totals.stale, future: totals.future },
    offset, items, nextOffset };
}
