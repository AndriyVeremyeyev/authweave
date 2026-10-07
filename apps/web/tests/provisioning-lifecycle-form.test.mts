import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import type { LifecyclePattern, ProvisioningRequirements } from "../src/lib/assessment/provisioning-lifecycle.ts";
import { lifecycleV2Analysis, lifecycleV2ByteLimit, lifecycleV2Conditions, lifecycleV2PatternConditions, lifecycleGroupConditions, parseLifecycleV2Form, type LifecycleGroupStrategy, type LifecycleV2Input } from "../src/lib/assessment/provisioning-lifecycle-v2.ts";
import { lifecycleAssessmentId as id, lifecycleV2Fixture, lifecycleRequirements } from "./fixtures/provisioning-lifecycle-v2.mts";
import { chunkedPreviewResponse, deferredJsonResponse } from "./fixtures/preview-stream.mts";

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
const { LifecycleConditions, LifecycleFollowUps, ProvisioningLifecycle } = await import(moduleUrl(compiled));
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
function followUps(input: LifecycleV2Input, requirements: ProvisioningRequirements = lifecycleRequirements) {
  const raw = lifecycleV2Fixture(input, requirements);
  const analysis = lifecycleV2Analysis(raw.analysis, input, requirements);
  const view = LifecycleFollowUps({ analysis });
  return { analysis, view, html: renderToStaticMarkup(view) };
}
const satisfiedInput = (patternId: LifecyclePattern, groupStrategy: LifecycleGroupStrategy): LifecycleV2Input => ({
  expectedVersion: 2, patternId, groupStrategy,
  declarations: Object.fromEntries(lifecycleV2Conditions(patternId, groupStrategy).map(id => [id, "SATISFIED"])),
});

test("provisioning follow-ups separate saved conflicts, saved unknowns and temporary condition outcomes without mutation", () => {
  const input = satisfiedInput("JIT_LOGIN", "APPLICATION_BRIDGE");
  input.declarations.APPLICATION_SESSION_INVALIDATION = "NOT_SATISFIED";
  input.declarations.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY = "UNKNOWN";
  const requirements: ProvisioningRequirements = { scim: "REQUIRED", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "FORBIDDEN" };
  const beforeInput = structuredClone(input), beforeRequirements = structuredClone(requirements);
  const { analysis, view, html } = followUps(input, requirements), beforeAnalysis = structuredClone(analysis);
  assert.ok(html.includes("Design conflicts with saved requirements (2)"));
  assert.ok(html.includes("Saved requirements to clarify (1)"));
  assert.ok(html.includes("Declared not met (1)")); assert.ok(html.includes("Unknown temporary conditions (1)"));
  assert.ok(html.includes('href="#lifecycle-JIT_LOGIN-groupStrategy"'));
  assert.ok(html.includes('href="#lifecycle-JIT_LOGIN-APPLICATION_SESSION_INVALIDATION"'));
  assert.ok(html.includes('href="#lifecycle-JIT_LOGIN-TOKEN_REVOCATION_OR_BOUNDED_EXPIRY"'));
  assert.equal(html.includes('href="#lifecycle-JIT_LOGIN-TENANT_AND_SUBJECT_CORRELATION"'), false);
  assert.equal(nodes(view).filter(n => n.props.step === "capabilities").length, 1);
  assert.equal(html.includes("Group plan to revisit"), false);
  for (const text of ["Required SCIM cannot be replaced by JIT", "saved requirements stay unchanged", "not unanswered temporary conditions",
    "do not select an answer", "Nothing is saved", "Full checks below", "remain unverified"]) assert.ok(html.includes(text), text);
  assert.equal(html.includes("<form"), false); assert.equal(html.includes("<select"), false); assert.equal(html.includes("<input"), false);
  renderToStaticMarkup(LifecycleFollowUps({ analysis }));
  assert.deepEqual(analysis, beforeAnalysis); assert.deepEqual(input, beforeInput); assert.deepEqual(requirements, beforeRequirements);
});

