import assert from "node:assert/strict";
import test from "node:test";
import { decisionBriefByteLimit, decisionBriefFilename, decisionBriefForm, decisionBriefFormat,
  DecisionBriefDownloadError, InvalidDecisionBriefRequest, requestSavedDecisionBrief, savedDecisionMarkdown } from "../src/lib/assessment/decision-brief.ts";
import { resultAdvice, rankedAdvice } from "./fixtures/decision-advice.mts";
import { firstResult, resultAssessment, resultWorkspace } from "./fixtures/decision-results.mts";
const markdown = (advice = resultAdvice()) => savedDecisionMarkdown(advice, resultWorkspace, resultAssessment, firstResult);
const unescape = (text: string) => text.replace(/\\([!-~])/g, "$1");

test("compact brief pins the original result/profile/catalog/policy/clock without mutating or granting authority", () => {
  const advice = resultAdvice(), before = structuredClone(advice), text = markdown(advice), decoded = unescape(text);
  assert.equal(text, markdown(advice)); assert.deepEqual(advice, before); assert.ok(text.endsWith("\n"));
  for (const value of [firstResult.resultId, firstResult.resultSha256, resultAssessment, advice.summary.profileSha256,
    advice.summary.policySha256, advice.summary.evaluatedAt, advice.summary.item.catalog.snapshotSha256]) assert.ok(text.includes(value));
  for (const value of ["NEEDS_INFORMATION", "UNKNOWN", "22", "External source verification performed: false",
    "Configuration verified: false", "Compliance verified: false", "Decision approved: false", "not a final ADR",
    "No latest version was substituted", "Temporary", "Points and unknown intervals are not confidence"]) assert.ok(decoded.toLowerCase().includes(value.toLowerCase()), value);
  for (const value of [resultWorkspace, "declaredValue", "oidcSubject", "oidcIssuer", "source.example.invalid", "Only the exact fictional plan", "claimSha256"])
    assert.equal(text.includes(value), false, value);
  assert.ok(text.includes("omits per-check evidence")); assert.ok(text.includes("review/audit records"));
  assert.ok(new TextEncoder().encode(text).byteLength < decisionBriefByteLimit);
});

test("ranked, tied and no-preference saved outcomes remain distinct and retain original weights", () => {
  const ranked = rankedAdvice(); assert.ok(markdown(ranked).includes("Rank 1:"));
  assert.ok(markdown(ranked).includes("SAML weight: 100 of 100")); assert.ok(markdown(ranked).includes("100–100"));
  const other = structuredClone(ranked.candidates[0]); other.hardChecks.optionId = "fictional-other-plan";
  const summary = structuredClone(ranked.summary.candidates[0]); summary.optionId = other.hardChecks.optionId;
  ranked.candidates.push(other); ranked.summary.candidates.push(summary); ranked.summary.shortlist.push(summary.optionId); ranked.rankGroups[0].optionIds.push(summary.optionId);
  assert.ok(markdown(ranked).includes("tied; no automatic winner"));
  const none = resultAdvice(); none.summary.weights = { mode: "NONE", values: [] };
  assert.ok(markdown(none).includes("Original weight mode: NONE")); assert.ok(markdown(none).includes("No ranks assigned"));
  assert.equal(markdown(none).includes("SAML weight:"), false);
});

