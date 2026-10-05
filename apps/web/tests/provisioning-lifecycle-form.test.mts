import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import type { LifecyclePattern } from "../src/lib/assessment/provisioning-lifecycle.ts";
import { lifecycleV2Conditions, lifecycleV2PatternConditions, lifecycleGroupConditions, parseLifecycleV2Form, type LifecycleGroupStrategy } from "../src/lib/assessment/provisioning-lifecycle-v2.ts";
import { lifecycleAssessmentId as id, lifecycleV2Fixture, lifecycleRequirements } from "./fixtures/provisioning-lifecycle-v2.mts";

const slot = `__authweave_lifecycle_form_${crypto.randomUUID()}`;
const globals = globalThis as unknown as Record<string, unknown>;
const moduleUrl = (source: string) => `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
const hooks = moduleUrl(`export function useState(initial){const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++;if(!(i in s.slots))s.slots[i]=initial;return [s.slots[i],v=>{s.updates++;s.slots[i]=v;}];}
export function useRef(initial){const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++;return s.slots[i]??={current:initial};}
export function useEffect(run){const s=globalThis[${JSON.stringify(slot)}];s.cleanup??=run();}`);
const source = await readFile(new URL("../src/app/assessments/[id]/provisioning-lifecycle.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
  .replaceAll('"react"', JSON.stringify(hooks)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
  .replaceAll('"@/lib/assessment/provisioning-lifecycle"', JSON.stringify(new URL("../src/lib/assessment/provisioning-lifecycle.ts", import.meta.url).href))
  .replaceAll('"@/lib/assessment/provisioning-lifecycle-v2"', JSON.stringify(new URL("../src/lib/assessment/provisioning-lifecycle-v2.ts", import.meta.url).href))
  .replaceAll('"@/lib/assessment/architecture-prerequisites"', JSON.stringify(new URL("../src/lib/assessment/architecture-prerequisites.ts", import.meta.url).href))
  .replaceAll('"./assessment-workflow"', JSON.stringify(moduleUrl(`export const AssessmentStepButton=()=>null;`)));
const { LifecycleConditions, ProvisioningLifecycle } = await import(moduleUrl(compiled));
type View = { type: unknown; key?: string; props: Record<string, unknown> };
const nodes = (value: unknown): View[] => {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const view = value as View; return [view, ...nodes(view.props.children)];
};
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function harness(patternId: LifecyclePattern) {
  const memory = { cursor: 0, slots: [] as unknown[], updates: 0, cleanup: undefined as (() => void) | undefined }; globals[slot] = memory;
  const requests: { url: string; init: RequestInit; reply: ReturnType<typeof deferred<Response>> }[] = [];
  Object.defineProperty(globalThis, "FormData", { configurable: true, value: class {
    private entries: [string, unknown][];
    constructor(entries: [string, unknown][]) { this.entries = entries; } *[Symbol.iterator]() { yield* this.entries; }
  } });
  globalThis.fetch = ((url: string, init: RequestInit) => { const reply = deferred<Response>(); requests.push({ url, init, reply }); return reply.promise; }) as typeof fetch;
  const render = () => { memory.cursor = 0; return LifecycleConditions({ assessmentId: id, version: 2, patternId, requirements: lifecycleRequirements }); };
  const form = () => nodes(render()).find(n => n.type === "form")!;
  const groupSelect = () => nodes(render()).find(n => n.type === "select" && n.props.name === "groupStrategy")!;
  const values = (declaration = "UNKNOWN"): [string, string][] => {
    const groupStrategy = groupSelect().props.value as LifecycleGroupStrategy;
    return [["expectedVersion", "2"], ["patternId", patternId], ["groupStrategy", groupStrategy], ...lifecycleV2Conditions(patternId, groupStrategy).map(id => [id, declaration] as [string, string])];
  };
  const submit = (entries: [string, unknown][] = values()) => (form().props.onSubmit as (event: unknown) => Promise<void>)({ preventDefault() {}, currentTarget: entries });
  const html = () => renderToStaticMarkup(render());
  const response = (entries = values()) => { const raw = lifecycleV2Fixture(parseLifecycleV2Form(new URLSearchParams(entries))); return { assessmentVersion: 2, analysis: raw.analysis }; };
  return { memory, requests, render, form, values, submit, html, response,
    chooseGroup: (value: string) => (groupSelect().props.onChange as (event: unknown) => void)({ currentTarget: { value }, stopPropagation() {} }),
    change: () => (form().props.onChange as () => void)(),
    cancel: () => (nodes(render()).find(n => n.type === "button" && n.props.type === "button")!.props.onClick as () => void)() };
}
async function withForm(run: (f: ReturnType<typeof harness>) => Promise<void>, pattern: LifecyclePattern = "SCIM_PUSH") {
  const fetch = globalThis.fetch, formData = Object.getOwnPropertyDescriptor(globalThis, "FormData")!, timeout = Object.getOwnPropertyDescriptor(AbortSignal, "timeout")!;
  const f = harness(pattern);
  try { await run(f); } finally { f.memory.cleanup?.(); Reflect.deleteProperty(globals, slot); globalThis.fetch = fetch;
    Object.defineProperty(globalThis, "FormData", formData); Object.defineProperty(AbortSignal, "timeout", timeout); }
}
test("all three real form handlers show conditional results, saved hard checks and every unknown gap", async () => {
  for (const pattern of Object.keys(lifecycleV2PatternConditions) as LifecyclePattern[]) await withForm(async f => {
    assert.equal(nodes(f.render()).filter(n => n.type === "select").length, lifecycleV2PatternConditions[pattern].length + 1);
    for (const n of nodes(f.render()).filter(n => n.type === "select" && n.props.name !== "groupStrategy")) assert.equal(n.props.defaultValue, "UNKNOWN");
    assert.equal(nodes(f.render()).find(n => n.type === "select" && n.props.name === "groupStrategy")!.props.value, "UNKNOWN");
    f.chooseGroup("NONE");
    for (const declaration of ["UNKNOWN", "SATISFIED", "NOT_SATISFIED"]) {
      const entries = f.values(declaration), done = f.submit(entries); f.requests.at(-1)!.reply.resolve(Response.json(f.response(entries))); await done;
      assert.match(f.html(), /not a recommendation, approval or ready-to-deploy design/);
      assert.match(f.html(), pattern === "JIT_LOGIN" || declaration === "NOT_SATISFIED" ? /proposed design has a mismatch/ : declaration === "UNKNOWN" ? /More information is needed/ : /proposed design only — unverified/);
      if (pattern === "JIT_LOGIN") assert.match(f.html(), /Required mechanism is missing/);
      assert.match(f.html(), /Not required — not a passed check/);
    }
  }, pattern);
});
test("cards explain all examples without ranking, saved choice or group/revocation claims", async () => withForm(async f => {
  f.memory.cursor = 0;
  const cards = ProvisioningLifecycle({ assessmentId: id, version: 2, requirements: lifecycleRequirements });
  assert.equal(nodes(cards).find(node => Object.hasOwn(node.props, "step"))!.props.step, "capabilities");
  const html = renderToStaticMarkup(cards);
  assert.match(html, /JIT cannot replace required SCIM/); assert.match(html, /display order is not a ranking or a saved choice/);
  assert.match(html, /collision policy/); assert.match(html, /Concept references/); assert.equal((html.match(/defaultValue/g) ?? []).length, 0);
}));
test("all group options scope conditions, explain limits and remount group answers without remounting account answers", async () => withForm(async f => {
  const accountKeys = nodes(f.render()).filter(n => n.type === "div" && n.key && lifecycleV2PatternConditions.SCIM_PUSH.includes(n.key as never)).map(n => n.key);
  for (const groupStrategy of Object.keys(lifecycleGroupConditions) as LifecycleGroupStrategy[]) {
    f.chooseGroup(groupStrategy);
    const fields = nodes(f.render()).filter(n => n.type === "select" && n.props.name !== "groupStrategy");
    assert.deepEqual(fields.map(n => n.props.name), lifecycleV2Conditions("SCIM_PUSH", groupStrategy));
    const wrappers = nodes(f.render()).filter(n => n.type === "div" && n.key);
    assert.deepEqual(wrappers.filter(n => accountKeys.includes(n.key)).map(n => n.key), accountKeys);
    for (const id of lifecycleGroupConditions[groupStrategy]) assert.ok(wrappers.some(n => n.key === `${groupStrategy}-${id}`));
    const entries = f.values("SATISFIED"), done = f.submit(entries); f.requests.at(-1)!.reply.resolve(Response.json(f.response(entries))); await done;
    assert.match(f.html(), groupStrategy === "UNKNOWN" ? /More information is needed/ : /proposed design only — unverified/);
    f.chooseGroup("UNKNOWN"); assert.match(f.html(), /No current what-if result/);
  }
  f.chooseGroup("SCIM_GROUPS"); assert.match(f.html(), /User-only SCIM interface is insufficient/);
  f.chooseGroup("APPLICATION_BRIDGE"); assert.match(f.html(), /bridge does not supply required SCIM/);
  assert.match(f.html(), /Account disablement, application sessions and issued tokens have separate paths/);
  f.chooseGroup("invalid"); assert.match(f.html(), /Choose a valid group strategy/);
}));
test("local input validation blocks foreign, duplicate, wrong-version and file answers before transport", async () => withForm(async f => {
  for (const entries of [[...f.values(), ["workspaceId", "private"]], [...f.values(), ["SCIM_USER_OPERATIONS", "SATISFIED"]],
    [["expectedVersion", "3"], ...f.values().slice(1)], [["expectedVersion", "2"], ["patternId", "JIT_LOGIN"]],
    [...f.values(), ["file", new Blob(["private"])]], [...f.values(), ["OFFBOARDING_AND_ACCESS_REVOCATION", "SATISFIED"]],
    [...f.values(), ["GROUP_SOURCE_AND_MEMBERSHIP_MAPPING", "SATISFIED"]], f.values().filter(([key]) => key !== "groupStrategy"),
    f.values().map(([key, value]) => [key, key === "groupStrategy" ? "NONE" : value])] as [string, unknown][][]) {
    await f.submit(entries); assert.equal(f.requests.length, 0); assert.match(f.html(), /Choose valid declarations/); assert.equal(f.html().includes("private"), false);
  }
}));
test("one in-flight request, cancel and late body cannot unlock or replace an explicit retry", async () => withForm(async f => {
  const first = f.submit(f.values("SATISFIED")); await f.submit(); assert.equal(f.requests.length, 1);
  const request = f.requests[0]; assert.equal(request.url, `/api/assessments/${id}/provisioning-lifecycle-v2`);
  assert.equal(request.init.credentials, "same-origin"); assert.equal(request.init.redirect, "error"); assert.equal(request.init.cache, "no-store");
  assert.equal(nodes(f.render()).find(n => n.type === "fieldset")!.props.disabled, true);
  const body = deferred<Uint8Array>(), reading = deferred<void>();
  request.reply.resolve(new Response(new ReadableStream({ async pull(controller) { reading.resolve(); controller.enqueue(await body.promise); controller.close(); } })));
  await reading.promise; f.cancel(); assert.match(f.html(), /Preview canceled/); assert.ok(request.init.signal?.aborted);
  const second = f.submit(); body.resolve(new TextEncoder().encode(JSON.stringify(f.response(f.values("SATISFIED"))))); await first;
  assert.equal(f.form().props["aria-busy"], true); assert.equal(f.html().includes("proposed design only"), false);
  f.requests[1].reply.resolve(Response.json(f.response())); await second; assert.match(f.html(), /More information is needed/);
}));
test("change and unmount discard results and suppress late success or error", async () => withForm(async f => {
  const first = f.submit(); f.chooseGroup("APPLICATION_BRIDGE"); f.requests[0].reply.reject(new Error("private failure")); await first;
  assert.match(f.html(), /No current what-if result/); assert.equal(f.html().includes('role="alert"'), false);
  const second = f.submit(); const updates = f.memory.updates; f.memory.cleanup?.();
  f.requests[1].reply.resolve(Response.json(f.response())); await second; assert.equal(f.memory.updates, updates);
}));
test("bounded body, safe refusals and forged checks cannot leak upstream details or promote design", async () => withForm(async f => {
  for (const response of [new Response("private".repeat(5000)), Response.json({ ...f.response(), assessmentVersion: 3 }),
    Response.json({ ...f.response(), recommendationReady: true }), new Response("private", { status: 409 }), new Response("private", { status: 401 })]) {
    const done = f.submit(); f.requests.at(-1)!.reply.resolve(response); await done;
    assert.match(f.html(), /role="alert"/); assert.equal(f.html().includes("private"), false); assert.equal(f.form().props["aria-busy"], false);
  }
}));
test("ten-second timeout releases inputs and does not accept a late successful result", async () => withForm(async f => {
  const clock = new AbortController(); Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: (ms: number) => { assert.equal(ms, 10_000); return clock.signal; } });
  const done = f.submit(f.values("SATISFIED")); clock.abort(); f.requests[0].reply.resolve(Response.json(f.response(f.values("SATISFIED")))); await done;
  assert.match(f.html(), /preview took too long/); assert.equal(f.form().props["aria-busy"], false); assert.equal(f.html().includes("proposed design only"), false);
}));
