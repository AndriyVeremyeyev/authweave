import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { architectureDesignFollowUpsModuleUrl } from "./fixtures/assessment-ui.mts";
import { chunkedPreviewResponse, deferredJsonResponse } from "./fixtures/preview-stream.mts";
import { architectureConfigurationByteLimit, parseArchitectureConfigurationForm, architectureConfigurationPatterns, type ArchitectureConfigurationAnalysis } from "../src/lib/assessment/architecture-configuration.ts";
import type { ArchitecturePatternId } from "../src/lib/assessment/architecture-prerequisites.ts";
import { configurationFixture, configurationMatching } from "./fixtures/architecture-configuration.mts";
import { prerequisiteAssessmentId as id } from "./fixtures/architecture-prerequisites.mts";

const slot = `__authweave_configuration_form_${crypto.randomUUID()}`;
const globals = globalThis as unknown as Record<string, unknown>;
function moduleUrl(source: string) { return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`; }
const hooks = moduleUrl(`export function useState(initial) { const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++; if(!(i in s.slots))s.slots[i]=initial;return [s.slots[i],value=>{s.updates++;s.slots[i]=value;}]; }
  export function useRef(initial) { const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++;return s.slots[i]??={current:initial}; }
  export function useEffect(run) { const s=globalThis[${JSON.stringify(slot)}];s.cleanup??=run(); }`);
const source = await readFile(new URL("../src/app/assessments/[id]/architecture-configuration.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
  target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
  .replaceAll('"react"', JSON.stringify(hooks)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
  .replaceAll('"./architecture-design-follow-ups"', JSON.stringify(await architectureDesignFollowUpsModuleUrl()))
  .replaceAll('"@/lib/assessment/architecture-prerequisites"', JSON.stringify(new URL("../src/lib/assessment/architecture-prerequisites.ts", import.meta.url).href))
  .replaceAll('"@/lib/assessment/architecture-configuration"', JSON.stringify(new URL("../src/lib/assessment/architecture-configuration.ts", import.meta.url).href));
const { ArchitectureConfiguration } = await import(moduleUrl(compiled));
type Entries = [string, unknown][];
type View = { type: unknown; props: Record<string, unknown> };
function nodes(value: unknown): View[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const view = value as View;
  return [view, ...nodes(view.props.children)];
}
function deferred<T>() {
  let resolve: (value: T) => void = () => { throw new Error("Not initialized"); };
  let reject: (error: Error) => void = () => { throw new Error("Not initialized"); };
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
// Real component handlers with deterministic hooks/DOM and synthetic replies, not browser E2E or Core evaluation.
async function withForm(run: (form: ReturnType<typeof harness>) => Promise<void>, patternId: ArchitecturePatternId = "BFF_SESSION",
  clientScope: ArchitectureConfigurationAnalysis["clientScope"] = "SELECTED") {
  const originals = new Map(["fetch", "FormData"].map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const timeout = Object.getOwnPropertyDescriptor(AbortSignal, "timeout")!;
  const form = harness(patternId, clientScope);
  try { await run(form); } finally {
    form.memory.cleanup?.(); Reflect.deleteProperty(globals, slot);
    Object.defineProperty(AbortSignal, "timeout", timeout);
    for (const [name, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor); else Reflect.deleteProperty(globalThis, name);
    }
  }
}
function harness(patternId: ArchitecturePatternId, clientScope: ArchitectureConfigurationAnalysis["clientScope"]) {
  const memory = { cursor: 0, slots: [] as unknown[], updates: 0, cleanup: undefined as (() => void) | undefined };
  globals[slot] = memory;
  const requests: { url: string; init: RequestInit; reply: ReturnType<typeof deferred<Response>> }[] = [];
  Object.defineProperty(globalThis, "FormData", { configurable: true, value: class {
    private entries: Entries;
    constructor(entries: Entries) { this.entries = entries; }
    *[Symbol.iterator]() { yield* this.entries; }
  } });
  Object.defineProperty(globalThis, "fetch", { configurable: true, value: (url: string, init: RequestInit) => {
    const reply = deferred<Response>(); requests.push({ url, init, reply }); return reply.promise;
  } });
  const render = () => { memory.cursor = 0; return ArchitectureConfiguration({ assessmentId: id, version: 2,
    patternId, clientScope }); };
  const form = () => nodes(render()).find(node => node.type === "form")!;
  const values = (declaration = "UNKNOWN"): Entries => [["expectedVersion", "2"], ["patternId", patternId],
    ...Object.entries(configurationMatching[patternId]).map(([setting, matching]) => [setting, declaration === "UNKNOWN" ? "UNKNOWN" : declaration === "NOT_SATISFIED" && setting === "OAUTH_FLOW" ? "IMPLICIT" : matching] as [string, string])];
  const submit = (entries = values()) => (form().props.onSubmit as (event: unknown) => Promise<void>)({ preventDefault() {}, currentTarget: entries });
  const change = () => (form().props.onChange as () => void)();
  const cancel = () => (nodes(render()).find(node => node.type === "button" && node.props.type === "button")!.props.onClick as () => void)();
  const select = (setting: string, value: string) => {
    const node = nodes(render()).find(node => node.type === "select" && node.props.name === setting)!;
    (node.props.onChange as (event: unknown) => void)({ currentTarget: { value } }); change();
  };
  const html = () => renderToStaticMarkup(render());
  const response = (entries = values()) => {
    const input = parseArchitectureConfigurationForm(new URLSearchParams(entries as [string, string][]));
    const client = patternId === "NATIVE_CODE_PKCE" ? "NATIVE_MOBILE" : patternId === "M2M_CLIENT_CREDENTIALS" ? "MACHINE_TO_MACHINE" : "BROWSER";
    const clients = clientScope === "UNKNOWN" ? [] : clientScope === "SELECTED" ? [client] : [client === "BROWSER" ? "NATIVE_MOBILE" : "BROWSER"];
    return { assessmentVersion: 2, analysis: configurationFixture(input, { clients: clients as ("BROWSER" | "NATIVE_MOBILE" | "MACHINE_TO_MACHINE")[], browserTokenExposureMinimization: "REQUIRED" }).analysis };
  };
  return { memory, requests, render, form, values, submit, change, select, cancel, html, response };
}

test("all five settings forms keep matches and unknown gaps conditional on saved clients", async () => {
  for (const pattern of Object.keys(architectureConfigurationPatterns) as ArchitecturePatternId[]) for (const scope of ["SELECTED", "NOT_SELECTED", "UNKNOWN"] as const) {
    await withForm(async f => {
      assert.equal(nodes(f.render()).filter(node => node.type === "select").length, architectureConfigurationPatterns[pattern].ids.length);
      for (const node of nodes(f.render()).filter(node => node.type === "select")) assert.equal(node.props.value, "UNKNOWN");
      for (const declaration of ["UNKNOWN", "SATISFIED", "NOT_SATISFIED"]) {
        const values = f.values(declaration);
        if (declaration === "NOT_SATISFIED") values[3][1] = "UNKNOWN";
        const done = f.submit(values); f.requests.at(-1)!.reply.resolve(Response.json(f.response(values))); await done;
        const html = f.html();
        assert.match(html, /unverified/); assert.match(html, /not observed IdP settings/);
        assert.match(html, scope === "NOT_SELECTED" ? /This client type is not selected/ : scope === "UNKNOWN" || declaration === "UNKNOWN" ?
          /More information is needed/ : declaration === "SATISFIED" ? /Proposed settings match this reference design only/ : /At least one proposed setting does not match/);
        if (scope === "SELECTED" && declaration === "NOT_SATISFIED") assert.match(html, /Unknown — clarify/);
      }
    }, pattern, scope);
  }
});

test("settings follow-ups link only exact incompatible/unknown controls of each pattern and clear on edit", async () => {
  for (const pattern of Object.keys(architectureConfigurationPatterns) as ArchitecturePatternId[]) await withForm(async f => {
    const values = f.values("SATISFIED"); values[2][1] = "IMPLICIT"; values[3][1] = "UNKNOWN";
    assert.equal(f.html().includes("Next steps for this temporary preview"), false);
    const done = f.submit(values); f.requests[0].reply.resolve(Response.json(f.response(values))); await done;
    const html = f.html();
    assert.match(html, /Does not match the reference setting \(1\)/); assert.match(html, /Unknown proposal \(1\)/);
    assert.equal((html.match(/<a /g) ?? []).length, 2);
    for (const setting of ["OAUTH_FLOW", "OAUTH_CLIENT_TYPE"]) {
      assert.ok(html.includes(`href="#${pattern}-configuration-${setting}"`)); assert.ok(html.includes(`id="${pattern}-configuration-${setting}"`));
    }
    assert.equal(html.includes(`href="#${pattern}-configuration-TOKEN_LOCATION"`), false);
    assert.match(html, /not observed IdP settings/); assert.match(html, /INCOMPATIBLE_SETTING_DECLARED/);
    assert.equal(f.requests.length, 1); f.select("OAUTH_FLOW", "UNKNOWN");
    assert.equal(f.html().includes("Next steps for this temporary preview"), false); assert.equal(f.html().includes("<a "), false);
  }, pattern);
});

test("settings follow-ups send unresolved applicability to saved Context, never to guessed setting corrections", async () => {
  for (const scope of ["UNKNOWN", "NOT_SELECTED"] as const) await withForm(async f => {
    const values = f.values("SATISFIED"), done = f.submit(values);
    f.requests[0].reply.resolve(Response.json(f.response(values))); await done;
    const html = f.html();
    assert.match(html, /Next steps for this temporary preview/); assert.equal(html.includes("<a "), false);
    assert.equal(html.includes("Unknown proposal"), false); assert.equal(html.includes("No unmet or unknown"), false);
    assert.match(html, scope === "UNKNOWN" ? /not a missing design answer/ : /cannot make this pattern applicable/);
    assert.equal(f.requests.length, 1);
  }, "BFF_SESSION", scope);
});

test("all settings forms accept a valid streamed reply exactly at the response byte limit", async () => {
  const headerCases: HeadersInit[] = [{}, { "Content-Length": String(architectureConfigurationByteLimit) }, { "Content-Length": "1" }];
  for (const pattern of Object.keys(architectureConfigurationPatterns) as ArchitecturePatternId[]) await withForm(async f => {
    for (const headers of headerCases) {
      const values = f.values("SATISFIED"), bytes = new TextEncoder().encode(JSON.stringify(f.response(values)).padEnd(architectureConfigurationByteLimit, " "));
      assert.equal(bytes.length, architectureConfigurationByteLimit);
      const streamed = chunkedPreviewResponse([bytes.slice(0, 17), bytes.slice(17, -1), bytes.slice(-1)], headers);
      const done = f.submit(values); f.requests.at(-1)!.reply.resolve(streamed.response); await done;
      assert.match(f.html(), /Proposed settings match this reference design only/); assert.equal(f.form().props["aria-busy"], false);
      assert.equal(streamed.cancellations(), 0);
    }
  }, pattern);
});

test("oversized or malformed settings reply streams preserve choices and require an explicit retry", async () => {
  const encoder = new TextEncoder(), limit = architectureConfigurationByteLimit;
  for (const { bytes, headers, validJson = false } of [
    { bytes: encoder.encode(""), headers: {}, validJson: true },
    { bytes: encoder.encode(" ".repeat(limit + 1)), headers: { "Content-Length": "1" } },
    { bytes: encoder.encode("é".repeat(limit / 2 + 1)), headers: {} },
    { bytes: encoder.encode("{}"), headers: { "Content-Length": String(limit + 1) } },
    { bytes: encoder.encode("{}"), headers: { "Content-Length": "invalid" } },
    { bytes: new Uint8Array([0xff]), headers: {} },
  ]) await withForm(async f => {
    f.select("PKCE_METHOD", "S256");
    const values = f.values("SATISFIED"), chunks = validJson ? [encoder.encode(JSON.stringify(f.response(values)).padEnd(limit + 1, " "))]
      : [bytes, encoder.encode("private unread tail")];
    const streamed = chunkedPreviewResponse(chunks, headers);
    const done = f.submit(values); f.requests[0].reply.resolve(streamed.response); await done;
    const html = f.html(); assert.match(html, /The settings preview could not be read safely. Try again/);
    for (const text of ["private", "Body too large", "TypeError", "Next steps for this temporary preview", "Proposed settings match this reference design only"]) assert.equal(html.includes(text), false);
    assert.match(html, /Selected proposal: S256 — hashed challenge/);
    assert.equal(streamed.cancellations(), 1); assert.ok(streamed.pulls() <= 1);
    assert.equal(f.form().props["aria-busy"], false); assert.equal(f.requests.length, 1);
    const retry = f.submit(values); f.requests[1].reply.resolve(Response.json(f.response(values))); await retry;
    assert.match(f.html(), /Proposed settings match this reference design only/); assert.equal(f.html().includes('role="alert"'), false);
  });
});

test("local settings validation binds the visible pattern and saved version", async () => withForm(async f => {
  const invalid: Entries[] = [
    [["expectedVersion", "3"], ...f.values().slice(1)],
    [["expectedVersion", "02"], ...f.values().slice(1)],
    [["expectedVersion", "2"], ["patternId", "SPA_CODE_PKCE"]],
    [...f.values(), ["PKCE_METHOD", "S256"]],
    [...f.values(), ["workspaceId", "private workspace"]],
    [...f.values().slice(0, 2), ["PKCE_METHOD", "VERIFIED"]],
    [...f.values(), ["privateFile", new Blob(["private payload"])]] ];
  for (const values of invalid) {
    await f.submit(values); assert.equal(f.requests.length, 0);
    assert.match(f.html(), /Choose valid proposed settings for this pattern/);
    assert.equal(f.html().includes("private"), false);
  }
  const missing = f.values().slice(0, 2), done = f.submit(missing);
  f.requests[0].reply.resolve(Response.json(f.response(missing))); await done;
  assert.match(f.html(), /More information is needed/); // Existing omitted-declaration semantics remain Unknown.
}));

test("duplicate callbacks share one request, freeze proposed settings and use only the exact scoped payload", async () => withForm(async f => {
  const values = f.values("SATISFIED"), done = f.submit(values);
  await f.submit(f.values()); assert.equal(f.requests.length, 1);
  const { url, init } = f.requests[0]; assert.equal(url, `/api/assessments/${id}/architecture-configuration`);
  assert.deepEqual([...new URLSearchParams(String(init.body))], values);
  assert.equal(init.method, "POST"); assert.equal(init.credentials, "same-origin");
  assert.equal(init.cache, "no-store"); assert.equal(init.redirect, "error"); assert.ok(init.signal instanceof AbortSignal);
  assert.equal(nodes(f.render()).find(node => node.type === "fieldset")!.props.disabled, true);
  assert.equal(f.form().props["aria-busy"], true); assert.match(f.html(), /Cancel preview/);
  f.requests[0].reply.resolve(Response.json(f.response(values))); await done;
  assert.equal(f.form().props["aria-busy"], false); assert.equal(f.html().includes("Cancel preview"), false);
}));

test("cancel during body reading preserves proposed settings and a late response cannot unlock a newer explicit retry", { timeout: 2000 }, async () => withForm(async f => {
  const body = deferred<unknown>(), streamed = deferredJsonResponse(body.promise), values = f.values("SATISFIED");
  const first = f.submit(values);
  f.requests[0].reply.resolve(streamed.response);
  await streamed.reading; f.cancel(); assert.equal(f.requests[0].init.signal!.aborted, true);
  await first; assert.equal(streamed.canceled(), true);
  assert.match(f.html(), /Preview canceled. Your proposed settings are still here/);
  const second = f.submit(values);
  body.resolve(f.response(values)); await first;
  assert.equal(f.form().props["aria-busy"], true); assert.equal(f.html().includes("Proposed settings match this reference design only"), false);
  await f.submit(values); assert.equal(f.requests.length, 2);
  f.requests[1].reply.resolve(Response.json(f.response(values))); await second;
  assert.match(f.html(), /Proposed settings match this reference design only/); assert.equal(f.html().includes("Preview canceled"), false);
}));

test("changing a declaration cancels pending work, clears its result and ignores late errors", async () => withForm(async f => {
  const first = f.submit(f.values("SATISFIED")); f.change();
  assert.equal(f.requests[0].init.signal!.aborted, true); assert.equal(f.form().props["aria-busy"], false);
  f.requests[0].reply.reject(new Error("private canceled transport details")); await first;
  assert.equal(f.html().includes('role="alert"'), false); assert.match(f.html(), /No current settings result/);
  const second = f.submit(); f.requests[1].reply.resolve(Response.json(f.response())); await second;
  assert.match(f.html(), /More information is needed/); f.change(); assert.match(f.html(), /No current settings result/);
}));

test("leaving Architecture suppresses late success, refusals, transport errors and body completion", { timeout: 2000 }, async () => {
  for (const outcome of ["success", "refusal", "error", "body"] as const) await withForm(async f => {
    const body = deferred<unknown>(), streamed = deferredJsonResponse(body.promise), done = f.submit(f.values("SATISFIED"));
    if (outcome === "body") {
      f.requests[0].reply.resolve(streamed.response); await streamed.reading;
    }
    f.memory.cleanup!(); const updates = f.memory.updates; assert.equal(f.requests[0].init.signal!.aborted, true);
    if (outcome === "body") { await done; assert.equal(streamed.canceled(), true); body.resolve(f.response(f.values("SATISFIED"))); }
    else if (outcome === "error") f.requests[0].reply.reject(new Error("private late failure"));
    else f.requests[0].reply.resolve(outcome === "refusal" ? new Response(null, { status: 401 }) : Response.json(f.response(f.values("SATISFIED"))));
    await done; assert.equal(f.memory.updates, updates);
  });
});

test("refusals and unreadable or forged replies use fixed feedback, never error text, and only manual retries", async () => {
  for (const outcome of [400, 401, 403, 404, 409, 503, "network", "json", "version", "pattern", "scope", "readiness", "extra", "null"] as const) await withForm(async f => {
    const values = f.values("SATISFIED"), done = f.submit(values), reply = f.requests[0].reply, valid = f.response(values);
    if (typeof outcome === "number") reply.resolve(new Response("private response details", { status: outcome }));
    else if (outcome === "network") reply.reject(new Error("private fetch details"));
    else if (outcome === "json") reply.resolve(new Response("private invalid JSON"));
    else reply.resolve(Response.json(outcome === "version" ? { ...valid, assessmentVersion: 3 } :
      outcome === "pattern" ? { ...valid, analysis: { ...valid.analysis, patternId: "SPA_CODE_PKCE" } } :
      outcome === "scope" ? { ...valid, analysis: { ...valid.analysis, clientScope: "NOT_SELECTED" } } :
      outcome === "readiness" ? { ...valid, analysis: { ...valid.analysis, recommendationReady: true } } :
      outcome === "extra" ? { ...valid, privateDebug: "private evidence" } : null));
    await done; const html = f.html();
    assert.match(html, /role="alert"/); assert.equal(html.includes("private"), false); assert.equal(html.includes("Invalid architecture settings preview"), false);
    assert.equal(html.includes("Proposed settings match this reference design only"), false);
    assert.equal(f.form().props["aria-busy"], false); assert.equal(f.requests.length, 1);
    if (outcome === 401) assert.match(html, /session expired/);
    if (outcome === 409) assert.match(html, /assessment changed/);
    const retry = f.submit(values); assert.equal(f.requests.length, 2);
    f.requests[1].reply.resolve(Response.json(valid)); await retry;
    assert.match(f.html(), /Proposed settings match this reference design only/); assert.equal(f.html().includes('role="alert"'), false);
  });
});

test("the ten-second deadline gives fixed feedback and rejects late bodies even if transport ignores abort", { timeout: 2000 }, async () => {
  for (const stage of ["fetch", "body"] as const) await withForm(async f => {
    const clock = new AbortController(), body = deferred<unknown>(), streamed = deferredJsonResponse(body.promise);
    Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: (ms: number) => { assert.equal(ms, 10_000); return clock.signal; } });
    const done = f.submit(f.values("SATISFIED")), request = f.requests[0];
    if (stage === "body") { request.reply.resolve(streamed.response); await streamed.reading; }
    clock.abort(new DOMException("private timeout details", "TimeoutError"));
    if (stage === "body") { await done; assert.equal(streamed.canceled(), true); body.resolve(f.response(f.values("SATISFIED"))); } else request.reply.reject(clock.signal.reason);
    await done; assert.match(f.html(), /preview took too long/); assert.equal(f.html().includes("private"), false);
    assert.equal(f.form().props["aria-busy"], false); assert.equal(f.html().includes("Proposed settings match this reference design only"), false);
  });
});

test("controlled choices keep readable labels through cancel/retry and changes invalidate the current result", async () => withForm(async f => {
  f.select("PKCE_METHOD", "S256"); assert.match(f.html(), /Selected proposal: S256 — hashed challenge/);
  const entries = f.values(); entries.find(([key]) => key === "PKCE_METHOD")![1] = "S256";
  const first = f.submit(entries); f.cancel(); assert.match(f.html(), /Selected proposal: S256 — hashed challenge/);
  f.requests[0].reply.resolve(Response.json(f.response(entries))); await first;
  const retry = f.submit(entries); f.requests[1].reply.resolve(Response.json(f.response(entries))); await retry;
  assert.match(f.html(), /More information is needed/);
  f.select("PKCE_METHOD", "NONE"); assert.match(f.html(), /Selected proposal: No PKCE/); assert.match(f.html(), /No current settings result/);
}));
