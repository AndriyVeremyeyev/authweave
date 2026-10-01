import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { authConfiguration } from "../src/lib/auth/config.ts";
import { prepareCatalogBootstrapReview, recordCatalogBootstrapReview, readCatalogBootstrapReview } from "../src/lib/auth/core-client.ts";
import { BOOTSTRAP_BODY_BYTES, BOOTSTRAP_CANDIDATE_BYTES, bootstrapCandidate, bootstrapPreparationFromCore,
  bootstrapReviewInput, bootstrapReviewSubmission, bootstrapReceiptFromCore, bootstrapReceiptReference,
  bootstrapReceiptHref, boundedBootstrapJson, type BootstrapReceipt } from "../src/lib/catalog/bootstrap-review.ts";
import { bootstrapMutation, bootstrapRead, type BootstrapDependencies } from "../src/lib/catalog/bootstrap-http.ts";
import { candidateClaimSummary } from "../src/lib/catalog/evidence-review.ts";

const draft = JSON.parse(readFileSync(new URL("../../../packages/contracts/tests/fixtures/provider-catalog-draft.valid.json", import.meta.url), "utf8"));
const reviewId = "10000000-0000-4000-8000-000000000001";
const candidateSha256 = "a".repeat(64), reviewSha256 = "b".repeat(64);
const at = "2026-09-30T12:00:00Z";
const scope = { projectId: "123456789012345678", organizationId: "987654321098765432" };
const config = authConfiguration({ AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081",
  AUTHWEAVE_OIDC_CLIENT_ID: "123456789012345678@authweave", AUTHWEAVE_OIDC_PROJECT_ID: scope.projectId, AUTHWEAVE_OIDC_ORG_ID: scope.organizationId });
const session = { issuer: "http://localhost:8081", subject: "synthetic-curator", workspaceId: reviewId,
  authenticatedAt: new Date(at), email: null, displayName: null, curatorScope: scope };
const now = new Date(at);

function report(candidate = draft) {
  const facts: Record<string, unknown>[] = [];
  for (const option of candidate.options) {
    const add = (path: string, fact: Record<string, unknown>) => facts.push({ optionId: option.id, path,
      evidenceStatus: "UNREVIEWED", freshness: "CURRENT", conditions: [...fact.conditions as string[]].sort(), evidence: fact.evidence });
    for (const [key, value] of Object.entries(option.facts)) add(`facts.${key}`, value as Record<string, unknown>);
    for (const [family, entries] of Object.entries(option.compatibility)) {
      for (const [key, value] of Object.entries(entries as object)) add(`compatibility.${family}.${key}`, value as Record<string, unknown>);
    }
    for (const [key, value] of Object.entries(option.residency)) add(`residency.${key}`, value as Record<string, unknown>);
    for (const [client, populations] of Object.entries(option.authenticationControls)) {
      for (const [population, controls] of Object.entries(populations as object)) {
        for (const [control, value] of Object.entries(controls as object)) add(`authenticationControls.${client}.${population}.${control}`, value as Record<string, unknown>);
      }
    }
  }
  facts.sort((a, b) => `${a.optionId}\0${a.path}` < `${b.optionId}\0${b.path}` ? -1 : 1);
  return { scope: "CATALOG_DRAFT_VALIDATION", policyVersion: "catalog-draft-validation-1", canonicalizationVersion: "catalog-draft-canonical-json-1",
    catalogVersion: candidate.catalogVersion, catalogSchemaVersion: 1, evaluatedAt: at, status: "VALID_DRAFT", contentSha256: candidateSha256,
    sourceVerificationPerformed: false, approvalGranted: false, writesPerformed: false, evaluationReady: false,
    optionCount: candidate.options.length, factCount: facts.length, issues: [], facts };
}
function prepared() { return bootstrapPreparationFromCore(report(), bootstrapCandidate(draft)!, reviewId); }
function input() {
  const preparation = prepared();
  return bootstrapReviewSubmission(preparation, Object.fromEntries(preparation.facts.map((fact, i) =>
    [`${fact.optionId}\0${fact.path}`, i % 3 === 0 ? "SOURCE_SUPPORTS_CLAIM" : i % 3 === 1 ? "SOURCE_DOES_NOT_SUPPORT_CLAIM" : "INSUFFICIENT_EVIDENCE"])), true)!;
}
function receipt(): BootstrapReceipt {
  const request = input();
  return { reviewId, candidateSha256, reviewSha256, catalogVersion: draft.catalogVersion, factCount: request.observations.length,
    counts: { supporting: 3, contradicting: 3, insufficient: 3 }, recordedAt: at,
    policyVersion: "catalog-bootstrap-source-review-1", kind: "HUMAN_BOOTSTRAP_SOURCE_REVIEW",
    sourceVerificationPerformed: false, approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false };
}

