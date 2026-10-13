import assert from "node:assert/strict";
import test from "node:test";
import { catalogChoicesFromCore, catalogCursor, catalogCursorQuery, catalogReference, type CatalogChoices } from "../src/lib/assessment/catalog-choices.ts";
import { readPersonalCatalogChoices } from "../src/lib/auth/core-client.ts";
import { resultItem, resultWorkspace, resultAssessment } from "./fixtures/decision-results.mts";

function page(): CatalogChoices { return { scope: "OWNED_ASSESSMENT_CATALOG_CHOICES", workspaceId: resultWorkspace, assessmentId: resultAssessment,
  items: [{ reference: resultItem().catalog, publishedAt: "2026-01-01T00:00:00.123456Z" }], nextBefore: null, historicalPublicationWorkflowVerified: false }; }
const read = (value: unknown) => catalogChoicesFromCore(value, resultWorkspace, resultAssessment, null);
test("catalog discovery retains exact references but never asserts verification or selects a version", () => {
  const value = page(), before = structuredClone(value); assert.deepEqual(read(value), value); assert.deepEqual(value, before);
  assert.deepEqual(read({ ...value, items: [] }).items, []);
  assert.deepEqual(catalogCursor(Object.fromEntries(new URLSearchParams(catalogCursorQuery(value.items[0].reference)))), value.items[0].reference);
  assert.equal(catalogCursor({}), null);
});
test("catalog cursor accepts only a singular complete exact reference", () => {
  const r = resultItem().catalog, query = Object.fromEntries(new URLSearchParams(catalogCursorQuery(r)));
  for (const malformed of [{ beforeSnapshotId: r.snapshotId }, { ...query, latest: "true" }, { ...query, beforeSnapshotId: [r.snapshotId] },
    { ...query, beforeSnapshotSha256: "0" }, { ...query, beforeCatalogVersion: "../outside" }, { ...query, beforeSnapshotId: "UPPERCASE" }]) assert.throws(() => catalogCursor(malformed));
  for (const ref of [{ ...r, approved: true }, { ...r, snapshotId: 1 }, { ...r, snapshotSha256: "A".repeat(64) }]) assert.throws(() => catalogReference(ref));
});
test("discovery fails closed on private fields, foreign context, trust upgrades, duplicates and broken page cursors", () => {
  for (const mutate of [(p: CatalogChoices) => p.workspaceId = "foreign", (p: CatalogChoices) => p.assessmentId = "foreign",
    (p: CatalogChoices) => Object.assign(p, { historicalPublicationWorkflowVerified: true }),
    (p: CatalogChoices) => Object.assign(p.items[0], { review: { issuer: "private" } }),
    (p: CatalogChoices) => p.items.push(p.items[0]), (p: CatalogChoices) => p.items[0].publishedAt = "not-a-date",
    (p: CatalogChoices) => p.nextBefore = p.items[0].reference, (p: CatalogChoices) => p.items = Array(21).fill(p.items[0])]) {
    const p = page(); mutate(p); assert.throws(() => read(p));
  }
  assert.throws(() => catalogChoicesFromCore(page(), resultWorkspace, resultAssessment, resultItem().catalog));
  const p = page(); p.items = Array.from({ length: 20 }, (_, i) => ({ reference: { ...resultItem().catalog,
    snapshotId: `11111111-1111-4111-8111-${String(i).padStart(12, "0")}`, catalogVersion: `fictional-${i}` }, publishedAt: "2026-01-01T00:00:00Z" }));
  p.nextBefore = p.items.at(-1)!.reference; assert.deepEqual(read(p), p);
  p.nextBefore = p.items[0].reference; assert.throws(() => read(p));
});
test("catalog BFF uses one bounded no-store fixed Core read with session-only identity", async () => {
  const old = globalThis.fetch, token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const session = { workspaceId: resultWorkspace, issuer: "https://identity.example.invalid", subject: "fictional-owner" };
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "fictional-catalog-token-000000000000000000000000000";
  let calls = 0;
  try {
    globalThis.fetch = async (url, init) => {
      calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${resultWorkspace}/assessments/${resultAssessment}/decision-results/catalogs`);
      assert.equal(init?.method, "GET"); assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
      assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
      assert.equal(init?.body, undefined); return Response.json(page());
    };
    assert.deepEqual(await readPersonalCatalogChoices(session as never, resultAssessment), page()); assert.equal(calls, 1);
    await assert.rejects(readPersonalCatalogChoices(session as never, "../other")); assert.equal(calls, 1);
    for (const response of [Response.json({ ...page(), workspaceId: "foreign" }), new Response("private", { status: 503 }),
      Response.json(page(), { headers: { "content-length": "16385" } }), new Response("{}", { headers: { "content-type": "text/html" } })]) {
      let attempts = 0; globalThis.fetch = async () => { attempts++; return response; };
      await assert.rejects(readPersonalCatalogChoices(session as never, resultAssessment)); assert.equal(attempts, 1);
    }
    globalThis.fetch = async () => new Response(null, { status: 404 }); assert.equal(await readPersonalCatalogChoices(session as never, resultAssessment), null);
  } finally { globalThis.fetch = old; if (token === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token; }
});
