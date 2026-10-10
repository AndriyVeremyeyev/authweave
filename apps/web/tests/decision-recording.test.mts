import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { parseRecordingForm, recordingRequest, recordingAck, recordingSummaryMatches, readRecordingForm, recordingBodyLimit, postRecording } from "../src/lib/assessment/decision-recording.ts";
import { recordPersonalDecision } from "../src/lib/auth/core-client.ts";
import { firstResult, secondResult, resultAssessment as id, resultWorkspace, resultSummary, resultItem } from "./fixtures/decision-results.mts";
import { capabilityFields } from "../src/lib/assessment/capabilities.ts";
import { resultCapabilities, resultSummaryFromCore } from "../src/lib/assessment/decision-results.ts";

function form(previous = false) {
  const s = resultSummary();
  return new URLSearchParams({ resultId: previous ? secondResult.resultId : firstResult.resultId, expectedAssessmentVersion: "1",
    ...s.item.catalog, previousResultId: previous ? firstResult.resultId : "", previousVersion: previous ? "1" : "",
    previousResultSha256: previous ? firstResult.resultSha256 : "", weightMode: "EXPLICIT", weight_SAML: "100",
    confirmation: previous ? "REEVALUATE_DECISION_RESULT" : "RECORD_DECISION_RESULT" });
}
function ack(created = true) { return { scope: "OWNED_ASSESSMENT_RESULT_WRITE_ACK", assessmentId: id, reference: firstResult, created, historicalReplayVerified: true }; }
test("recording and summary dimensions match the saved capability inputs and versioned Core contract, not authentication controls", async () => {
  const schema = JSON.parse(await readFile(new URL("../../../packages/contracts/schemas/decision-result.v1.schema.json", import.meta.url), "utf8"));
  assert.deepEqual([...resultCapabilities].sort(), capabilityFields.map(f => f.capability).sort());
  assert.deepEqual([...resultCapabilities].sort(), schema.$defs.capability.enum.toSorted());
  for (const capability of resultCapabilities) {
    const p = form(); p.delete("weight_SAML"); p.set(`weight_${capability}`, "100"); assert.equal(parseRecordingForm(p).weights.values[0].capability, capability);
    const s = resultSummary(); s.weights.values[0].capability = capability; assert.deepEqual(resultSummaryFromCore(s, resultWorkspace, id, firstResult), s);
  }
  const p = form(); p.delete("weight_SAML"); p.set("weight_PASSKEYS", "100"); assert.throws(() => parseRecordingForm(p));
});
test("recording and re-evaluation parse exact keys, pins, confirmation and explicit weights without changing inputs", () => {
  for (const previous of [false, true]) {
    const p = form(previous), before = p.toString(), request = parseRecordingForm(p);
    assert.equal(request.previousResult?.version ?? 0, previous ? 1 : 0);
    assert.equal(request.schemaVersion, 1); assert.equal(request.weights.values[0].weight, 100);
    assert.deepEqual(recordingRequest(request), request); assert.equal(p.toString(), before);
  }
  const p = form(); p.set("weightMode", "NONE"); p.delete("weight_SAML");
  assert.deepEqual(parseRecordingForm(p).weights, { mode: "NONE", values: [] });
});
for (const issue of ["unknown", "duplicate", "missing", "partial-previous", "same-key", "confirmation", "zero", "leading-zero", "unsafe", "fraction", "digest", "url-version", "none-with-points", "empty-explicit", "hidden-default"])
  test(`recording form refuses ${issue} rather than normalizing or supplying defaults`, () => {
    const p = form(true);
    switch (issue) {
      case "unknown": p.set("workspaceId", resultWorkspace); break;
      case "duplicate": p.append("expectedAssessmentVersion", "1"); break;
      case "missing": p.delete("snapshotId"); break;
      case "partial-previous": p.set("previousVersion", ""); break;
      case "same-key": p.set("resultId", firstResult.resultId); break;
      case "confirmation": p.set("confirmation", "RECORD_DECISION_RESULT"); break;
      case "zero": p.set("weight_SAML", "0"); break;
      case "leading-zero": p.set("expectedAssessmentVersion", "01"); break;
      case "unsafe": p.set("expectedAssessmentVersion", "9007199254740992"); break;
      case "fraction": p.set("weight_SAML", "100.0"); break;
      case "digest": p.set("snapshotSha256", "A".repeat(64)); break;
      case "url-version": p.set("catalogVersion", "https://outside.example.invalid"); break;
      case "none-with-points": p.set("weightMode", "NONE"); break;
      case "empty-explicit": p.delete("weight_SAML"); break;
      case "hidden-default": p.set("weight_DEFAULT", "100"); break;
    }
    assert.throws(() => parseRecordingForm(p));
  });
