import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { sensitivityFromCore, sensitivityForm, sensitivityRequest, postSensitivity, readSensitivityForm, sensitivityByteLimit } from "../src/lib/assessment/decision-sensitivity.ts";
import { comparePersonalDecisionWeights } from "../src/lib/auth/core-client.ts";
import { sensitivityFixture, sensitivityBaseline } from "./fixtures/decision-sensitivity.mts";
import { resultAdvice, rankedAdvice } from "./fixtures/decision-advice.mts";

const guard = (f: ReturnType<typeof sensitivityFixture>) => sensitivityFromCore(f.value, f.baseline.summary.workspaceId, f.baseline.summary.assessmentId, f.input, f.baseline);
const body = (f = sensitivityFixture()) => new URLSearchParams({ version: "1", resultSha256: f.input.reference.resultSha256,
  weightMode: "EXPLICIT", weight_SAML: String(f.input.weights.values[0].weight), weight_MFA: String(f.input.weights.values[1].weight) }).toString();
test("fixed-input sensitivity can reverse ranks or preserve a tie without changing hard evidence or saved inputs", () => {
  for (const saml of [20, 50, 70]) {
    const f = sensitivityFixture(undefined, saml), snapshot = structuredClone(f);
    assert.deepEqual(guard(f), f.value); assert.deepEqual(f, snapshot);
    assert.deepEqual(f.value.before.candidates.map(c => c.hardChecks), f.value.after.candidates.map(c => c.hardChecks));
  }
  const flipped = sensitivityFixture(); assert.deepEqual(flipped.value.after.rankGroups[0].optionIds, ["fictional-second"]);
  assert.equal(sensitivityFixture(undefined, 50).value.after.rankGroups[0].optionIds.length, 2);
});
test("unknown preference evidence stays unknown and unranked at every weighting", () => {
  const b = sensitivityBaseline();
  for (let i = 0; i < b.candidates.length; i++) {
    const c = b.candidates[i]; c.score!.contributions.forEach(x => { x.outcome = "UNKNOWN"; x.earnedPoints = 0; });
    c.score!.lowerBound = 0; c.score!.upperBound = 100; c.score!.unknownWeight = 100;
    b.summary.candidates[i].score = { lowerBound: 0, upperBound: 100, unknownWeight: 100 };
  }
  b.rankGroups = []; b.summary.status = "UNRANKED_SHORTLIST";
  const f = sensitivityFixture(b); assert.deepEqual(guard(f), f.value); assert.deepEqual(f.value.after.rankGroups, []);
});
test("excluded options never acquire a score, shortlist membership or a rank through weights", () => {
  const b = sensitivityBaseline(), c = b.candidates[1];
  c.hardChecks.hardVerdict = "EXCLUDED"; c.hardChecks.findings[0].outcome = "FAIL"; c.score = null;
  b.summary.candidates[1].hardVerdict = "EXCLUDED"; b.summary.candidates[1].score = null;
  b.summary.shortlist.pop(); b.rankGroups.pop();
  const f = sensitivityFixture(b); assert.deepEqual(guard(f), f.value);
  assert.equal(f.value.after.candidates[1].score, null); assert.equal(f.value.after.shortlist.includes(c.hardChecks.optionId), false);
  f.value.after.candidates[1].hardChecks.hardVerdict = "ELIGIBLE"; assert.throws(() => guard(f));
});
test("actual SSR control explains zero/one dimensions and shows explicit accessible weights only for reweightable profiles", async () => {
  const source = await readFile(new URL("../src/app/assessments/[id]/results/result-sensitivity.tsx", import.meta.url), "utf8");
  let compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText;
  compiled = compiled.replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"react"', JSON.stringify(import.meta.resolve("react")))
    .replaceAll('"@/lib/assessment/decision-sensitivity"', JSON.stringify(new URL("../src/lib/assessment/decision-sensitivity.ts", import.meta.url).href));
  const { ResultSensitivityView } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
  const none = resultAdvice(); none.summary.weights = { mode: "NONE", values: [] };
  for (const b of [none, rankedAdvice()]) {
    const html = renderToStaticMarkup(createElement(ResultSensitivityView, { baseline: b }));
    assert.equal(html.includes('<form'), false); assert.match(html, /Changing preferences requires editing the profile/);
    assert.match(html, /does not save or approve a result/);
  }
  const html = renderToStaticMarkup(createElement(ResultSensitivityView, { baseline: sensitivityBaseline() }));
  assert.match(html, /Compare weights without saving/); assert.match(html, /name="weight_SAML"/); assert.match(html, /name="weight_MFA"/);
  assert.match(html, /min="1" max="100" step="1"/); assert.match(html, /role="status" aria-live="polite"/);
  assert.equal(html.includes('localStorage'), false); assert.equal(html.includes('approve-result'), false);
});
const changes: [string, (f: ReturnType<typeof sensitivityFixture>) => void][] = [
  ["writes", f => Object.assign(f.value, { writesPerformed: true })],
  ["foreign owner", f => f.value.summary.workspaceId = "foreign"],
  ["new result", f => f.value.summary.item.reference.version = 2],
  ["clock drift", f => f.value.summary.evaluatedAt = "2026-01-01T00:00:00Z"],
  ["catalog drift", f => f.value.summary.item.catalog.catalogVersion = "forged"],
  ["raw profile", f => Object.assign(f.value, { profile: {} })],
  ["false approval", f => Object.assign(f.value.summary, { decisionApproved: true })],
  ["hard reason drift", f => f.value.after.candidates[0].hardChecks.findings[0].reasonCode = "FORGED"],
  ["evidence date drift", f => f.value.after.candidates[0].hardChecks.findings[0].evidence!.observedAt = "2026-01-01T00:00:00Z"],
  ["preference outcome drift", f => f.value.after.candidates[0].score!.contributions[0].reasonCode = "FORGED"],
  ["changed dimensions", f => f.value.after.weights.values[0].capability = "SCIM"],
  ["wrong requested weights", f => f.input.weights.values = [{ capability: "SAML", weight: 50 }, { capability: "MFA", weight: 50 }]],
  ["invented rank", f => f.value.after.rankGroups[0].rank = 2],
  ["invented points", f => f.value.after.candidates[0].score!.contributions[0].earnedPoints = 10],
  ["forged baseline", f => f.value.before.candidates[0].hardChecks.configuration = "Forged input"],
];
for (const [name, change] of changes) test(`comparison guard rejects ${name}`, () => { const f = sensitivityFixture(); f.value = structuredClone(f.value); change(f); assert.throws(() => guard(f)); });
test("form refuses duplicate, partial, unsafe, unknown, fractional and nonsumming input", () => {
  const f = sensitivityFixture(); assert.deepEqual(sensitivityForm(new URLSearchParams(body(f)), f.input.reference.resultId).weights.values,
    [{ capability: "SAML", weight: 20 }, { capability: "MFA", weight: 80 }]);
  const changes: ((p: URLSearchParams) => void)[] = [p => p.append("version", "1"), p => p.delete("resultSha256"), p => p.set("version", "9007199254740992"),
    p => p.set("clock", "now"), p => p.set("weight_MFA", "79"), p => p.set("weight_SAML", "20.0"), p => p.set("weight_SCIM", "1")];
  for (const change of changes) {
    const p = new URLSearchParams(body()); change(p); assert.throws(() => sensitivityForm(p, f.input.reference.resultId));
  }
  assert.throws(() => sensitivityRequest({ ...f.input, catalog: {} }));
});
test("bounded form reading rejects chunked UTF-8 byte overflow before parsing", async () => {
  await assert.rejects(readSensitivityForm(new Request("https://example.invalid", { method: "POST", body: "\u00e9".repeat(8193) })));
  await assert.rejects(readSensitivityForm(new Request("https://example.invalid", { method: "POST", body: "a", headers: { "Content-Length": "20000" } })));
});
test("browser comparison performs only one same-origin bounded no-store POST, never recording or automatic retry", async () => {
  const f = sensitivityFixture(); let calls = 0;
  const fetcher: typeof fetch = async (url, init) => {
    calls++; assert.equal(String(url), `/api/assessments/${f.baseline.summary.assessmentId}/decision-results/${f.input.reference.resultId}/sensitivity`);
    assert.equal(init?.method, "POST"); assert.equal(init?.credentials, "same-origin"); assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    return Response.json(f.value);
  };
  assert.deepEqual(await postSensitivity(f.baseline.summary.assessmentId, body(f), f.baseline, fetcher), f.value); assert.equal(calls, 1);
  const failed: typeof fetch = async () => { calls++; throw new Error("Offline"); };
  assert.equal(await postSensitivity(f.baseline.summary.assessmentId, body(f), f.baseline, failed), null); assert.equal(calls, 2);
  for (const response of [new Response(null, { status: 409 }), Response.json(f.value, { status: 201 }),
    new Response("x".repeat(sensitivityByteLimit + 1), { headers: { "Content-Type": "application/json" } })]) {
    assert.equal(await postSensitivity(f.baseline.summary.assessmentId, body(f), f.baseline, async () => response), null);
  }
});
test("Core BFF uses fixed owner-scoped URL and session assertions only, with no latest lookup or retry", async () => {
  const f = sensitivityFixture(), old = globalThis.fetch, token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "fictional-sensitivity-token-000000000000000000";
  const session = { workspaceId: f.baseline.summary.workspaceId, issuer: "https://identity.example.invalid", subject: "fictional-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  let calls = 0;
  try {
    globalThis.fetch = async (url, init) => {
      calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${session.workspaceId}/assessments/${f.baseline.summary.assessmentId}/decision-results/${f.input.reference.resultId}/sensitivity`);
      assert.deepEqual(JSON.parse(String(init?.body)), f.input); assert.equal(new Headers(init?.headers).get("X-AuthWeave-Oidc-Subject"), session.subject);
      assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); return Response.json(f.value);
    };
    assert.deepEqual(await comparePersonalDecisionWeights(session, f.baseline.summary.assessmentId, f.input), f.value); assert.equal(calls, 1);
    globalThis.fetch = async () => { calls++; return new Response(null, { status: 403 }); };
    assert.equal(await comparePersonalDecisionWeights(session, f.baseline.summary.assessmentId, f.input), 403); assert.equal(calls, 2);
    globalThis.fetch = async () => { calls++; return new Response("x".repeat(sensitivityByteLimit + 1), { headers: { "Content-Type": "application/json" } }); };
    await assert.rejects(comparePersonalDecisionWeights(session, f.baseline.summary.assessmentId, f.input)); assert.equal(calls, 3);
  } finally { globalThis.fetch = old; if (token === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token; }
});