test("bootstrap display covers every recorded fact and all four typed claim families with no authority", () => {
  const preparation = prepared();
  assert.equal(preparation.facts.length, 9);
  assert.deepEqual(new Set(preparation.facts.map(fact => fact.claim.kind)), new Set(["CAPABILITY", "COMPATIBILITY", "RESIDENCY", "AUTHENTICATION_CONTROL"]));
  for (const fact of preparation.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.scope.plan, draft.options[0].plan);
    assert.equal(fact.evidence.sourceUrl, "https://docs.example.invalid/identity/plan");
    assert.ok(candidateClaimSummary(fact.claim).length > 0);
  }
  assert.equal(preparation.candidateSha256, candidateSha256);
  assert.deepEqual(preparation.candidate, draft);
});

test("bootstrap display preserves UNKNOWN, PARTIAL and separate availability/enforcement and Core freshness", () => {
  const candidate = structuredClone(draft);
  candidate.options[0].facts.SCIM.availability = "UNKNOWN";
  candidate.options[0].residency.USER_PROFILES.coverage = "PARTIAL";
  candidate.options[0].authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE.enforcement = "UNKNOWN";
  const validation = report(candidate);
  validation.facts[0].freshness = "FUTURE"; validation.facts[1].freshness = "STALE";
  const preparation = bootstrapPreparationFromCore(validation, bootstrapCandidate(candidate)!, reviewId);
  assert.equal(preparation.facts[0].freshness, "FUTURE");
  assert.equal(preparation.facts[1].freshness, "STALE");
  assert.match(candidateClaimSummary(preparation.facts.find(f => f.path === "facts.SCIM")!.claim)[0], /Unknown/);
  assert.match(candidateClaimSummary(preparation.facts.find(f => f.path.startsWith("residency"))!.claim)[0], /does not rule out/);
  assert.match(candidateClaimSummary(preparation.facts[0].claim)[1], /Enforcement: Unknown/);
});

test("bootstrap preparation fails closed on another label/target, incomplete data, changed provenance or promoted trust", () => {
  for (const mutate of [
    (r: ReturnType<typeof report>) => { r.catalogVersion = "another"; },
    (r: ReturnType<typeof report>) => { r.contentSha256 = "invalid"; },
    (r: ReturnType<typeof report>) => { r.status = "INVALID_DRAFT"; },
    (r: ReturnType<typeof report>) => { r.sourceVerificationPerformed = true; },
    (r: ReturnType<typeof report>) => { r.approvalGranted = true; },
    (r: ReturnType<typeof report>) => { r.writesPerformed = true; },
    (r: ReturnType<typeof report>) => { r.evaluationReady = true; },
    (r: ReturnType<typeof report>) => { r.factCount++; },
    (r: ReturnType<typeof report>) => { r.facts.pop(); },
    (r: ReturnType<typeof report>) => { r.facts.reverse(); },
    (r: ReturnType<typeof report>) => { r.facts[0].path = "facts.NOT_THIS_TARGET"; },
    (r: ReturnType<typeof report>) => { r.facts[0].evidenceStatus = "REVIEWED"; },
    (r: ReturnType<typeof report>) => { r.facts[0].freshness = "VERIFIED"; },
    (r: ReturnType<typeof report>) => { r.facts[0].conditions = ["new condition"]; },
    (r: ReturnType<typeof report>) => { (r.facts[0].evidence as Record<string, unknown>).summary = "changed claim"; },
  ]) {
    const validation = structuredClone(report()); mutate(validation);
    assert.throws(() => bootstrapPreparationFromCore(validation, bootstrapCandidate(draft)!, reviewId));
  }
});