test("unknown group design is not an unknown saved requirement or a missing condition", () => {
  for (const criticality of ["REQUIRED", "FORBIDDEN", "PREFERRED", "NOT_REQUIRED"] as const) {
    const { html, view } = followUps(satisfiedInput("SCIM_AND_JIT", "UNKNOWN"),
      { scim: "REQUIRED", justInTimeProvisioning: "REQUIRED", groupSynchronization: criticality });
    assert.ok(html.includes("Group plan to revisit")); assert.ok(html.includes("Group strategy is unknown"));
    assert.equal((html.match(/href=/g) ?? []).length, 1);
    assert.ok(html.includes('href="#lifecycle-SCIM_AND_JIT-groupStrategy"'));
    assert.equal(html.includes("Saved requirements to clarify"), false); assert.equal(html.includes("Unknown temporary conditions"), false);
    assert.equal(nodes(view).some(n => n.props.step === "capabilities"), false); assert.equal(html.includes("No unmet or unknown"), false);
  }
});

test("SCIM Group and JIT incompatibility remains a design gap even when all declarations are met", () => {
  const { html } = followUps(satisfiedInput("JIT_LOGIN", "SCIM_GROUPS"),
    { scim: "NOT_REQUIRED", justInTimeProvisioning: "REQUIRED", groupSynchronization: "REQUIRED" });
  assert.ok(html.includes("Group plan to revisit")); assert.ok(html.includes("JIT-only does not match this option"));
  assert.ok(html.includes('href="#lifecycle-JIT_LOGIN-groupStrategy"'));
  assert.equal(html.includes("Unknown temporary conditions"), false); assert.equal(html.includes("Design conflicts with saved requirements"), false);
  assert.equal(html.includes("No unmet or unknown"), false);
});

test("matched and not-applied checks are not follow-up gaps or a verification claim", () => {
  for (const pattern of Object.keys(lifecycleV2PatternConditions) as LifecyclePattern[]) {
    for (const strategy of ["NONE", "APPLICATION_BRIDGE"] as const) {
      const { html, view } = followUps(satisfiedInput(pattern, strategy),
        { scim: "PREFERRED", justInTimeProvisioning: "NOT_REQUIRED", groupSynchronization: "PREFERRED" });
      assert.ok(html.includes("No unmet or unknown checks in this temporary preview"));
      assert.ok(html.includes("Declared matches and not-applied checks are not verification or a recommendation"));
      assert.equal(html.includes("href="), false); assert.equal(nodes(view).some(n => n.props.step), false);
    }
  }
});

test("all real pattern and group forms bind summary links to exact fields and clear them only on explicit changes", async () => {
  for (const pattern of Object.keys(lifecycleV2PatternConditions) as LifecyclePattern[]) await withForm(async f => {
    for (const strategy of Object.keys(lifecycleGroupConditions) as LifecycleGroupStrategy[]) {
      f.chooseGroup(strategy);
      const entries = f.values("SATISFIED").map(([key, value], index): [string, string] =>
        [key, index === 3 ? "NOT_SATISFIED" : index === 4 ? "UNKNOWN" : value]);
      assert.equal(f.html().includes('aria-label="Provisioning preview follow-ups"'), false);
      const done = f.submit(entries); assert.equal(f.html().includes('aria-label="Provisioning preview follow-ups"'), false);
      f.requests.at(-1)!.reply.resolve(Response.json(f.response(entries))); await done;
      const html = f.html(), summary = html.slice(html.indexOf('aria-label="Provisioning preview follow-ups"'), html.indexOf("</section>") + 10);
      const targets = [...summary.matchAll(/href="#([^\"]+)"/g)].map(match => match[1]);
      assert.ok(targets.length >= 2);
      const fields = nodes(f.render()).filter(n => n.type === "select");
      for (const target of targets) {
        assert.ok(target.startsWith(`lifecycle-${pattern}-`));
        assert.equal(fields.filter(n => n.props.id === target).length, 1);
        assert.ok(fields.find(n => n.props.id === target)!.props.className?.toString().includes("focus-visible"));
      }
      assert.ok(summary.includes("Declared not met (1)")); assert.ok(summary.includes("Unknown temporary conditions (1)"));
      for (const text of ["Group transport design", "Saved requirement checks", "Temporary condition checks", "not a recommendation, approval"]) assert.ok(html.includes(text));
      if (pattern === "JIT_LOGIN") assert.ok(summary.includes("Design conflicts with saved requirements (1)"));
      assert.equal(f.requests.length, Object.keys(lifecycleGroupConditions).indexOf(strategy) + 1);
      assert.equal(fields.find(n => n.props.name === "groupStrategy")!.props.value, strategy);
      f.change(); assert.equal(f.html().includes('aria-label="Provisioning preview follow-ups"'), false);
      assert.ok(f.html().includes("No current what-if result"));
    }
  }, pattern);
});