test("brief blocker counts share the UI grouping without merging checks, options or provider plans", () => {
  const advice = resultAdvice(), c = advice.candidates[0];
  c.hardChecks.findings.push({ ...structuredClone(c.hardChecks.findings[0]), checkId: "OTHER_CLIENT" });
  const second = structuredClone(c), summary = structuredClone(advice.summary.candidates[0]);
  second.hardChecks.optionId = summary.optionId = "fictional-second-plan";
  advice.candidates.push(second); advice.summary.candidates.push(summary);
  const excluded = structuredClone(c), excludedSummary = structuredClone(advice.summary.candidates[0]);
  excluded.hardChecks.optionId = excludedSummary.optionId = "fictional-excluded";
  excluded.hardChecks.hardVerdict = excludedSummary.hardVerdict = "EXCLUDED";
  excluded.hardChecks.findings[0].outcome = "FAIL"; excluded.hardChecks.findings[0].reasonCode = "UNSUPPORTED";
  advice.candidates.push(excluded); advice.summary.candidates.push(excludedSummary);
  const text = unescape(markdown(advice)), blockers = text.split("## Blocking requirements")[1].split("## Conditional architecture")[0];
  assert.ok(blockers.includes("UNKNOWN / REQUIRED_UNKNOWN: 2 scoped options, 4 checks"));
  assert.ok(blockers.includes("FAIL / UNSUPPORTED: 1 scoped options, 1 checks"));
  assert.ok(blockers.indexOf("FAIL / UNSUPPORTED") < blockers.indexOf("UNKNOWN / REQUIRED_UNKNOWN"));
  assert.ok(blockers.includes("fictional-second-plan: 2 checks")); assert.ok(blockers.includes("fictional-excluded: 1 checks"));
});

test("brief does not collapse conditional architecture, prerequisites, SCIM/JIT trade-offs or deployment limitations", () => {
  const text = unescape(markdown()), advice = resultAdvice();
  for (const pattern of advice.architecture.patterns) {
    assert.ok(text.includes(`### ${pattern.choice.id}`));
    for (const c of pattern.prerequisites.checks) assert.ok(text.includes(`${c.prerequisiteId}: UNKNOWN`));
  }
  for (const phrase of ["Explicit scoped design", "Needs lifecycle verification", "Test offboarding and access revocation",
    "Required SCIM cannot be replaced", "REQUIRED_SCIM_CANNOT_BE_REPLACED_BY_JIT", "API protection: NEEDS_INFORMATION",
    "blocks verified deployment recommendation", "Confirm workload and cost", "Obtain manual source review", "Neither proves a free tier"]) assert.ok(text.includes(phrase), phrase);
});

test("untrusted prose cannot inject headings, HTML, links, images, fences or invisible controls", () => {
  const advice = resultAdvice(), attack = "<script>run</script>\n# Approved\n![img](https://example.invalid)\n```\u0000\u202e";
  advice.followUps.push(attack); advice.architecture.patterns[0].choice.pros.push(attack);
  advice.candidates[0].hardChecks.configuration = attack;
  const text = markdown(advice);
  for (const value of ["<script>", "\n# Approved", "![img]", "https://example.invalid", "\n```", "\u0000", "\u202e"]) assert.equal(text.includes(value), false);
  assert.ok(unescape(text).includes("[U+0000]")); assert.ok(unescape(text).includes("[U+202E]"));
});

test("formatter fails closed on forged scope, extra raw data or mismatched version/digest/owner", () => {
  for (const mutate of [a => Object.assign(a, { profile: { private: "raw" } }), a => Object.assign(a.limitations[0], { declaredValue: "private" }),
    a => a.summary.workspaceId = "foreign", a => a.summary.assessmentId = "foreign",
    a => a.summary.item.reference.version = 2, a => a.summary.item.reference.resultSha256 = "f".repeat(64),
    a => Object.assign(a.summary, { decisionApproved: true })] as ((a: ReturnType<typeof resultAdvice>) => void)[]) {
    const advice = resultAdvice(); mutate(advice); assert.throws(() => markdown(advice));
  }
  const huge = resultAdvice(); huge.followUps = Array(200).fill("!".repeat(4000));
  assert.throws(() => markdown(huge), RangeError); // No truncation pretending to be a complete brief.
});