test("bootstrap input requires every explicit verdict and distinct whole-review confirmation", () => {
  const preparation = prepared();
  assert.equal(bootstrapReviewSubmission(preparation, {}, false), null);
  assert.equal(bootstrapReviewSubmission(preparation, {}, true), null);
  const choices = Object.fromEntries(preparation.facts.map(f => [`${f.optionId}\0${f.path}`, "INSUFFICIENT_EVIDENCE"]));
  assert.equal(bootstrapReviewSubmission(preparation, choices, false), null);
  const request = bootstrapReviewSubmission(preparation, choices, true)!;
  assert.equal(request.observations.length, 9);
  assert.ok(request.observations.every(o => o.verdict === "INSUFFICIENT_EVIDENCE"));
  assert.equal(request.reviewId, reviewId);
  assert.equal(request.expectedCandidateSha256, candidateSha256);
  assert.deepEqual(bootstrapReviewInput(input()), input());
  for (const change of [
    { confirmation: "MANUAL_SOURCE_REVIEW" }, { confirmation: true }, { schemaVersion: 2 },
    { expectedCandidateSha256: "A".repeat(64) }, { actor: "browser-actor" }, { approvalGranted: true },
    { reviewId: "new" }, { observations: input().observations.slice(1) },
    { observations: [...input().observations.slice(1), input().observations[1]] },
    { observations: input().observations.map((o, i) => i ? o : { ...o, factPath: "facts.NOT_RECORDED" }) },
    { observations: input().observations.map((o, i) => i ? o : { ...o, optionId: "another" }) },
    { observations: input().observations.map((o, i) => i ? o : { ...o, verdict: "APPROVED" }) },
    { observations: input().observations.map((o, i) => i ? o : { ...o, approvalGranted: false }) },
  ]) assert.equal(bootstrapReviewInput({ ...input(), ...change }), null);
});

test("bootstrap candidate and recorded target guards are bounded and reject duplicate options and unsafe links", () => {
  assert.equal(bootstrapCandidate({ ...draft, kind: "PUBLISHED_PROVIDER_CATALOG_SNAPSHOT" }), null);
  assert.equal(bootstrapCandidate({ ...draft, actor: "caller" }), null);
  assert.equal(bootstrapCandidate({ ...draft, options: Array(101).fill(draft.options[0]) }), null);
  assert.equal(bootstrapCandidate({ ...draft, options: [], note: "x".repeat(BOOTSTRAP_CANDIDATE_BYTES) }), null);
  const huge = structuredClone(draft); huge.options[0].product = "é".repeat(BOOTSTRAP_CANDIDATE_BYTES / 2);
  assert.equal(bootstrapCandidate(huge), null);
  for (const sourceUrl of ["javascript:alert(1)", "data:text/html,test", "http://insecure.invalid", "https://user:password@example.invalid", "https://x.invalid/\" onmouseover=\"x"]) {
    const candidate = structuredClone(draft); candidate.options[0].facts.SCIM.evidence.sourceUrl = sourceUrl;
    assert.equal(bootstrapReviewInput({ ...input(), candidate }), null);
  }
  assert.equal(bootstrapReviewInput({ ...input(), candidate: { ...draft, options: [draft.options[0], draft.options[0]] } }), null);
});

