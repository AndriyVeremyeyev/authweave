import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { comparisonEvidenceFromCore, comparisonEvidenceByteLimit } from "../src/lib/assessment/comparison-provenance.ts";
import { readComparisonEvidence } from "../src/lib/auth/core-client.ts";
import { provenanceFixture, provenanceBinding, fixtureCatalogDigest } from "./fixtures/comparison-provenance.mts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

test("exact comparison evidence preserves verdict and all five scoped families, including explicitly missing facts", () => {
  const raw = provenanceFixture(), before = structuredClone(raw), preview = comparisonEvidenceFromCore(raw, provenanceBinding);
  assert.deepEqual(raw, before);
  assert.deepEqual(preview.comparison.candidates.map(c => c.hardVerdict), ["UNRESOLVED"]);
  assert.deepEqual(preview.evidence[0].groups.map(g => g.rows.length), [9, 19, 4, 36, 6]);
  const facts = preview.evidence[0].groups[0].rows;
  assert.equal(facts.find(f => f.path === "facts.OIDC")?.gate, "CURRENT");
  assert.equal(facts.find(f => f.path === "facts.SCIM")?.gate, "UNREVIEWED");
  assert.equal(facts.find(f => f.path === "facts.SAML")?.gate, "MISSING");
  assert.equal(preview.evidence[0].groups[2].rows[0].claim, "PARTIAL · DE, US");
  assert.equal(preview.evidence[0].groups[3].rows.find(r => r.path.endsWith("EMPLOYEES.PHISHING_RESISTANCE"))?.claim, "Availability: SUPPORTED · Enforcement capability: UNKNOWN");
  raw.catalog.options[0].facts.OIDC.sourceUrl = "https://catalog.invalid/changed";
  assert.equal(facts[0].sourceUrl, "https://catalog.invalid/fictional-plan");
  for (const field of ["workspaceId", "subject", "actor", "sourceBody", "Authorization", "approvalGranted"]) assert.equal(field in preview, false);
});

test("review/date gates preserve nanoseconds at exactly 90 days and distinguish stale, future and unknown claims", () => {
  for (const [observedAt, gate] of [
    ["2026-07-04T12:00:00.123456789Z", "CURRENT"], ["2026-07-04T12:00:00.123456788Z", "STALE"],
    ["2026-10-02T12:00:00.123456790Z", "FUTURE"],
  ]) {
    const raw = provenanceFixture(); raw.catalog.options[0].facts.SCIM.observedAt = observedAt;
    raw.catalog.options[0].facts.SCIM.evidenceStatus = "REVIEWED"; raw.catalogSha256 = fixtureCatalogDigest(raw.catalog);
    const row = comparisonEvidenceFromCore(raw, provenanceBinding).evidence[0].groups[0].rows.find(r => r.path === "facts.SCIM")!;
    assert.equal(row.gate, gate); assert.equal(row.claim, "UNKNOWN", "Date/review gates do not promote an unknown value.");
  }
  const raw = provenanceFixture(); raw.catalog.options[0].facts.SCIM.observedAt = "2026-10-03T00:00:00Z";
  raw.catalogSha256 = fixtureCatalogDigest(raw.catalog);
  assert.equal(comparisonEvidenceFromCore(raw, provenanceBinding).evidence[0].groups[0].rows.find(r => r.path === "facts.SCIM")?.gate, "UNREVIEWED");
});

