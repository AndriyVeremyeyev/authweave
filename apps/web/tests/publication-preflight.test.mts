import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import ts from "typescript";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { publicationReviewFromCore, publicationReviewByteLimit, publicationReference, publicationBlockerTitle } from "../src/lib/catalog/publication-preflight.ts";
import { readCatalogPublicationPreflight } from "../src/lib/auth/core-client.ts";
import { publicationAt, publicationBinding, publicationFixture } from "./fixtures/publication-preflight.mts";

const now = new Date(publicationAt);
const config = { issuer: new URL("http://localhost:8081"), curatorScope: { projectId: "123456789012345678", organizationId: "987654321098765432" } };
const session = { issuer: "http://localhost:8081", subject: "synthetic-curator", authenticatedAt: now,
  email: null, displayName: null, workspaceId: "80000000-0000-4000-8000-000000000001", curatorScope: config.curatorScope };

test("fresh projection binds both exact input modes without exposing flags, credentials or source bodies", () => {
  for (const bootstrap of [false, true]) {
    const raw = publicationFixture(bootstrap), before = structuredClone(raw);
    const report = publicationReviewFromCore(raw, publicationBinding(bootstrap), now);
    assert.deepEqual(report.planning?.regressions.map(r => r.checkedCases), [252, 2016, 140, 36]);
    assert.equal(report.evaluatedAt, publicationAt); assert.equal(report.planning?.evaluatedAt, publicationAt);
    assert.deepEqual(raw, before); raw.blockers.pop(); assert.equal(report.blockers.length, before.blockers.length);
    for (const key of ["approvalGranted", "sourceUrl", "actor", "subject", "Authorization", "coverageComplete"]) assert.equal(key in report, false);
  }
});
test("absence is only NOT_CHECKED with an exact unavailable-input blocker and empty facts", () => {
  for (const bootstrap of [false, true]) {
    const raw = publicationFixture(bootstrap); raw.planning = null;
    assert.throws(() => publicationReviewFromCore(raw, publicationBinding(bootstrap), now));
    raw.blockers.unshift(bootstrap ? "BOOTSTRAP_REVIEW_UNAVAILABLE" : "PROPOSAL_NOT_FOUND");
    for (const key of ["total", "supporting"] as const) raw.facts[key] = 0;
    raw.facts.allFactsHaveSupportingObservation = false; raw.reviewThroughNumber = 0;
    assert.equal(publicationReviewFromCore(raw, publicationBinding(bootstrap), now).planning, null);
    raw.planning = publicationFixture().planning;
    assert.throws(() => publicationReviewFromCore(raw, publicationBinding(bootstrap), now));
  }
});
test("strict guard rejects wrong bindings, clocks, malformed counts, omitted gaps/families/blockers and authority claims", () => {
  const mutations: ((r: ReturnType<typeof publicationFixture>) => void)[] = [
    r => r.inputVersion = 4, r => r.inputId = "90000000-0000-4000-8000-000000000008", r => r.inputSha256 = "c".repeat(64),
    r => r.policyVersion = "catalog-publication-preflight-11", r => r.status = "READY", r => r.mode = "CURATED_BOOTSTRAP",
    r => r.evaluatedAt = "2026-10-07T11:59:00Z", r => r.evaluatedAt = "2026-02-30T12:00:00Z", r => r.evaluatedAt = "2026-10-07T12:00:00+00:00",
    r => r.planning!.evaluatedAt = "2026-10-07T12:00:00.123456788Z", r => r.planning!.checkedDimensions--,
    r => r.planning!.structuralVerificationGaps--, r => r.planning!.planningVerificationGaps--,
    r => r.planning!.regressions.reverse(), r => r.planning!.regressions.pop(), r => r.planning!.regressions[0].checkedCases++,
    r => r.planning!.analysisSha256 = "invalid", r => r.facts.supporting--, r => r.facts.allFactsHaveSupportingObservation = false,
    r => r.facts.stale = 1, r => r.facts.total = 6801, r => r.reviewThroughNumber = -1,
    r => r.blockers.pop(), r => r.blockers.reverse(), r => r.blockers.push("MADE_UP"), r => r.blockers.push(r.blockers[0]),
  ];
  for (const mutate of mutations) { const raw = publicationFixture(); mutate(raw); assert.throws(() => publicationReviewFromCore(raw, publicationBinding(), now)); }
  for (const field of ["actor", "sourceUrl", "candidate", "winner", "rationale"]) assert.throws(() => publicationReviewFromCore({ ...publicationFixture(), [field]: "private" }, publicationBinding(), now));
  for (const [field, value] of Object.entries(publicationFixture())) if (value === false)
    assert.throws(() => publicationReviewFromCore({ ...publicationFixture(), [field]: true }, publicationBinding(), now));
  for (const version of [-1, 0.1, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => publicationReference({ ...publicationBinding(), inputVersion: version }));
  assert.throws(() => publicationReference({ ...publicationBinding(), inputId: "../other" }));
  assert.throws(() => publicationReference({ ...publicationBinding(true), inputVersion: 0 }));
});
test("curator BFF denies missing/stale grants and invalid references without making any Core request", async () => {
  const oldFetch = globalThis.fetch; let calls = 0;
  globalThis.fetch = async () => { calls++; throw new Error("No request expected"); };
  try {
    assert.equal((await readCatalogPublicationPreflight({ ...session, curatorScope: null }, config, publicationBinding(), now)).kind, "not-granted");
    assert.equal((await readCatalogPublicationPreflight({ ...session, authenticatedAt: new Date(now.getTime() - 900_001) }, config, publicationBinding(), now)).kind, "reauth-required");
    assert.equal((await readCatalogPublicationPreflight(session, { ...config, curatorScope: null }, publicationBinding(), now)).kind, "not-configured");
    assert.equal((await readCatalogPublicationPreflight({ ...session, issuer: "http://other.invalid" }, config, publicationBinding(), now)).kind, "not-granted");
    assert.equal((await readCatalogPublicationPreflight(session, config, { ...publicationBinding(), inputSha256: "bad" }, now)).kind, "invalid");
    assert.equal(calls, 0);
  } finally { globalThis.fetch = oldFetch; }
});
test("curator BFF uses a fixed credentialed GET, no cache/redirect/body, and pins exact revision or review digest", async () => {
  const oldFetch = globalThis.fetch, token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-publication-token-000000000000000000";
  try {
    for (const bootstrap of [false, true]) {
      let calls = 0; const binding = publicationBinding(bootstrap);
      globalThis.fetch = async (input, init) => {
        calls++; assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.equal(init?.method, "GET"); assert.equal(init?.body, undefined);
        assert.ok(init?.signal instanceof AbortSignal);
        const headers = init?.headers as Record<string, string>;
        assert.equal(headers.Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
        assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject); assert.equal(headers["X-AuthWeave-Curator-Project-Id"], config.curatorScope.projectId);
        if (String(input).endsWith("/authorization")) return new Response(null, { status: 204 });
        const suffix = bootstrap ? `bootstrap-reviews/${binding.inputId}` : `proposals/${binding.inputId}/revisions/3`;
        assert.equal(String(input), `http://127.0.0.1:8080/internal/v1/catalog-curator/${suffix}/publication-preflight?expectedSha256=${binding.inputSha256}`);
        return Response.json(publicationFixture(bootstrap));
      };
      assert.equal((await readCatalogPublicationPreflight(session, config, binding, now)).kind, "ready"); assert.equal(calls, 2);
    }
  } finally { globalThis.fetch = oldFetch; if (token === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token; }
});
test("bad status, identity rejection, timeout, oversized stream, invalid JSON and stale payload never reuse a prior report or expose upstream errors", async () => {
  const oldFetch = globalThis.fetch, token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-publication-token-000000000000000000";
  try {
    for (const status of [401, 403, 404, 409, 500, 503]) {
      globalThis.fetch = async input => String(input).endsWith("/authorization") ? new Response(null, { status: 204 }) : new Response("Private upstream error", { status });
      const result = await readCatalogPublicationPreflight(session, config, publicationBinding(), now);
      assert.equal(result.kind, status === 401 || status === 403 ? "core-rejected" : "core-unavailable"); assert.ok(!JSON.stringify(result).includes("Private"));
    }
    for (const response of [new Response("invalid-json"), new Response("x".repeat(publicationReviewByteLimit + 1)),
      Response.json(publicationFixture(), { headers: { "Content-Length": String(publicationReviewByteLimit + 1) } }),
      Response.json({ ...publicationFixture(), evaluatedAt: "2026-10-06T12:00:00Z" })]) {
      globalThis.fetch = async input => String(input).endsWith("/authorization") ? new Response(null, { status: 204 }) : response;
      assert.equal((await readCatalogPublicationPreflight(session, config, publicationBinding(), now)).kind, "core-unavailable");
    }
    let cancelled = false;
    globalThis.fetch = async input => String(input).endsWith("/authorization") ? new Response(null, { status: 204 }) :
      new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array(publicationReviewByteLimit + 1)); }, cancel() { cancelled = true; } }));
    assert.equal((await readCatalogPublicationPreflight(session, config, publicationBinding(), now)).kind, "core-unavailable"); assert.equal(cancelled, true);
    globalThis.fetch = async input => { if (String(input).endsWith("/authorization")) return new Response(null, { status: 204 }); throw new DOMException("Private timeout", "TimeoutError"); };
    assert.deepEqual(await readCatalogPublicationPreflight(session, config, publicationBinding(), now), { kind: "core-unavailable" });
  } finally { globalThis.fetch = oldFetch; if (token === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = token; }
});
test("shared UI renders checked/absent/unavailable states honestly and both protected pages use it", async () => {
  const path = new URL("../src/app/catalog/publication-preflight.tsx", import.meta.url);
  const source = await readFile(path, "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"@/lib/catalog/publication-preflight"', JSON.stringify(new URL("../src/lib/catalog/publication-preflight.ts", import.meta.url).href));
  const { default: Component } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
  for (const bootstrap of [false, true]) {
    const html = renderToStaticMarkup(createElement(Component, { result: { kind: "ready", report: publicationReviewFromCore(publicationFixture(bootstrap), publicationBinding(bootstrap), now) } }));
    for (const text of ["Fresh publication check", "Publication is blocked", "136 structural", "40 structural verification gaps", "22 additional planning", "2016 synthetic", "read only", "not a completion percentage", "separate reads", "Publication-write authorization",
      "legacy read-only preflight", "separate opt-in Core publication workflow", "not a deployment-status check"]) assert.ok(html.includes(text), text);
    for (const forbidden of ["<form", "<button", "sourceUrl", "Bearer", "synthetic-curator", "private"]) assert.equal(html.includes(forbidden), false);
  }
  const unavailable = renderToStaticMarkup(createElement(Component, { result: { kind: "core-unavailable" } }));
  assert.ok(unavailable.includes("Historical receipts and other review panels cannot replace it")); assert.equal(unavailable.includes("136 structural"), false);
  assert.ok(unavailable.includes("legacy read-only preflight"));
  assert.equal(publicationBlockerTitle("PUBLICATION_WORKFLOW_UNAVAILABLE"), "This legacy preflight cannot invoke the publication workflow");
  const raw = publicationFixture(); raw.planning = null; raw.blockers.unshift("PROPOSAL_NOT_FOUND"); raw.reviewThroughNumber = 0;
  raw.facts.total = 0; raw.facts.supporting = 0; raw.facts.allFactsHaveSupportingObservation = false;
  const absent = renderToStaticMarkup(createElement(Component, { result: { kind: "ready", report: publicationReviewFromCore(raw, publicationBinding(), now) } }));
  assert.ok(absent.includes("Planning was not checked")); assert.ok(!absent.includes("136 structural"));
  for (const page of ["../src/app/catalog/review/[id]/page.tsx", "../src/app/catalog/bootstrap/page.tsx"]) {
    const pageSource = await readFile(new URL(page, import.meta.url), "utf8"); assert.ok(pageSource.includes("readCatalogPublicationPreflight(session, config"));
    assert.ok(pageSource.includes("<PublicationPreflight result={publication}"));
  }
});