test("bootstrap receipts pin UUID/review digest and cannot leak actors, bodies or change verdict totals", () => {
  assert.deepEqual(bootstrapReceiptFromCore(receipt(), { id: reviewId, digest: reviewSha256 }, input()), receipt());
  assert.equal(bootstrapReceiptReference(reviewId, candidateSha256)?.digest, candidateSha256); // Format only; Core binds the review domain.
  assert.equal(bootstrapReceiptReference([reviewId, reviewId], reviewSha256), null);
  assert.equal(bootstrapReceiptReference(reviewId, [reviewSha256]), null);
  assert.match(bootstrapReceiptHref(receipt()), new RegExp(`expectedSha256=${reviewSha256}$`));
  for (const change of [
    { reviewId: "10000000-0000-4000-8000-000000000002" }, { candidateSha256: "c".repeat(64) },
    { reviewSha256: candidateSha256 }, { catalogVersion: "another" }, { factCount: 10 },
    { counts: { supporting: 9, contradicting: 0, insufficient: 0 } }, { actor: { subject: "leak" } },
    { candidate: draft }, { observations: input().observations }, { sourceVerificationPerformed: true },
    { approvalGranted: true }, { factTrustChanged: true }, { catalogWritesPerformed: true },
    { recordedAt: "invalid" }, { kind: "APPROVAL" }, { policyVersion: "another" },
  ]) assert.throws(() => bootstrapReceiptFromCore({ ...receipt(), ...change }, { id: reviewId, digest: reviewSha256 }, input()));
});

async function fakeCore(run: () => Promise<void>, fetch: typeof globalThis.fetch) {
  const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-bootstrap-service-token-000000000000000000";
  globalThis.fetch = fetch;
  try { await run(); } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
}
test("bootstrap prepare/record/read do not call Core for absent, mismatched, stale or future curator grants", async () => {
  await fakeCore(async () => {
    for (const identity of [ { ...session, curatorScope: null }, { ...session, issuer: "https://another.invalid" },
      { ...session, curatorScope: { ...scope, organizationId: "111" } },
      { ...session, authenticatedAt: new Date(now.getTime() - 900001) }, { ...session, authenticatedAt: new Date(now.getTime() + 1) } ]) {
      assert.notEqual((await prepareCatalogBootstrapReview(identity, config, draft, now)).kind, "ready");
      assert.notEqual((await recordCatalogBootstrapReview(identity, config, input(), now)).kind, "recorded");
      assert.notEqual((await readCatalogBootstrapReview(identity, config, reviewId, reviewSha256, now)).kind, "ready");
    }
  }, async () => { assert.fail("Unauthorized Core call"); });
});

test("bootstrap preparation uses only fixed read-only validation and Core digest, without source fetch or write", async () => {
  const paths: string[] = [];
  await fakeCore(async () => {
    const result = await prepareCatalogBootstrapReview(session, config, draft, now);
    assert.equal(result.kind, "ready");
    if (result.kind === "ready") { assert.equal(result.preparation.candidateSha256, candidateSha256); assert.match(result.preparation.reviewId, /^[0-9a-f-]{36}$/); }
    assert.deepEqual(paths, ["http://127.0.0.1:8080/internal/v1/catalog-curator/authorization", "http://127.0.0.1:8080/api/v1/catalog-drafts/validate"]);
  }, async (url, init) => {
    paths.push(String(url)); assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    if (paths.length === 1) return new Response(null, { status: 204 });
    assert.equal(init?.method, "POST"); assert.deepEqual(JSON.parse(String(init?.body)), draft);
    return Response.json(report());
  });
});

