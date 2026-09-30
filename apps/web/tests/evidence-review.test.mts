import assert from "node:assert/strict";
import { test } from "node:test";
import { candidateClaimSummary, evidenceOffsetFromQuery, evidencePageFromCore,
  type CandidateClaim } from "../src/lib/catalog/evidence-review.ts";
import { candidateEvidenceFixture } from "./fixtures/candidate-evidence.mts";

const review = { proposalId: "90000000-0000-4000-8000-000000000001", version: 2,
  proposalSha256: "a".repeat(64), candidateCatalogVersion: "synthetic-candidate",
  recordedAt: "2026-09-30T12:00:00Z", rationale: "Synthetic proposal.", baseCatalogVersion: "synthetic-base",
  affectedOptionIds: [], optionChanges: [], factChanges: [] };
const page = candidateEvidenceFixture(review.proposalId, review.version, review.proposalSha256);

test("candidate evidence remains available when no fact changes are listed", () => {
  const result = evidencePageFromCore(page, review, 0);
  assert.equal(result.factCount, 1);
  assert.equal(result.items[0].path, "facts.OIDC");
  assert.equal(result.items[0].evidenceStatus, "UNREVIEWED");
  assert.deepEqual(result.items[0].claim, { kind: "CAPABILITY", availability: "OPTIONAL" });
  assert.equal(evidenceOffsetFromQuery("20"), 20);
  for (const invalid of ["-20", "21", "6801", "00", ["20"]]) assert.equal(evidenceOffsetFromQuery(invalid), 0);
});

test("all four claim kinds retain exact values beside unchanged evidence", () => {
  const cases: [string, CandidateClaim][] = [
    ["facts.SCIM", { kind: "CAPABILITY", availability: "UNAVAILABLE" }],
    ["facts.OIDC", { kind: "CAPABILITY", availability: "UNKNOWN" }],
    ["facts.MFA", { kind: "CAPABILITY", availability: "MANDATORY" }],
    ["facts.OAUTH2_APIS", { kind: "CAPABILITY", availability: "OPTIONAL" }],
    ["compatibility.applications.B2B_SAAS", { kind: "COMPATIBILITY", support: "SUPPORTED" }],
    ["compatibility.clients.BROWSER", { kind: "COMPATIBILITY", support: "UNKNOWN" }],
    ["compatibility.populations.PARTNERS", { kind: "COMPATIBILITY", support: "UNSUPPORTED" }],
    ["residency.USER_PROFILES", { kind: "RESIDENCY", coverage: "PARTIAL", storageCountries: ["DE", "FR"] }],
    ["residency.AUDIT_LOGS", { kind: "RESIDENCY", coverage: "UNKNOWN", storageCountries: [] }],
    ["authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE",
      { kind: "AUTHENTICATION_CONTROL", availability: "SUPPORTED", enforcement: "UNKNOWN" }],
  ];
  for (const [path, claim] of cases) {
    const result = evidencePageFromCore({ ...page, items: [{ ...page.items[0], path, claim }] }, review, 0);
    assert.deepEqual(result.items[0].claim, claim);
  }
});

test("claim labels distinguish unavailable, unknown, partial and unenforced values", () => {
  assert.deepEqual(candidateClaimSummary({ kind: "CAPABILITY", availability: "OPTIONAL" }),
    ["Availability: Optional (can be enabled or disabled)"]);
  assert.match(candidateClaimSummary({ kind: "CAPABILITY", availability: "UNKNOWN" })[0], /not an unavailable claim/);
  assert.deepEqual(candidateClaimSummary({ kind: "COMPATIBILITY", support: "UNSUPPORTED" }), ["Compatibility: Unsupported"]);
  assert.deepEqual(candidateClaimSummary({ kind: "RESIDENCY", coverage: "PARTIAL", storageCountries: ["DE"] }),
    ["Storage coverage: Partial (does not rule out other countries)", "Recorded storage countries: DE"]);
  assert.deepEqual(candidateClaimSummary({ kind: "AUTHENTICATION_CONTROL", availability: "SUPPORTED", enforcement: "UNKNOWN" }),
    ["Availability: Supported", "Enforcement: Unknown (not an unsupported claim)"]);
});

test("missing, mixed or path-incompatible claims close the review instead of guessing", () => {
  const invalidClaims = [undefined, null, { kind: "CAPABILITY", availability: "SUPPORTED" },
    { kind: "CAPABILITY", availability: "OPTIONAL", support: "SUPPORTED" },
    { kind: "COMPATIBILITY", support: "SUPPORTED" }, { kind: "CAPABILITY" },
    { kind: "VERIFIED", availability: "OPTIONAL" }];
  for (const claim of invalidClaims) {
    assert.throws(() => evidencePageFromCore({ ...page, items: [{ ...page.items[0], claim }] }, review, 0), /claim is invalid/);
  }
  for (const storageCountries of [["DE", "DE"], ["de"], ["not-a-country"], [null]]) {
    assert.throws(() => evidencePageFromCore({ ...page, items: [{ ...page.items[0], path: "residency.USER_PROFILES",
      claim: { kind: "RESIDENCY", coverage: "PARTIAL", storageCountries } }] }, review, 0), /claim is invalid/);
  }
  assert.throws(() => evidencePageFromCore({ ...page, policyVersion: "catalog-proposal-evidence-review-1" }, review, 0), /invalid/);
});

test("evidence pagination accepts a full first page and the remaining immutable rows", () => {
  const items = Array.from({ length: 21 }, (_, n) => ({ ...page.items[0], optionId: `example-${String(n).padStart(2, "0")}` }));
  const first = { ...page, factCount: 21, freshness: { current: 21, stale: 0, future: 0 },
    items: items.slice(0, 20), nextOffset: 20 };
  assert.equal(evidencePageFromCore(first, review, 0).nextOffset, 20);
  assert.equal(evidencePageFromCore({ ...first, offset: 20, items: items.slice(20), nextOffset: null },
    review, 20).items.length, 1);
});

test("evidence pages reject mismatched snapshots, forged authority, counts and pagination", () => {
  for (const invalid of [{ ...page, proposalVersion: 3 }, { ...page, proposalSha256: "b".repeat(64) },
    { ...page, sourceVerificationPerformed: true }, { ...page, approvalGranted: true },
    { ...page, factCount: 2 }, { ...page, nextOffset: 20 },
    { ...page, items: [{ ...page.items[0], evidenceStatus: "REVIEWED" }] },
    { ...page, items: [{ ...page.items[0], evidence: { ...page.items[0].evidence,
      sourceUrl: "https://user:password@example.invalid/" } }] }]) {
    assert.throws(() => evidencePageFromCore(invalid, review, 0), /invalid/);
  }
});