test("guard rejects foreign scopes, changed catalog content, preference-source substitution and real/unsafe source URLs", () => {
  const mutations: ((raw: ReturnType<typeof provenanceFixture>) => void)[] = [
    r => r.comparison.assessmentVersion++, r => r.catalog.catalogVersion = "synthetic-other", r => r.catalog.kind = "PUBLISHED",
    r => r.catalogSha256 = "a".repeat(64), r => r.catalog.options[0].plan = "Foreign plan",
    r => r.catalog.options[0].region = "Foreign region", r => r.catalog.options[0].displayName = "Foreign name",
    r => r.catalog.options.push(structuredClone(r.catalog.options[0])), r => r.catalog.options.pop(),
    r => r.comparison.auditability.candidates[0].evidence[0].scope.configuration = "Foreign configuration",
    r => r.catalog.options[0].facts.OIDC.sourceUrl = "https://catalog.invalid/different-source",
  ];
  for (const mutate of mutations) {
    const raw = provenanceFixture(); mutate(raw);
    if (raw.catalogSha256 !== "a".repeat(64)) raw.catalogSha256 = fixtureCatalogDigest(raw.catalog);
    assert.throws(() => comparisonEvidenceFromCore(raw, provenanceBinding));
  }
  for (const sourceUrl of ["https://real-provider.example/docs", "http://catalog.invalid/doc", "https://name:password@catalog.invalid/doc", "javascript:alert(1)", "https://catalog.invalid/doc\nsecret"])
    assert.throws(() => { const raw = provenanceFixture(); raw.catalog.options[0].facts.SCIM.sourceUrl = sourceUrl; raw.catalogSha256 = fixtureCatalogDigest(raw.catalog); comparisonEvidenceFromCore(raw, provenanceBinding); });
  for (const field of ["subject", "profile", "sourceBody", "token"])
    assert.throws(() => comparisonEvidenceFromCore({ ...provenanceFixture(), [field]: "private" }, provenanceBinding));
  for (const field of ["sourceVerificationPerformed", "publicationReady", "recommendationReady", "writesPerformed"])
    assert.throws(() => comparisonEvidenceFromCore({ ...provenanceFixture(), [field]: true }, provenanceBinding));
});

test("strict fact shapes do not accept malformed claims, countries, dates, extra fields or unknown scope keys", () => {
  const catalogMutations: ((raw: ReturnType<typeof provenanceFixture>) => void)[] = [
    r => r.catalog.options[0].facts.SCIM.availability = "SUPPORTED", r => r.catalog.options[0].facts.SCIM.evidenceStatus = "APPROVED",
    r => r.catalog.options[0].facts.SCIM.observedAt = "2026-02-30T12:00:00Z",
    r => r.catalog.options[0].residency.USER_PROFILES.storageCountries = ["DE", "DE"],
    r => r.catalog.options[0].residency.USER_PROFILES.coverage = "UNKNOWN",
    r => { r.catalog.options[0].authenticationControls.BROWSER.EMPLOYEES.PHISHING_RESISTANCE.enforcement = "SUPPORTED"; r.catalog.options[0].authenticationControls.BROWSER.EMPLOYEES.PHISHING_RESISTANCE.availability = "UNKNOWN"; },
  ];
  for (const mutate of catalogMutations) { const raw = provenanceFixture(); mutate(raw); raw.catalogSha256 = fixtureCatalogDigest(raw.catalog); assert.throws(() => comparisonEvidenceFromCore(raw, provenanceBinding)); }
  for (const [key, value] of [["UNKNOWN", {}], ["__proto__", {}]]) {
    const raw = provenanceFixture(); raw.catalog.options[0].facts = { ...raw.catalog.options[0].facts, [key as string]: value };
    raw.catalogSha256 = fixtureCatalogDigest(raw.catalog); assert.throws(() => comparisonEvidenceFromCore(raw, provenanceBinding));
  }
  const raw = provenanceFixture(); Object.assign(raw.catalog.options[0].facts.SCIM, { actor: "private" });
  raw.catalogSha256 = fixtureCatalogDigest(raw.catalog); assert.throws(() => comparisonEvidenceFromCore(raw, provenanceBinding));
});

const session = { issuer: "http://localhost:8081", subject: "synthetic-provenance-owner", authenticatedAt: new Date("2026-10-02T12:00:00Z"),
  email: null, displayName: null, workspaceId: provenanceBinding.workspaceId, curatorScope: null };