test("bootstrap writes and exact receipt reads use only fresh server-session headers and body-free validated receipts", async () => {
  const paths: string[] = [];
  let status = 201;
  await fakeCore(async () => {
    let recorded = await recordCatalogBootstrapReview(session, config, input(), now);
    assert.equal(recorded.kind, "recorded");
    if (recorded.kind === "recorded") assert.equal(recorded.created, true);
    status = 200;
    recorded = await recordCatalogBootstrapReview(session, config, input(), now);
    assert.equal(recorded.kind, "recorded");
    if (recorded.kind === "recorded") assert.equal(recorded.created, false);
    assert.deepEqual(await readCatalogBootstrapReview(session, config, reviewId, reviewSha256, now), { kind: "ready", receipt: receipt() });
    assert.equal(paths.length, 6);
  }, async (url, init) => {
    paths.push(String(url));
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(headers["X-AuthWeave-Curator-Project-Id"], scope.projectId);
    assert.equal(headers["X-AuthWeave-Curator-Org-Id"], scope.organizationId);
    assert.equal(headers["X-AuthWeave-Authenticated-At"], session.authenticatedAt.toISOString());
    assert.match(headers.Authorization, /^Bearer synthetic-bootstrap-service-token-/);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    if (String(url).endsWith("/authorization")) return new Response(null, { status: 204 });
    if (init?.method === "POST") { assert.equal(String(url), "http://127.0.0.1:8080/api/v1/catalog-bootstrap-reviews"); assert.deepEqual(JSON.parse(String(init?.body)), input()); }
    else assert.equal(String(url), `http://127.0.0.1:8080/api/v1/catalog-bootstrap-reviews/${reviewId}?expectedSha256=${reviewSha256}`);
    return Response.json(receipt(), { status: init?.method === "POST" ? status : 200 });
  });
});

test("bootstrap Core denials and malformed/promoted receipts never become success or silently alter retry payload", async () => {
  for (const [status, kind] of [[400, "invalid"], [409, "conflict"], [401, "core-rejected"], [403, "core-rejected"], [500, "core-unavailable"]] as const) {
    await fakeCore(async () => assert.deepEqual(await recordCatalogBootstrapReview(session, config, input(), now), { kind }),
      async url => String(url).endsWith("/authorization") ? new Response(null, { status: 204 }) : new Response(null, { status }));
  }
  const request = input(); const original = JSON.stringify(request);
  await fakeCore(async () => {
    assert.deepEqual(await recordCatalogBootstrapReview(session, config, request, now), { kind: "core-unavailable" });
    assert.equal(JSON.stringify(request), original);
  }, async url => String(url).endsWith("/authorization") ? new Response(null, { status: 204 }) : Response.json({ ...receipt(), factTrustChanged: true }, { status: 201 }));
  await fakeCore(async () => {
    assert.deepEqual(await readCatalogBootstrapReview(session, config, reviewId, candidateSha256, now), { kind: "core-unavailable" });
  }, async url => String(url).endsWith("/authorization") ? new Response(null, { status: 204 }) : Response.json(receipt()));
});

function dependencies(): BootstrapDependencies {
  return { configuration: () => config, session: async () => session,
    prepare: async () => ({ kind: "ready", preparation: prepared() }),
    record: async () => ({ kind: "recorded", created: true, receipt: receipt() }),
    read: async () => ({ kind: "ready", receipt: receipt() }) };
}
function request(body: unknown = input(), headers: Record<string, string> = {}) {
  return new Request("http://localhost:3000/api/catalog-bootstrap-reviews", { method: "POST",
    headers: { origin: "http://localhost:3000", "content-type": "application/json", ...headers }, body: JSON.stringify(body) });
}

test("bootstrap HTTP mutations enforce Origin/session before parsing or forwarding browser identity", async () => {
  const deps = dependencies(); let sessions = 0, writes = 0;
  deps.session = async () => { sessions++; return session; };
  deps.record = async () => { writes++; return { kind: "recorded", created: true, receipt: receipt() }; };
  for (const origin of ["https://attacker.invalid", "null", "http://127.0.0.1:3000"]) {
    assert.equal((await bootstrapMutation(request(input(), { origin }), "record", deps)).status, 403);
  }
  const missing = request(); missing.headers.delete("origin");
  assert.equal((await bootstrapMutation(missing, "record", deps)).status, 403);
  assert.equal(sessions, 0); assert.equal(writes, 0);
  deps.session = async () => null;
  assert.equal((await bootstrapMutation(request(), "record", deps)).status, 401);
  deps.session = async () => session;
  assert.equal((await bootstrapMutation(request({ ...input(), actor: "browser-spoof" }), "record", deps)).status, 400);
  assert.equal((await bootstrapMutation(request(input(), { "content-type": "text/plain" }), "record", deps)).status, 415);
  assert.equal(writes, 0);
});

