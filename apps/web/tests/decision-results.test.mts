import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { resultPageFromCore, resultSummaryFromCore, resultQueryReference, resultReferenceQuery,
  resultSummaryByteLimit, resultHistoryByteLimit } from "../src/lib/assessment/decision-results.ts";
import { readPersonalDecisionHistory, readPersonalDecisionSummary } from "../src/lib/auth/core-client.ts";
import { resultWorkspace, resultAssessment, firstResult, secondResult, resultItem, resultPage, resultSummary } from "./fixtures/decision-results.mts";

const session = { workspaceId: resultWorkspace, issuer: "https://identity.example.invalid", subject: "fictional-owner" };
const readPage = (value: unknown, before = null as typeof firstResult | null) => resultPageFromCore(value, resultWorkspace, resultAssessment, before);
const readSummary = (value: unknown) => resultSummaryFromCore(value, resultWorkspace, resultAssessment, firstResult);
const clone = <T,>(value: T) => structuredClone(value);

test("history preserves exact immutable pins, version order and metadata-only authority", () => {
  const page = resultPage(), before = clone(page); assert.deepEqual(readPage(page), page); assert.deepEqual(page, before);
  assert.deepEqual(readPage({ ...page, items: [page.items[1]] }, secondResult).items, [page.items[1]]);
  assert.deepEqual(readPage({ ...page, items: [] }, firstResult).items, []);
  assert.throws(() => readPage({ ...page, items: [] }, secondResult));
});
for (const field of ["scope", "workspaceId", "assessmentId", "items", "nextBefore", "historicalReplayVerified"])
  test(`history rejects missing ${field} and mixed trust scope`, () => {
    const page = resultPage() as unknown as Record<string, unknown>; delete page[field]; assert.throws(() => readPage(page));
    const invalid = resultPage(); if (field === "historicalReplayVerified") Object.assign(invalid, { historicalReplayVerified: true });
    else Object.assign(invalid, { [field]: "forged" }); assert.throws(() => readPage(invalid));
  });
test("history rejects duplicates, skipped versions, foreign links and invented cursors", () => {
  const p = resultPage();
  for (const items of [[p.items[0], p.items[0]], [...p.items].reverse(), [p.items[0]], Array(21).fill(p.items[0])]) assert.throws(() => readPage({ ...p, items }));
  const bad = resultPage(); bad.items[0].previousResult!.resultSha256 = "f".repeat(64); assert.throws(() => readPage(bad));
  assert.throws(() => readPage({ ...p, nextBefore: firstResult }));
  assert.throws(() => readPage({ ...p, auditActor: "private-subject" }));
});
test("summary accepts historical advice without mutation and checks bounded score/status consistency", () => {
  const s = resultSummary(), before = clone(s); assert.deepEqual(readSummary(s), s); assert.deepEqual(s, before);
  s.candidates[0].hardVerdict = "ELIGIBLE"; s.candidates[0].score = { lowerBound: 60, upperBound: 100, unknownWeight: 40 };
  s.status = "UNRANKED_SHORTLIST"; s.shortlist = [s.candidates[0].optionId]; assert.deepEqual(readSummary(s), s);
  s.candidates[0].score = { lowerBound: 100, upperBound: 100, unknownWeight: 0 }; s.status = "RANKED_SHORTLIST"; assert.deepEqual(readSummary(s), s);
  s.weights = { mode: "NONE", values: [] }; s.status = "UNRANKED_SHORTLIST"; s.candidates[0].score = null; assert.deepEqual(readSummary(s), s);
});
for (const field of ["externalSourceVerificationPerformed", "configurationVerified", "complianceVerified", "decisionApproved", "historicalReplayVerified", "verificationGapCount"])
  test(`summary fails closed on forged ${field}`, () => {
    const s = resultSummary(); Object.assign(s, { [field]: field === "verificationGapCount" ? 0 : field === "historicalReplayVerified" ? false : true });
    assert.throws(() => readSummary(s));
  });
test("summary rejects foreign references, malformed weights, invented winners and illegal point ranges", () => {
  const mutations = [
    (s: ReturnType<typeof resultSummary>) => { s.item.reference = secondResult; },
    (s: ReturnType<typeof resultSummary>) => { s.item.assessmentVersion = Number.MAX_SAFE_INTEGER + 1; },
    (s: ReturnType<typeof resultSummary>) => { s.weights.values[0].weight = 99; },
    (s: ReturnType<typeof resultSummary>) => { s.shortlist = ["invented-winner"]; },
    (s: ReturnType<typeof resultSummary>) => { s.candidates[0].score = { lowerBound: 50, upperBound: 101, unknownWeight: 51 }; },
    (s: ReturnType<typeof resultSummary>) => { s.candidates.push(clone(s.candidates[0])); },
    (s: ReturnType<typeof resultSummary>) => { s.profileSchemaVersion = 7; },
    (s: ReturnType<typeof resultSummary>) => { s.profileSha256 = "not-a-digest"; },
    (s: ReturnType<typeof resultSummary>) => { s.evaluatedAt = "not-a-clock"; },
  ];
  for (const mutate of mutations) { const s = resultSummary(); mutate(s); assert.throws(() => readSummary(s)); }
  assert.throws(() => readSummary({ ...resultSummary(), actor: session.subject }));
});
test("exact query references reject duplicates, partial, coerced/unsafe values and unknown query authority", () => {
  assert.equal(resultQueryReference({}, true), null);
  const complete = { beforeResultId: secondResult.resultId, beforeVersion: "2", beforeResultSha256: secondResult.resultSha256 };
  assert.deepEqual(resultQueryReference(complete, true), secondResult);
  assert.deepEqual(resultQueryReference({ version: "1", resultSha256: firstResult.resultSha256 }, false, firstResult.resultId), firstResult);
  for (const v of ["0", "01", "1.0", "1e0", "9007199254740992", ["1", "2"]]) assert.throws(() => resultQueryReference({ ...complete, beforeVersion: v }, true));
  for (const field of Object.keys(complete)) { const q = { ...complete } as Record<string, string>; delete q[field]; assert.throws(() => resultQueryReference(q, true)); }
  assert.throws(() => resultQueryReference({ ...complete, latest: "true" }, true));
});

