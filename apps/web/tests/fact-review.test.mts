import assert from "node:assert/strict";
import { test } from "node:test";
import { factReviewInput, factReviewFromCore, type FactReviewInput } from "../src/lib/catalog/fact-review.ts";
import { recordCatalogFactReview } from "../src/lib/auth/core-client.ts";

const id = "90000000-0000-4000-8000-000000000001";
const input: FactReviewInput = { reviewId: "90000000-0000-4000-8000-000000000002", expectedVersion: 2,
  expectedSha256: "a".repeat(64), optionId: "example-managed-eu", factPath: "facts.OIDC",
  verdict: "SOURCE_SUPPORTS_CLAIM", confirmation: "MANUAL_SOURCE_REVIEW" };
const receipt = { reviewId: input.reviewId, proposalId: id, proposalVersion: 2, proposalSha256: input.expectedSha256,
  reviewNumber: 1, optionId: input.optionId, factPath: input.factPath, verdict: input.verdict,
  recordedAt: "2026-09-30T12:00:00Z", kind: "HUMAN_SOURCE_REVIEW_OBSERVATION", sourceVerificationPerformed: false,
  approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false };

test("manual review input is strict, bounded, explicit and covers digit-bearing fact paths", () => {
  assert.deepEqual(factReviewInput(input), input);
  for (const factPath of ["compatibility.applications.B2B_SAAS", "residency.USER_PROFILES",
    "authenticationControls.MACHINE_TO_MACHINE.PARTNERS.MFA"]) {
    assert.ok(factReviewInput({ ...input, factPath }));
  }
  for (const change of [{ expectedVersion: "2" }, { expectedVersion: -1 }, { expectedVersion: 2.5 },
    { expectedVersion: Number.MAX_SAFE_INTEGER + 1 }, { expectedSha256: "A".repeat(64) }, { actor: "forged" },
    { confirmation: null }, { factPath: "../facts.OIDC" }, { factPath: `facts.${"A".repeat(200)}` },
    { reviewId: "not-a-uuid" }, { optionId: "example/other" }, { verdict: "VERIFIED" }]) {
    assert.equal(factReviewInput({ ...input, ...change }), null);
  }
  const missing = { ...input } as Partial<FactReviewInput>; delete missing.confirmation;
  assert.equal(factReviewInput(missing), null);
});

test("manual review receipt is exactly request-bound and cannot imply approval or verification", () => {
  assert.deepEqual(factReviewFromCore(receipt, id, input), receipt);
  for (const change of [{ proposalId: input.reviewId }, { proposalVersion: 3 }, { reviewId: id },
    { proposalSha256: "b".repeat(64) }, { optionId: "other-option" }, { factPath: "facts.SCIM" },
    { verdict: "INSUFFICIENT_EVIDENCE" }, { reviewNumber: 0 }, { recordedAt: "invalid" },
    { sourceVerificationPerformed: true }, { approvalGranted: true }, { catalogWritesPerformed: true },
    { factTrustChanged: true }, { actorSubject: "private" }]) {
    assert.throws(() => factReviewFromCore({ ...receipt, ...change }, id, input), /Invalid/);
  }
});

test("BFF records only through fixed Core with fresh server-session assertions and validates every receipt", async () => {
  const previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const previousFetch = globalThis.fetch;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const scope = { projectId: "123456789012345678", organizationId: "987654321098765432" };
  const session = { issuer: "http://localhost:8081", subject: "synthetic-curator", email: null, displayName: null,
    workspaceId: "70000000-0000-4000-8000-000000000001", authenticatedAt: new Date("2026-09-30T12:00:00Z"),
    curatorScope: scope };
  const config = { issuer: new URL(session.issuer), curatorScope: scope };
  const now = new Date("2026-09-30T12:10:00Z");
  let calls = 0, status = 201;
  let payload: unknown = receipt;
  globalThis.fetch = async (url, init) => {
    calls++;
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    assert.ok(init?.signal instanceof AbortSignal);
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers.Cookie, undefined);
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject);
    assert.equal(headers["X-AuthWeave-Authenticated-At"], session.authenticatedAt.toISOString());
    if (init?.method === "GET") {
      assert.equal(url, "http://127.0.0.1:8080/internal/v1/catalog-curator/authorization");
      return new Response(null, { status: 204 });
    }
    assert.equal(url, `http://127.0.0.1:8080/api/v1/catalog-change-proposals/${id}/fact-reviews`);
    assert.equal(init?.method, "POST"); assert.deepEqual(JSON.parse(String(init?.body)), input);
    return status === 201 || status === 200 ? Response.json(payload, { status }) : new Response(null, { status });
  };
  try {
    assert.equal((await recordCatalogFactReview({ ...session, curatorScope: null }, config, id, input, now)).kind, "not-granted");
    assert.equal((await recordCatalogFactReview({ ...session, authenticatedAt: new Date("2026-09-30T11:54:00Z") },
      config, id, input, now)).kind, "reauth-required");
    assert.equal((await recordCatalogFactReview(session, config, "../other", input, now)).kind, "invalid");
    assert.equal(calls, 0);
    assert.deepEqual(await recordCatalogFactReview(session, config, id, input, now), { kind: "recorded", created: true, review: receipt });
    status = 200;
    assert.deepEqual(await recordCatalogFactReview(session, config, id, input, now), { kind: "recorded", created: false, review: receipt });
    for (const [code, kind] of [[400, "invalid"], [401, "core-rejected"], [403, "core-rejected"],
      [404, "not-found"], [409, "conflict"], [503, "core-unavailable"], [204, "core-unavailable"]] as const) {
      status = code; assert.equal((await recordCatalogFactReview(session, config, id, input, now)).kind, kind);
    }
    status = 201; payload = { ...receipt, factTrustChanged: true };
    assert.equal((await recordCatalogFactReview(session, config, id, input, now)).kind, "core-unavailable");
    globalThis.fetch = async () => { throw new Error("Core offline"); };
    assert.equal((await recordCatalogFactReview(session, config, id, input, now)).kind, "core-unavailable");
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});