test("bootstrap HTTP prepare never writes and recorded/retried/read responses are no-store and body-free", async () => {
  const deps = dependencies(); let writes = 0;
  deps.record = async (_session, _config, body) => { writes++; assert.deepEqual(body, input()); return { kind: "recorded", created: writes === 1, receipt: receipt() }; };
  const preparation = await bootstrapMutation(request(draft), "prepare", deps);
  assert.equal(preparation.status, 200); assert.equal(writes, 0);
  assert.equal((await preparation.json()).facts.length, 9);
  for (const expected of [201, 200]) {
    const response = await bootstrapMutation(request(), "record", deps);
    assert.equal(response.status, expected); assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(response.headers.get("referrer-policy"), "no-referrer"); assert.deepEqual(await response.json(), receipt());
  }
  const response = await bootstrapRead(new Request(`http://localhost:3000/api/catalog-bootstrap-reviews/${reviewId}?expectedSha256=${reviewSha256}`), reviewId, deps);
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), receipt());
  for (const query of ["", `?expectedSha256=${reviewSha256}&expectedSha256=${reviewSha256}`, `?expectedSha256=${reviewSha256}&actor=spoof`]) {
    assert.equal((await bootstrapRead(new Request(`http://localhost:3000/api/catalog-bootstrap-reviews/${reviewId}${query}`), reviewId, deps)).status, 400);
  }
});

test("bootstrap HTTP maps authorization, conflicts and uncertain outcomes without raw errors or fallback writes", async () => {
  const deps = dependencies();
  for (const [kind, status] of [["invalid", 400], ["conflict", 409], ["reauth-required", 403], ["not-granted", 403], ["core-rejected", 403], ["core-unavailable", 503], ["not-configured", 503]] as const) {
    deps.record = async () => ({ kind });
    const result = await bootstrapMutation(request(), "record", deps);
    assert.equal(result.status, status); assert.deepEqual(await result.json(), { kind });
  }
  deps.record = async () => { throw new Error("private actor/source/token must never escape"); };
  assert.deepEqual(await (await bootstrapMutation(request(), "record", deps)).json(), { kind: "core-unavailable" });
});

test("bootstrap streamed JSON is bounded for declared/chunked bytes and malformed UTF-8", async () => {
  assert.deepEqual(await boundedBootstrapJson(Response.json({ valid: true })), { valid: true });
  await assert.rejects(boundedBootstrapJson(request({}, { "content-length": String(BOOTSTRAP_BODY_BYTES + 1) })), RangeError);
  await assert.rejects(boundedBootstrapJson(request({}, { "content-length": "bogus" })), RangeError);
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new Uint8Array(BOOTSTRAP_BODY_BYTES + 1)); }, cancel() { cancelled = true; } });
  await assert.rejects(boundedBootstrapJson(new Response(stream)), RangeError); assert.equal(cancelled, true);
  await assert.rejects(boundedBootstrapJson(new Response(new Uint8Array([0xc3, 0x28]))));
  assert.equal((await bootstrapMutation(request({}, { "content-length": String(BOOTSTRAP_BODY_BYTES + 1) }), "record", dependencies())).status, 413);
});

test("bootstrap HTTP bounds expanded browser responses, including repeated option scope", async () => {
  const deps = dependencies();
  deps.prepare = async () => ({ kind: "ready", preparation: { ...prepared(),
    candidate: { ...prepared().candidate, catalogVersion: "x".repeat(BOOTSTRAP_BODY_BYTES) } } });
  const response = await bootstrapMutation(request(draft), "prepare", deps);
  assert.equal(response.status, 413);
  assert.equal(await response.text(), "");
  assert.equal(response.headers.get("cache-control"), "no-store");
});