test("BFF uses fixed bounded no-store GETs and session-only owner assertions for history and exact summary", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN, token = "fictional-result-read-token-0000000000000000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token; const calls: string[] = [];
  globalThis.fetch = async (url, init) => {
    const href = String(url); calls.push(href); assert.equal(init?.method, "GET"); assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    const headers = init?.headers as Record<string, string>; assert.equal(headers.Authorization, `Bearer ${token}`);
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject); assert.equal(headers["X-AuthWeave-Oidc-Issuer"], session.issuer);
    assert.equal(Object.keys(headers).length, 3); assert.equal(init?.body, undefined);
    return Response.json(href.includes("/summary?") ? resultSummary() : { ...resultPage(), items: [resultItem()] });
  };
  try {
    assert.ok(await readPersonalDecisionHistory(session as never, resultAssessment, secondResult));
    assert.ok(await readPersonalDecisionSummary(session as never, resultAssessment, firstResult));
    assert.equal(calls[0], `http://127.0.0.1:8080/api/v6/workspaces/${resultWorkspace}/assessments/${resultAssessment}/decision-results?${resultReferenceQuery(secondResult, true)}`);
    assert.equal(calls[1], `http://127.0.0.1:8080/api/v6/workspaces/${resultWorkspace}/assessments/${resultAssessment}/decision-results/${firstResult.resultId}/summary?${resultReferenceQuery(firstResult)}`);
    const count = calls.length; await assert.rejects(readPersonalDecisionHistory(session as never, "../another"));
    await assert.rejects(readPersonalDecisionSummary(session as never, resultAssessment, { ...firstResult, version: Number.MAX_SAFE_INTEGER + 1 }));
    assert.equal(calls.length, count);
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});
test("BFF refuses missing credentials, non-JSON, redirects, wrong bindings and oversized streamed/declared responses", async () => {
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "fictional-result-read-token-0000000000000000";
  try {
    for (const [limit, read, fixture] of [[resultHistoryByteLimit, () => readPersonalDecisionHistory(session as never, resultAssessment), resultPage],
      [resultSummaryByteLimit, () => readPersonalDecisionSummary(session as never, resultAssessment, firstResult), resultSummary]] as const) {
      for (const response of [new Response("private-error", { status: 500 }), new Response("{}", { headers: { "content-type": "text/html" } }),
        Response.json({ ...fixture(), workspaceId: "foreign" }), Response.json(fixture(), { headers: { "content-length": String(limit + 1) } }),
        new Response(new Uint8Array(limit + 1), { headers: { "content-type": "application/json" } })]) {
        globalThis.fetch = async () => response; await assert.rejects(read());
      }
      const redirected = Response.json(fixture()); Object.defineProperty(redirected, "redirected", { value: true });
      globalThis.fetch = async () => redirected; await assert.rejects(read());
      globalThis.fetch = async () => new Response(null, { status: 404 }); assert.equal(await read(), null);
    }
    delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; globalThis.fetch = async () => { throw new Error("Unexpected call"); };
    await assert.rejects(readPersonalDecisionHistory(session as never, resultAssessment), /not configured/);
  } finally { globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});

async function components() {
  const url = (source: string) => `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
  const link = url(`import { jsx } from ${JSON.stringify(import.meta.resolve("react/jsx-runtime"))}; export default function Link({prefetch,children,...props}) { return jsx("a",{...props,children}); }`);
  const source = await readFile(new URL("../src/app/assessments/[id]/results/result-history.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  return import(url(compiled.replaceAll('"next/link"', JSON.stringify(link))
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"@/lib/assessment/decision-results"', JSON.stringify(new URL("../src/lib/assessment/decision-results.ts", import.meta.url).href))));
}
test("actual history and summary components expose exact pins and no write, winner or approval controls", async () => {
  const { ResultHistory, ResultSummaryView } = await components();
  const page = resultPage(), s = resultSummary(), before = clone({ page, s });
  const history = renderToStaticMarkup(createElement(ResultHistory, { page, paginated: false }));
  assert.ok(history.includes("references, not verified calculation bodies")); assert.ok(history.includes(`/${firstResult.resultId}?version=1&amp;resultSha256=${firstResult.resultSha256}`));
  const html = renderToStaticMarkup(createElement(ResultSummaryView, { summary: s }));
  for (const label of ["Core verified the whole historical replay", "Original evaluation clock", "Schema 6", "No decision is approved", "22 verification", "not confidence", "Unresolved hard requirements", firstResult.resultSha256]) assert.ok(html.includes(label), label);
  for (const text of ["<form", "<input", session.subject, "Bearer", "sourceUrl"]) assert.equal((html + history).includes(text), false);
  assert.deepEqual({ page, s }, before);
  const empty = renderToStaticMarkup(createElement(ResultHistory, { page: { ...page, items: [] }, paginated: false }));
  assert.ok(empty.includes("No saved decision calculations yet")); assert.ok(empty.includes("synthetic previews are not saved"));
});
