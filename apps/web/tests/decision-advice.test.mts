import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { resultAdviceFromCore, resultAdviceByteLimit } from "../src/lib/assessment/decision-advice.ts";
import { readPersonalDecisionAdvice } from "../src/lib/auth/core-client.ts";
import { resultAdvice, rankedAdvice } from "./fixtures/decision-advice.mts";
import { firstResult, resultAssessment, resultWorkspace } from "./fixtures/decision-results.mts";
const read = (v: unknown) => resultAdviceFromCore(v, resultWorkspace, resultAssessment, firstResult);
const session = { workspaceId: resultWorkspace, issuer: "https://identity.example.invalid", subject: "fictional-owner" };

test("owned historical advice preserves exact fields, unknowns and source dates without mutation", () => {
  for (const r of [resultAdvice(), rankedAdvice()]) { const before = structuredClone(r); assert.deepEqual(read(r), r); assert.deepEqual(r, before); }
});
test("transport retains equal-score ties and unknown preference intervals without invented ranks", () => {
  const r = rankedAdvice();
  const second = structuredClone(r.candidates[0]); second.hardChecks.optionId = "fictional-other"; r.candidates.push(second);
  const summary = structuredClone(r.summary.candidates[0]); summary.optionId = "fictional-other"; r.summary.candidates.push(summary);
  r.summary.shortlist.push(summary.optionId); r.rankGroups[0].optionIds.push(summary.optionId); assert.deepEqual(read(r), r);
  for (const candidate of r.candidates) { candidate.score!.lowerBound = 0; candidate.score!.unknownWeight = 100;
    candidate.score!.contributions[0].outcome = "UNKNOWN"; candidate.score!.contributions[0].earnedPoints = 0; }
  for (const candidate of r.summary.candidates) { candidate.score!.lowerBound = 0; candidate.score!.unknownWeight = 100; }
  r.summary.status = "UNRANKED_SHORTLIST"; r.rankGroups = []; assert.deepEqual(read(r), r);
});
const mutations: [string, (r: ReturnType<typeof resultAdvice>) => void][] = [
  ["foreign summary", r => r.summary.workspaceId = "foreign"], ["wrong digest", r => r.summary.item.reference.resultSha256 = "f".repeat(64)],
  ["approved decision", r => Object.assign(r.summary, { decisionApproved: true })], ["zero gaps", r => Object.assign(r.summary, { verificationGapCount: 0 })],
  ["duplicate option", r => r.candidates.push(structuredClone(r.candidates[0]))], ["duplicate check", r => r.candidates[0].hardChecks.findings.push(structuredClone(r.candidates[0].hardChecks.findings[0]))],
  ["verdict drift", r => r.candidates[0].hardChecks.findings[0].outcome = "FAIL"], ["identity drift", r => r.candidates[0].hardChecks.product = "Forged"],
  ["raw declared value", r => Object.assign(r.limitations[0], { declaredValue: "private" })], ["raw profile", r => Object.assign(r, { profile: {} })],
  ["unsafe evidence link", r => r.candidates[0].hardChecks.findings[0].evidence!.sourceUrl = "javascript:alert(1)"],
  ["source URL credentials", r => r.candidates[0].hardChecks.findings[0].evidence!.sourceUrl = "https://user:password@example.invalid"],
  ["invalid date", r => r.candidates[0].hardChecks.findings[0].evidence!.observedAt = "yesterday"],
  ["missing evidence field", r => { delete (r.candidates[0].hardChecks.findings[0].evidence as unknown as Record<string, unknown>).conditions; }],
  ["duplicate pattern", r => r.architecture.patterns[1] = structuredClone(r.architecture.patterns[0])],
  ["missing pattern", r => r.architecture.patterns.pop()], ["missing provisioning", r => r.architecture.provisioning.pop()],
  ["forged prerequisite readiness", r => Object.assign(r.architecture.patterns[0].prerequisites, { recommendationReady: true })],
  ["foreign prerequisite", r => r.architecture.patterns[0].prerequisites.patternId = "M2M_CLIENT_CREDENTIALS"],
  ["foreign conditional option", r => r.architecture.patterns[0].choice.conditionalOptionIds.push("foreign")],
  ["unsafe design link", r => r.architecture.patterns[0].choice.references.push("data:text/html,bad")],
  ["oversized follow-up", r => r.followUps.push("x".repeat(4001))], ["unbounded findings", r => r.candidates[0].hardChecks.findings = Array(201).fill(r.candidates[0].hardChecks.findings[0])],
];
for (const [name, mutate] of mutations) test(`advice guard refuses ${name}`, () => { const r = resultAdvice(); mutate(r); assert.throws(() => read(r)); });
test("points, contributions and ties cannot drift from the exact summary", () => {
  for (const mutate of [r => r.candidates[0].score!.contributions[0].weight = 99,
    r => r.candidates[0].score!.contributions[0].earnedPoints = 50, r => r.candidates[0].score!.contributions.push(structuredClone(r.candidates[0].score!.contributions[0])),
    r => r.rankGroups[0].optionIds.push("foreign"), r => r.rankGroups[0].rank = 2,
    r => r.candidates[0].score!.upperBound = 99] as ((r: ReturnType<typeof rankedAdvice>) => void)[]) {
    const r = rankedAdvice(); mutate(r); assert.throws(() => read(r));
  }
});
test("BFF uses one bounded fixed no-store read with session-only assertions and no retries", async () => {
  const oldFetch = globalThis.fetch, token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "fictional-advice-read-token-0000000000000000";
  let calls = 0;
  try {
    globalThis.fetch = async (url, init) => {
      calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${resultWorkspace}/assessments/${resultAssessment}/decision-results/${firstResult.resultId}/advice?version=1&resultSha256=${firstResult.resultSha256}`);
      assert.equal(init?.method, "GET"); assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.equal(init?.body, undefined);
      assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject); return Response.json(resultAdvice());
    };
    assert.deepEqual(await readPersonalDecisionAdvice(session as never, resultAssessment, firstResult), resultAdvice()); assert.equal(calls, 1);
    await assert.rejects(readPersonalDecisionAdvice(session as never, "../foreign", firstResult)); assert.equal(calls, 1);
    for (const response of [new Response("private-error", { status: 500 }), new Response("{}", { headers: { "content-type": "text/html" } }),
      Response.json(resultAdvice(), { headers: { "content-length": String(resultAdviceByteLimit + 1) } }),
      new Response(new Uint8Array(resultAdviceByteLimit + 1), { headers: { "content-type": "application/json" } }), Response.json({ ...resultAdvice(), scope: "forged" })]) {
      let reads = 0; globalThis.fetch = async () => { reads++; return response; };
      await assert.rejects(readPersonalDecisionAdvice(session as never, resultAssessment, firstResult)); assert.equal(reads, 1);
    }
    const redirected = Response.json(resultAdvice()); Object.defineProperty(redirected, "redirected", { value: true });
    globalThis.fetch = async () => redirected; await assert.rejects(readPersonalDecisionAdvice(session as never, resultAssessment, firstResult));
    globalThis.fetch = async () => new Response(null, { status: 404 }); assert.equal(await readPersonalDecisionAdvice(session as never, resultAssessment, firstResult), null);
  } finally { globalThis.fetch = oldFetch; if (token === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token; }
});
test("actual advice component explains evidence and conditions, escapes prose and never presents write/approval controls", async () => {
  const source = await readFile(new URL("../src/app/assessments/[id]/results/result-advice.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  const { ResultAdviceView } = await import(`data:text/javascript;base64,${Buffer.from(compiled.replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))).toString("base64")}`);
  const r = resultAdvice(); r.followUps.push("<script>private-value</script>"); const before = structuredClone(r);
  const html = renderToStaticMarkup(createElement(ResultAdviceView, { advice: r }));
  for (const text of ["Why this saved result?", "Fictional test evidence", "2026-10-01", "not a fresh check", "SCIM is required", "Unverified design prerequisites", "cost uncertainty", "No ranks are assigned"]) assert.ok(html.includes(text), text);
  assert.ok(html.includes('rel="noopener noreferrer"')); assert.ok(html.includes('referrerPolicy="no-referrer"'));
  assert.ok(html.includes("&lt;script&gt;private-value&lt;/script&gt;"));
  for (const text of ["<form", "<input", "<script>", session.subject, "declaredValue", "Bearer"]) assert.equal(html.includes(text), false, text);
  assert.deepEqual(r, before);
  const ranked = rankedAdvice(), second = structuredClone(ranked.candidates[0]); second.hardChecks.optionId = "fictional-tied";
  ranked.candidates.push(second); ranked.rankGroups[0].optionIds.push(second.hardChecks.optionId);
  const secondSummary = structuredClone(ranked.summary.candidates[0]); secondSummary.optionId = second.hardChecks.optionId;
  ranked.summary.candidates.push(secondSummary); ranked.summary.shortlist.push(secondSummary.optionId); assert.deepEqual(read(ranked), ranked);
  const tied = renderToStaticMarkup(createElement(ResultAdviceView, { advice: ranked }));
  assert.ok(tied.includes("tied; no automatic winner")); assert.ok(tied.includes("SAML: 100 of 100 points"));
  ranked.rankGroups = []; ranked.summary.status = "UNRANKED_SHORTLIST";
  for (const c of ranked.candidates) { c.score!.lowerBound = 0; c.score!.unknownWeight = 100;
    c.score!.contributions[0].outcome = "UNKNOWN"; c.score!.contributions[0].earnedPoints = 0; }
  for (const c of ranked.summary.candidates) { c.score!.lowerBound = 0; c.score!.unknownWeight = 100; }
  assert.deepEqual(read(ranked), ranked);
  assert.ok(renderToStaticMarkup(createElement(ResultAdviceView, { advice: ranked })).includes("100 unknown points, not confidence"));
});