test("request transport refuses caller clock/profile/source verdicts and invalid predecessor versions", () => {
  const r = parseRecordingForm(form());
  for (const key of ["profile", "clock", "approvedBy", "sourceVerdicts", "policy", "decision"]) assert.throws(() => recordingRequest({ ...r, [key]: {} }));
  assert.throws(() => recordingRequest({ ...r, previousResult: { ...firstResult, version: Number.MAX_SAFE_INTEGER }, confirmation: "REEVALUATE_DECISION_RESULT" }));
});
test("form streaming budget bounds raw bytes, malformed UTF-8 and declared/undeclared multi-byte bodies", async () => {
  const body = form().toString(); assert.equal((await readRecordingForm(new Request("http://localhost/", { method: "POST", body }))).toString(), body);
  for (const request of [new Request("http://localhost/", { method: "POST", body, headers: { "content-length": String(recordingBodyLimit + 1) } }),
    new Request("http://localhost/", { method: "POST", body, headers: { "content-length": "NaN" } }),
    new Request("http://localhost/", { method: "POST", body: "界".repeat(recordingBodyLimit / 2) }),
    new Request("http://localhost/", { method: "POST", body: new Uint8Array([0xff]) })]) await assert.rejects(readRecordingForm(request));
});
test("summary/ack bindings refuse another profile, catalog, predecessor, weights, result key and approval scope", () => {
  const request = parseRecordingForm(form()), s = resultSummary(); assert.equal(recordingSummaryMatches(s, request), true);
  for (const mutate of [(v: typeof s) => { v.item.assessmentVersion++; }, (v: typeof s) => { v.item.catalog.snapshotSha256 = "0".repeat(64); },
    (v: typeof s) => { v.item.previousResult = firstResult; }, (v: typeof s) => { v.weights.values[0].weight = 99; },
    (v: typeof s) => { v.item.reference = secondResult; }]) { const v = structuredClone(s); mutate(v); assert.equal(recordingSummaryMatches(v, request), false); }
  assert.deepEqual(recordingAck(ack(), id, request), ack());
  for (const v of [{ ...ack(), assessmentId: "foreign" }, { ...ack(), reference: secondResult }, { ...ack(), created: "true" },
    { ...ack(), historicalReplayVerified: false }, { ...ack(), approved: true }]) assert.throws(() => recordingAck(v, id, request));
});
test("one-shot browser writes use same-origin and exact payload/key; explicit identical retries do not regenerate inputs", async () => {
  const body = form().toString(); let calls = 0;
  const fetcher: typeof fetch = async (url, init) => {
    calls++; assert.equal(String(url), `/api/assessments/${id}/decision-results`); assert.equal(init?.method, "POST"); assert.equal(init?.body, body);
    assert.equal(init?.credentials, "same-origin"); assert.equal(init?.mode, "same-origin"); assert.equal(init?.redirect, "error"); assert.equal(init?.cache, "no-store");
    return Response.json(ack(calls === 1), { status: calls === 1 ? 201 : 200 });
  };
  assert.equal((await postRecording(id, body, fetcher)).outcome, "saved"); assert.equal(calls, 1);
  assert.equal((await postRecording(id, body, fetcher)).outcome, "saved"); assert.equal(calls, 2);
  assert.equal((await postRecording("../foreign", body, fetcher)).outcome, "denied"); assert.equal(calls, 2);
  assert.equal((await postRecording(id, body + "&clock=forged", fetcher)).outcome, "denied"); assert.equal(calls, 2);
});
test("lost/unreadable replies including post-commit 409 remain unconfirmed, never auto-retry or acknowledge another version", async () => {
  const body = form().toString(); let calls = 0;
  for (const reply of [new Response("private", { status: 503 }), new Response(null, { status: 409 }), Response.json({ ...ack(), reference: secondResult }, { status: 201 }),
    Response.json(ack(false), { status: 201 }), new Response("{}", { headers: { "content-type": "text/html" } }),
    new Response("x".repeat(4097), { headers: { "content-type": "application/json" } }), Response.redirect("https://outside.example.invalid")]) {
    const before = calls, result = await postRecording(id, body, async () => { calls++; return reply; });
    assert.equal(result.outcome, "uncertain"); assert.equal(calls, before + 1);
  }
  assert.deepEqual(await postRecording(id, body, async () => { throw new Error("Lost reply"); }), { outcome: "uncertain", conflict: false });
  for (const status of [400, 401, 403, 404, 413, 415]) assert.equal((await postRecording(id, body, async () => new Response("private", { status }))).outcome, "denied");
});
test("server client sends only the exact request and owner assertion, verifies summary before bounded acknowledgement", async () => {
  const oldFetch = globalThis.fetch, token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const session = { workspaceId: resultWorkspace, issuer: "https://identity.example.invalid", subject: "fictional-owner" };
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "fictional-write-token-000000000000000000000000000";
  const input = parseRecordingForm(form()); let calls = 0;
  try {
    globalThis.fetch = async (url, init) => {
      calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${resultWorkspace}/assessments/${id}/decision-results/summary`);
      assert.equal(init?.method, "POST"); assert.deepEqual(JSON.parse(String(init?.body)), input); assert.equal(init?.redirect, "error"); assert.equal(init?.cache, "no-store");
      assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
      return Response.json(resultSummary(), { status: 201 });
    };
    assert.deepEqual(await recordPersonalDecision(session as never, id, input), ack()); assert.equal(calls, 1);
    for (const reply of [Response.json({ ...resultSummary(), item: { ...resultItem(), catalog: { ...resultItem().catalog, snapshotSha256: "0".repeat(64) } } }, { status: 201 }),
      Response.json({ ...resultSummary(), workspaceId: "foreign" }, { status: 201 }), Response.json(resultSummary(), { status: 201, headers: { "content-length": "262145" } }),
      new Response("private", { status: 500 }), new Response("{}", { status: 201, headers: { "content-type": "text/html" } })]) {
      let attempts = 0; globalThis.fetch = async () => { attempts++; return reply; }; await assert.rejects(recordPersonalDecision(session as never, id, input)); assert.equal(attempts, 1);
    }
    for (const status of [400, 401, 403, 404, 409, 413]) { globalThis.fetch = async () => new Response("private", { status }); assert.equal(await recordPersonalDecision(session as never, id, input), status); }
  } finally { globalThis.fetch = oldFetch; if (token === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token; }
});
test("actual recording component renders unselected initial catalog, unchecked explicit confirmation and no guessed weights", async () => {
  const url = (s: string) => `data:text/javascript;base64,${Buffer.from(s).toString("base64")}`;
  const link = url(`import { jsx } from ${JSON.stringify(import.meta.resolve("react/jsx-runtime"))}; export default function Link({prefetch,children,...props}) { return jsx("a",{...props,children}); }`);
  const navigation = url("export function useRouter() { return { push() {} }; }");
  const source = await readFile(new URL("../src/app/assessments/[id]/results/new/recording-form.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  const component = await import(url(compiled.replaceAll('"next/link"', JSON.stringify(link)).replaceAll('"next/navigation"', JSON.stringify(navigation))
    .replaceAll('"react"', JSON.stringify(import.meta.resolve("react"))).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"@/lib/assessment/decision-results"', JSON.stringify(new URL("../src/lib/assessment/decision-results.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/decision-recording"', JSON.stringify(new URL("../src/lib/assessment/decision-recording.ts", import.meta.url).href))));
  const html = renderToStaticMarkup(createElement(component.DecisionRecordingForm, { assessmentId: id, assessmentVersion: 1, resultId: firstResult.resultId, previous: null, preferred: ["SAML"] }));
  for (const s of ["No catalog is selected automatically", "name=\"snapshotId\"", "name=\"confirmation\"", "name=\"weight_SAML\"", "disabled=\"\"", "Idempotency key", "This does not approve"]) assert.ok(html.includes(s), s);
  assert.equal(html.includes("checked="), false); assert.equal(html.includes("Bearer"), false); assert.equal(html.includes(resultItem().catalog.snapshotSha256), false);
  const none = renderToStaticMarkup(createElement(component.DecisionRecordingForm, { assessmentId: id, assessmentVersion: 1, resultId: secondResult.resultId, previous: resultItem(), preferred: [] }));
  assert.ok(none.includes("NONE with no points")); assert.ok(none.includes(firstResult.resultSha256)); assert.ok(none.includes("Older results stay unchanged"));
});