test("unsafe and stale replies cannot create provisioning follow-ups or copy upstream text", async () => withForm(async f => {
  for (const alter of [(reply: ReturnType<typeof f.response>) => ({ ...reply, assessmentVersion: 3 }),
    (reply: ReturnType<typeof f.response>) => ({ ...reply, analysis: { ...reply.analysis, status: "READY", secret: "private upstream" } })]) {
    const done = f.submit(); f.requests.at(-1)!.reply.resolve(Response.json(alter(f.response()))); await done;
    const html = f.html(); assert.ok(html.includes("could not be read safely"));
    assert.equal(html.includes('aria-label="Provisioning preview follow-ups"'), false);
    assert.equal(html.includes("private upstream"), false);
  }
}));

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
test("one in-flight request, cancel and late body cannot unlock or replace an explicit retry", { timeout: 2000 }, async () => withForm(async f => {
  f.chooseGroup("APPLICATION_BRIDGE");
  const first = f.submit(f.values("SATISFIED")); await f.submit(); assert.equal(f.requests.length, 1);
  const request = f.requests[0]; assert.equal(request.url, `/api/assessments/${id}/provisioning-lifecycle-v2`);
  assert.equal(request.init.credentials, "same-origin"); assert.equal(request.init.redirect, "error"); assert.equal(request.init.cache, "no-store");
  assert.equal(nodes(f.render()).find(n => n.type === "fieldset")!.props.disabled, true);
  const body = deferred<unknown>(), streamed = deferredJsonResponse(body.promise);
  request.reply.resolve(streamed.response);
  await streamed.reading; f.cancel(); assert.match(f.html(), /Preview canceled/); assert.ok(request.init.signal?.aborted);
  assert.equal(streamed.canceled(), true); await first;
  assert.equal(streamed.response.body!.locked, false);
  assert.equal(f.values()[2][1], "APPLICATION_BRIDGE");
  const second = f.submit(); body.resolve(f.response(f.values("SATISFIED"))); await Promise.resolve();
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
test("ten-second timeout releases inputs and does not accept a late successful result", { timeout: 2000 }, async () => {
  for (const stage of ["fetch", "body"] as const) await withForm(async f => {
    f.chooseGroup("SCIM_GROUPS");
    const clocks: AbortController[] = [], body = deferred<unknown>(), streamed = deferredJsonResponse(body.promise);
    Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: (ms: number) => {
      assert.equal(ms, 10_000); const clock = new AbortController(); clocks.push(clock); return clock.signal;
    } });
    const values = f.values("SATISFIED"), done = f.submit(values);
    if (stage === "body") { f.requests[0].reply.resolve(streamed.response); await streamed.reading; }
    clocks[0].abort(new DOMException("synthetic-private-timeout", "TimeoutError"));
    if (stage === "body") { assert.equal(streamed.canceled(), true); await done; body.resolve(f.response(values)); }
    else f.requests[0].reply.resolve(Response.json(f.response(values)));
    await done;
    assert.match(f.html(), /preview took too long/); assert.equal(f.form().props["aria-busy"], false); assert.equal(f.html().includes("proposed design only"), false);
    assert.equal(f.html().includes("synthetic-private"), false); assert.equal(f.values()[2][1], "SCIM_GROUPS");
    const retry = f.submit(values); f.requests[1].reply.resolve(Response.json(f.response(values))); await retry;
    assert.equal(clocks.length, 2); assert.equal(clocks[1].signal.aborted, false);
    assert.match(f.html(), /Matches your proposed design only/); assert.equal(f.html().includes('role="alert"'), false);
  });
});

test("change, group change and unmount interrupt a held body without waiting for late bytes", { timeout: 2000 }, async () => {
  for (const pattern of Object.keys(lifecycleV2PatternConditions) as LifecyclePattern[]) {
    for (const action of ["change", "group", "unmount"] as const) await withForm(async f => {
      f.chooseGroup("SCIM_GROUPS");
      const values = f.values("SATISFIED"), body = deferred<unknown>(), streamed = deferredJsonResponse(body.promise), done = f.submit(values);
      f.requests[0].reply.resolve(streamed.response); await streamed.reading;
      if (action === "unmount") f.memory.cleanup?.();
      else if (action === "group") f.chooseGroup("APPLICATION_BRIDGE");
      else f.change();
      assert.equal(streamed.canceled(), true); const updates = f.memory.updates;
      await done; assert.equal(f.memory.updates, updates); assert.equal(streamed.response.body!.locked, false);
      body.resolve(f.response(values)); await Promise.resolve(); assert.equal(f.memory.updates, updates);
      if (action !== "unmount") {
        assert.match(f.html(), /No current what-if result/); assert.equal(f.form().props["aria-busy"], false);
        assert.equal(f.html().includes('role="alert"'), false);
        assert.equal(f.values()[2][1], action === "group" ? "APPLICATION_BRIDGE" : "SCIM_GROUPS");
      }
    }, pattern);
  }
});

test("all lifecycle patterns and group strategies accept valid streamed replies at the exact byte limit", async () => {
  const headerCases: HeadersInit[] = [{}, { "Content-Length": String(lifecycleV2ByteLimit) }, { "Content-Length": "1" }];
  for (const pattern of Object.keys(lifecycleV2PatternConditions) as LifecyclePattern[]) await withForm(async f => {
    for (const group of Object.keys(lifecycleGroupConditions) as LifecycleGroupStrategy[]) {
      f.chooseGroup(group);
      for (const headers of headerCases) {
        const values = f.values("SATISFIED"), bytes = new TextEncoder().encode(JSON.stringify(f.response(values)).padEnd(lifecycleV2ByteLimit, " "));
        assert.equal(bytes.length, lifecycleV2ByteLimit);
        const streamed = chunkedPreviewResponse([bytes.slice(0, 19), bytes.slice(19, -1), bytes.slice(-1)], headers);
        const done = f.submit(values); f.requests.at(-1)!.reply.resolve(streamed.response); await done;
        assert.match(f.html(), pattern === "JIT_LOGIN" ? /proposed design has a mismatch/ : group === "UNKNOWN" ? /More information is needed/ : /Matches your proposed design only/);
        assert.equal(f.html().includes('role="alert"'), false); assert.equal(f.form().props["aria-busy"], false);
        assert.equal(streamed.cancellations(), 0); assert.equal(streamed.response.body!.locked, false);
      }
    }
  }, pattern);
});

test("oversized or malformed streams keep the group strategy and require only a manual retry", async () => {
  const encoder = new TextEncoder(), limit = lifecycleV2ByteLimit;
  const cases: { bytes?: Uint8Array; headers?: HeadersInit }[] = [
    {}, { bytes: encoder.encode(" ".repeat(limit + 1)), headers: { "Content-Length": "1" } },
    { bytes: encoder.encode("é".repeat(limit / 2 + 1)) },
    { bytes: encoder.encode("{}"), headers: { "Content-Length": String(limit + 1) } },
    { bytes: encoder.encode("{}"), headers: { "Content-Length": "invalid" } }, { bytes: new Uint8Array([0xff]) },
  ];
  for (const { bytes, headers } of cases) await withForm(async f => {
    f.chooseGroup("APPLICATION_BRIDGE"); const values = f.values("SATISFIED");
    const streamed = chunkedPreviewResponse([bytes ?? encoder.encode(JSON.stringify(f.response(values)).padEnd(limit + 1, " ")), encoder.encode("synthetic-private-unread-tail")], headers);
    const done = f.submit(values); f.requests[0].reply.resolve(streamed.response); await done;
    assert.match(f.html(), /The provisioning preview could not be read safely. Try again/);
    for (const text of ["synthetic-private", "Body too large", "TypeError", "Matches your proposed design only"]) assert.equal(f.html().includes(text), false);
    assert.equal(streamed.cancellations(), 1); assert.ok(streamed.pulls() <= 1); assert.equal(streamed.response.body!.locked, false);
    assert.equal(f.values()[2][1], "APPLICATION_BRIDGE"); assert.equal(f.form().props["aria-busy"], false); assert.equal(f.requests.length, 1);
    const retry = f.submit(values); f.requests[1].reply.resolve(Response.json(f.response(values))); await retry;
    assert.match(f.html(), /Matches your proposed design only/); assert.equal(f.html().includes('role="alert"'), false);
  });
});
