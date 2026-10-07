import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { boundedPrerequisiteText, InvalidPrerequisiteForm, parsePrerequisiteForm, prerequisiteAnalysis,
  prerequisiteIds, type ArchitecturePatternId, type DesignDeclaration } from "../src/lib/assessment/architecture-prerequisites.ts";
import { previewPersonalArchitecturePrerequisites } from "../src/lib/auth/core-client.ts";
import { prerequisiteAssessmentId as id, prerequisiteWorkspaceId as workspaceId, prerequisiteFixture,
  prerequisiteInput, prerequisiteProfile } from "./fixtures/architecture-prerequisites.mts";
import { architectureDesignFollowUpsModuleUrl } from "./fixtures/assessment-ui.mts";

test("what-if form renders eleven labelled unknown-by-default controls and explicit temporary-design limits", async () => {
  const source = await readFile(new URL("../src/app/assessments/[id]/architecture-prerequisites.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"react"', JSON.stringify(import.meta.resolve("react")))
    .replaceAll('"./architecture-design-follow-ups"', JSON.stringify(await architectureDesignFollowUpsModuleUrl()))
    .replaceAll('"@/lib/assessment/architecture-prerequisites"',
      JSON.stringify(new URL("../src/lib/assessment/architecture-prerequisites.ts", import.meta.url).href));
  const component = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
  let controlCount = 0;
  for (const patternId of Object.keys(prerequisiteIds) as ArchitecturePatternId[]) {
    const descriptions = prerequisiteIds[patternId].map(id => `Synthetic condition ${id}`);
    const html = renderToStaticMarkup(createElement(component.ArchitecturePrerequisites,
      { assessmentId: id, version: 2, patternId, descriptions, clientScope: "SELECTED" }));
    controlCount += (html.match(/<select /g) ?? []).length;
    assert.equal((html.match(/value="UNKNOWN" selected=""/g) ?? []).length, descriptions.length);
    assert.ok(html.includes("are temporary and are not saved"));
    assert.ok(html.includes("No IdP configuration is read or changed"));
    assert.ok(html.includes('aria-live="polite"'));
    for (const condition of prerequisiteIds[patternId]) {
      assert.ok(html.includes(`for="${patternId}-${condition}"`));
      assert.ok(html.includes(`id="${patternId}-${condition}" name="${condition}"`));
    }
  }
  assert.equal(controlCount, 11);
});

test("typed prerequisite form rejects scope, authority, foreign keys, duplicates and unsafe versions", () => {
  const valid = new URLSearchParams({ expectedVersion: "2", patternId: "BFF_SESSION", BFF_SESSION_DEFENSES: "UNKNOWN" });
  assert.equal(parsePrerequisiteForm(valid).declarations.BFF_SESSION_DEFENSES, "UNKNOWN");
  for (const [key, value] of [["clientScope", "SELECTED"], ["approvalGranted", "true"], ["workspaceId", workspaceId],
    ["SPA_TOKEN_THREAT_MODEL", "SATISFIED"], ["BFF_SESSION_DEFENSES", "SATISFIED"], ["patternId", "BFF_SESSION"],
    ["expectedVersion", "2"], ["__proto__", "UNKNOWN"]]) {
    const invalid = new URLSearchParams(valid); invalid.append(key, value);
    assert.throws(() => parsePrerequisiteForm(invalid), InvalidPrerequisiteForm);
  }
  for (const [key, value] of [["expectedVersion", "9007199254740992"], ["expectedVersion", "02"],
    ["expectedVersion", "-1"], ["patternId", "toString"], ["BFF_SESSION_DEFENSES", "VERIFIED"]]) {
    const invalid = new URLSearchParams(valid); invalid.set(key, value);
    assert.throws(() => parsePrerequisiteForm(invalid), InvalidPrerequisiteForm);
  }
  const missing = new URLSearchParams(valid); missing.delete("expectedVersion");
  assert.throws(() => parsePrerequisiteForm(missing), InvalidPrerequisiteForm);
});

for (const patternId of Object.keys(prerequisiteIds) as ArchitecturePatternId[]) {
  for (const scope of ["SELECTED", "NOT_SELECTED", "UNKNOWN"] as const) {
    for (const declaration of ["SATISFIED", "NOT_SATISFIED", "UNKNOWN"] as DesignDeclaration[]) {
      test(`${patternId} ${scope} ${declaration} stays conditional and scope-bound`, () => {
        const input = { expectedVersion: 2, patternId, declarations: Object.fromEntries(prerequisiteIds[patternId].map(id => [id, declaration])) };
        const client = patternId === "NATIVE_CODE_PKCE" ? "NATIVE_MOBILE" : patternId === "M2M_CLIENT_CREDENTIALS" ? "MACHINE_TO_MACHINE" : "BROWSER";
        const clients = scope === "UNKNOWN" ? [] : scope === "SELECTED" ? [client] : [client === "BROWSER" ? "NATIVE_MOBILE" : "BROWSER"];
        const value = prerequisiteFixture(input, clients).analysis;
        const result = prerequisiteAnalysis(value, input, scope);
        assert.equal(result.configurationVerified, false);
        assert.equal(result.status, scope === "NOT_SELECTED" ? "NOT_APPLICABLE" :
          scope === "UNKNOWN" || declaration === "UNKNOWN" ? "NEEDS_INFORMATION" :
            declaration === "SATISFIED" ? "CONDITIONALLY_MATCHES" : "CONDITIONALLY_DOES_NOT_MATCH");
        for (const forged of [{ ...value, recommendationReady: true }, { ...value, configurationVerified: true },
          { ...value, providerCompatibilityVerified: true }, { ...value, approvalGranted: true },
          { ...value, checks: value.checks.slice(1) }, { ...value, checks: [...value.checks].reverse() },
          { ...value, clientScope: scope === "SELECTED" ? "UNKNOWN" : "SELECTED" }]) {
          assert.throws(() => prerequisiteAnalysis(forged, input, scope));
        }
      });
    }
  }
}

test("stream limits count bytes without trusting Content-Length and cancel oversized chunks", async () => {
  assert.equal(await boundedPrerequisiteText(new Response("test"), 4), "test");
  await assert.rejects(boundedPrerequisiteText(new Response("ééé"), 4), RangeError);
  await assert.rejects(boundedPrerequisiteText(new Response("test", { headers: { "Content-Length": "100" } }), 4), RangeError);
  let cancelled = false;
  const stream = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(2049)); }, cancel() { cancelled = true; } });
  await assert.rejects(boundedPrerequisiteText(new Response(stream), 2048), RangeError);
  assert.equal(cancelled, true);
});