test("download input allows only exact canonical version/digest and safe attachment identifiers", () => {
  const form = new URLSearchParams({ version: "1", resultSha256: firstResult.resultSha256 });
  assert.deepEqual(decisionBriefForm(form, firstResult.resultId), firstResult);
  for (const value of ["0", "01", "1.0", "1e2", "-1", "Infinity", "9007199254740992"])
    assert.throws(() => decisionBriefForm(new URLSearchParams({ version: value, resultSha256: firstResult.resultSha256 }), firstResult.resultId), InvalidDecisionBriefRequest);
  for (const extra of ["version=1", "workspaceId=other", "profile=raw", "approval=true", "clock=now", "resultSha256=bad"])
    assert.throws(() => decisionBriefForm(new URLSearchParams(`${form}&${extra}`), firstResult.resultId));
  for (const bad of ["", "../other", `${resultAssessment}\r\nprivate=1`]) assert.throws(() => decisionBriefFilename(bad, firstResult));
});

test("download reader requires one bounded exact fixed-origin attachment and never accepts private errors", async () => {
  const oldFetch = globalThis.fetch, text = markdown(), filename = decisionBriefFilename(resultAssessment, firstResult);
  const headers = { "Content-Type": "text/markdown; charset=utf-8", "Content-Disposition": `attachment; filename="${filename}"`, "Cache-Control": "no-store" };
  let calls = 0;
  try {
    globalThis.fetch = async (url, init) => {
      calls++; assert.equal(url, `/api/assessments/${resultAssessment}/decision-results/${firstResult.resultId}/brief`);
      assert.equal(init?.method, "POST"); assert.equal(init?.credentials, "same-origin"); assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
      assert.equal(init?.body, `version=1&resultSha256=${firstResult.resultSha256}`); return new Response(text, { headers });
    };
    await assert.rejects(requestSavedDecisionBrief("../other", firstResult)); assert.equal(calls, 0);
    assert.deepEqual(await requestSavedDecisionBrief(resultAssessment, firstResult), { filename, markdown: text }); assert.equal(calls, 1);
    for (const status of [401, 403, 404, 409, 503]) {
      globalThis.fetch = async () => new Response("private credential", { status });
      await assert.rejects(requestSavedDecisionBrief(resultAssessment, firstResult), e => e instanceof DecisionBriefDownloadError &&
        e.kind === (status === 401 ? "session" : status === 404 ? "not-found" : "unavailable") && !e.message.includes("private credential"));
    }
    const replies = [new Response(text, { headers: { ...headers, "Content-Type": "text/html" } }),
      new Response(text, { headers: { ...headers, "Cache-Control": "public" } }),
      new Response(text, { headers: { ...headers, "Content-Disposition": 'attachment; filename="latest.md"' } }),
      ...[text.replace("- Result version: `1`", "- Result version: `2`"), text.replace(firstResult.resultSha256, "f".repeat(64)),
        text.replace(firstResult.resultId, "90000000-0000-4000-8000-000000000002"), text.replace(decisionBriefFormat, "other-format"),
        "x".repeat(decisionBriefByteLimit + 1)].map(t => new Response(t, { headers }))];
    const redirected = new Response(text, { headers }); Object.defineProperty(redirected, "redirected", { value: true }); replies.push(redirected);
    for (const reply of replies) { globalThis.fetch = async () => reply; await assert.rejects(requestSavedDecisionBrief(resultAssessment, firstResult)); }
  } finally { globalThis.fetch = oldFetch; }
});

test("download reader forwards cancellation and does not send an already-cancelled request", async () => {
  const oldFetch = globalThis.fetch, controller = new AbortController(); let calls = 0;
  try {
    globalThis.fetch = async (_url, init) => {
      calls++; assert.ok(init?.signal instanceof AbortSignal);
      return new Promise<Response>((_done, fail) => init.signal!.addEventListener("abort", () => fail(init.signal!.reason), { once: true }));
    };
    const request = requestSavedDecisionBrief(resultAssessment, firstResult, controller.signal); controller.abort();
    await assert.rejects(request); assert.equal(calls, 1);
    await assert.rejects(requestSavedDecisionBrief(resultAssessment, firstResult, controller.signal)); assert.equal(calls, 1);
  } finally { globalThis.fetch = oldFetch; }
});