test("personal BFF fixes the owner route, uses bounded no-store credentialed GET and rejects foreign or unavailable results without fallback", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-provenance-token-000000000000000000";
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${session.workspaceId}/assessments/${provenanceBinding.assessmentId}/comparison-evidence-preview`);
    assert.equal(init?.method, "GET"); assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.equal(init?.body, undefined);
    assert.ok(init?.signal instanceof AbortSignal);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
    return Response.json(provenanceFixture());
  };
  try {
    assert.equal((await readComparisonEvidence(session, provenanceBinding.assessmentId, 2, provenanceBinding.values)).evidence.length, 1); assert.equal(calls, 1);
    await assert.rejects(readComparisonEvidence(session, "../other", 2, provenanceBinding.values)); assert.equal(calls, 1);
    for (const response of [new Response("Private upstream body", { status: 403 }), new Response("invalid-json"),
      new Response("x".repeat(comparisonEvidenceByteLimit + 1)), Response.json(provenanceFixture(), { headers: { "Content-Length": String(comparisonEvidenceByteLimit + 1) } }),
      Response.json({ ...provenanceFixture(), sourceVerificationPerformed: true })]) {
      globalThis.fetch = async () => response; await assert.rejects(readComparisonEvidence(session, provenanceBinding.assessmentId, 2, provenanceBinding.values));
    }
    let cancelled = false;
    globalThis.fetch = async () => new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array(comparisonEvidenceByteLimit + 1)); }, cancel() { cancelled = true; } }));
    await assert.rejects(readComparisonEvidence(session, provenanceBinding.assessmentId, 2, provenanceBinding.values)); assert.equal(cancelled, true);
    globalThis.fetch = async () => { throw new DOMException("Synthetic timeout", "TimeoutError"); };
    await assert.rejects(readComparisonEvidence(session, provenanceBinding.assessmentId, 2, provenanceBinding.values));
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});

test("actual Comparison UI renders sources as escaped text, separates all gaps and scopes, and never grants trust or changes verdict", async () => {
  const preview = comparisonEvidenceFromCore(provenanceFixture(), provenanceBinding), components = await assessmentUiComponents();
  const before = structuredClone(preview);
  const html = renderToStaticMarkup(createElement(components.ComparisonSection, { comparison: preview.comparison, evidence: preview.evidence,
    profile: savedRequirementsFixture(), editable: false }));
  assert.deepEqual(preview, before);
  for (const text of ["Inspect fictional evidence", "Identity capabilities", "Scoped human authentication controls", "Identity-provider auditability",
    "https://catalog.invalid/fictional-plan", "UNREVIEWED", "2026-10-02T12:00:00.123456789Z", "No fact recorded", "PARTIAL · DE, US",
    "Passes date/review gates only", "not source or deployed-behavior verification", "not application logs", "Not every listed fact is applied", "The comparison verdict above is unchanged"])
    assert.ok(html.includes(text), text);
  assert.equal(/href="https:\/\/[^"]+\.invalid/u.test(html), false); assert.equal(html.includes("<form"), false);
  assert.equal(html.includes(session.subject), false); assert.ok(html.includes("Needs more information"));
  const dated = provenanceFixture();
  Object.assign(dated.catalog.options[0].facts.SCIM, { evidenceStatus: "REVIEWED", observedAt: "2026-01-01T00:00:00Z",
    sourceUrl: 'https://catalog.invalid/docs?literal=<script>not-a-script</script>&quote="safe"' });
  Object.assign(dated.catalog.options[0].facts, { SAML: { ...dated.catalog.options[0].facts.OIDC, observedAt: "2026-10-03T00:00:00Z" } });
  dated.catalogSha256 = fixtureCatalogDigest(dated.catalog);
  const datedPreview = comparisonEvidenceFromCore(dated, provenanceBinding);
  const datedHtml = renderToStaticMarkup(createElement(components.ComparisonSection, { comparison: datedPreview.comparison,
    evidence: datedPreview.evidence, profile: savedRequirementsFixture(), editable: false }));
  assert.ok(datedHtml.includes("Older than 90 days")); assert.ok(datedHtml.includes("Future-dated"));
  assert.ok(datedHtml.includes("&lt;script&gt;not-a-script&lt;/script&gt;")); assert.equal(datedHtml.includes("<script>"), false);
  assert.equal(/href="https:\/\/[^"]+\.invalid/u.test(datedHtml), false);
  const page = await readFile(new URL("../src/app/assessments/[id]/page.tsx", import.meta.url), "utf8");
  const previews = await readFile(new URL("../src/app/assessments/[id]/saved-previews.tsx", import.meta.url), "utf8");
  assert.ok(page.includes("readComparisonEvidence(session, id, assessment.version, auditValues)")); assert.ok(previews.includes("evidence={preview.evidence}"));
});