test("personal preview is exact-version, fixed-origin, body-free and cannot promote readiness", async () => {
  const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  const session = { workspaceId, issuer: "http://localhost:8081", subject: "synthetic-prerequisite-user",
    email: null, displayName: null, authenticatedAt: new Date() };
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-prerequisite-token-000000000000000000000";
  const calls: string[] = [];
  let value = prerequisiteFixture(), status = 200, version = 2;
  globalThis.fetch = async (url, init) => {
    calls.push(`${init?.method} ${url}`);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], session.subject);
    if (init?.method === "GET") return Response.json({ id, workspaceId, status: "DRAFT", version, profileSchemaVersion: 6, profile: prerequisiteProfile });
    assert.equal(String(url), `http://127.0.0.1:8080/api/v1/workspaces/${workspaceId}/assessments/${id}/architecture-prerequisite-preview`);
    assert.deepEqual(JSON.parse(String(init?.body)), prerequisiteInput);
    return status === 200 ? Response.json(value) : new Response(null, { status });
  };
  try {
    const result = await previewPersonalArchitecturePrerequisites(session, id, prerequisiteInput);
    assert.equal(result.kind, "preview");
    if (result.kind === "preview") assert.deepEqual(Object.keys(result.preview).sort(), ["analysis", "assessmentVersion"]);
    assert.equal(calls.length, 2);
    const valid = prerequisiteFixture();
    for (const invalid of [{ ...valid, declarations: {} },
      { ...valid, preflight: { ...valid.preflight, workspaceId: "70000000-0000-4000-8000-000000000002" } },
      { ...valid, preflight: { ...valid.preflight, assessmentVersion: 3 } },
      { ...valid, analysis: { ...valid.analysis, configurationVerified: true } },
      { ...valid, analysis: { ...valid.analysis, status: "CONDITIONALLY_MATCHES" } },
      { ...valid, winner: "BFF_SESSION" }]) {
      value = invalid as typeof value;
      await assert.rejects(previewPersonalArchitecturePrerequisites(session, id, prerequisiteInput));
    }
    value = valid;
    for (const [code, kind] of [[409, "conflict"], [400, "invalid"], [404, "not-found"]] as const) {
      status = code; assert.equal((await previewPersonalArchitecturePrerequisites(session, id, prerequisiteInput)).kind, kind);
    }
    status = 403; await assert.rejects(previewPersonalArchitecturePrerequisites(session, id, prerequisiteInput));
    version = 3; const count = calls.length;
    assert.equal((await previewPersonalArchitecturePrerequisites(session, id, prerequisiteInput)).kind, "conflict");
    assert.equal(calls.length, count + 1);
    assert.ok(calls.every(call => call.startsWith("GET ") || call.startsWith("POST ")));
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});
