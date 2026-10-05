import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const slot = `__authweave_weighted_form_${crypto.randomUUID()}`;
const globals = globalThis as unknown as Record<string, unknown>;
function moduleUrl(source: string) { return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`; }
const hooks = moduleUrl(`export function useState(initial) { const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++; if(!(i in s.slots))s.slots[i]=initial;return [s.slots[i],value=>{s.updates++;s.slots[i]=value;}]; }
  export function useRef(initial) { const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++;return s.slots[i]??={current:initial}; }
  export function useEffect(run) { const s=globalThis[${JSON.stringify(slot)}];s.cleanup??=run(); }`);
const source = await readFile(new URL("../src/app/assessments/[id]/weighted-preview.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
  target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
  .replaceAll('"react"', JSON.stringify(hooks)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")));
const { WeightedPreviewForm } = await import(moduleUrl(compiled));
const id = "80000000-0000-4000-8000-000000000001";
const preferred = [{ capability: "OIDC", label: "OIDC" }, { capability: "JIT", label: "JIT" }];
// Display-only responses and deterministic hooks/DOM. These run real handlers, not Core or browser E2E.
const weighted = (catalogVersion = "fictional-baseline") => ({ assessmentVersion: 7, catalogVersion,
  scoringPolicyVersion: "explicit-capability-weights-1", candidates: [
    { optionId: "fictional-a", displayName: "Fictional A", plan: "Demo", region: "Test", status: "SCORED", score: 60,
      contributions: [{ capability: "OIDC", weight: 60, outcome: "AVAILABLE", earnedPoints: 60 },
        { capability: "JIT", weight: 40, outcome: "UNAVAILABLE", earnedPoints: 0 }] },
    { optionId: "fictional-b", displayName: "Fictional B", plan: "Demo", region: "Test", status: "EXCLUDED", score: null, contributions: [] },
  ] });
const sensitivity = () => ({ assessmentVersion: 7, catalogVersion: "fictional-alternative",
  sensitivityPolicyVersion: "explicit-capability-sensitivity-1", candidates: [
    { optionId: "fictional-a", displayName: "Fictional A", plan: "Demo", region: "Test", status: "SCORED",
      baselineScore: 60, alternativeScore: 20, scoreDelta: -40, capabilityDeltas: [
        { capability: "OIDC", baselineWeight: 60, alternativeWeight: 20, outcome: "AVAILABLE", pointChange: -40 },
        { capability: "JIT", baselineWeight: 40, alternativeWeight: 80, outcome: "UNAVAILABLE", pointChange: 0 }] },
  ] });
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
async function withForm(run: (form: ReturnType<typeof harness>) => Promise<void>) {
  const originals = new Map(["fetch", "FormData"].map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const form = harness();
  try { await run(form); } finally {
    form.memory.cleanup?.(); Reflect.deleteProperty(globals, slot);
    for (const [name, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor); else Reflect.deleteProperty(globalThis, name);
    }
  }
}
function harness() {
  const memory = { cursor: 0, slots: [] as unknown[], updates: 0, cleanup: undefined as (() => void) | undefined };
  globals[slot] = memory;
  const requests: { url: string; init: RequestInit; reply: ReturnType<typeof deferred<Response>> }[] = [];
  Object.defineProperty(globalThis, "FormData", { configurable: true, value: class {
    private values: Record<string, string>;
    constructor(values: Record<string, string>) { this.values = values; }
    get(name: string) { return this.values[name] ?? null; }
  } });
  Object.defineProperty(globalThis, "fetch", { configurable: true, value: (url: string, init: RequestInit) => {
    const reply = deferred<Response>(); requests.push({ url, init, reply }); return reply.promise;
  } });
  const render = () => { memory.cursor = 0; return WeightedPreviewForm({ assessmentId: id, version: 7, preferred }); };
  const forms = () => nodes(render()).filter(node => node.type === "form");
  const submit = (index: number, values: Record<string, string>) =>
    (forms()[index].props.onSubmit as (event: unknown) => Promise<void>)({ preventDefault() {}, currentTarget: values });
  const change = (index: number) => (forms()[index].props.onChange as () => void)();
  const html = () => renderToStaticMarkup(render());
  const cancel = () => (nodes(render()).find(node => node.type === "button" && node.props.type === "button")!.props.onClick as () => void)();
  const startBaseline = async () => {
    const done = submit(0, { OIDC: "60", JIT: "40" });
    requests.at(-1)!.reply.resolve(Response.json(weighted())); await done;
  };
  return { memory, requests, render, forms, submit, change, html, cancel, startBaseline };
}

test("weight inputs remain explicit; invalid sums do not send requests or create a baseline", async () => withForm(async f => {
  assert.equal(f.forms().length, 1);
  for (const node of nodes(f.render()).filter(node => node.type === "input" && node.props.type === "number")) {
    assert.equal(node.props.value, undefined); assert.equal(node.props.defaultValue, undefined);
  }
  await f.submit(0, { OIDC: "60" }); assert.match(f.html(), /every preferred capability/);
  await f.submit(0, { OIDC: "60", JIT: "39" }); assert.match(f.html(), /current total is 99/);
  assert.equal(f.requests.length, 0); assert.equal(f.forms().length, 1);
  await f.startBaseline(); assert.equal(f.forms().length, 2);
  await f.submit(1, { alternative_OIDC: "20", alternative_JIT: "79" });
  assert.match(f.html(), /Alternative weights must total exactly 100/); assert.equal(f.requests.length, 1);
  assert.match(f.html(), /No score: Excluded/); assert.match(f.html(), /not rank providers/);
}));

test("baseline and alternative share a synchronous request guard and use exactly the confirmed baseline", async () => withForm(async f => {
  const first = f.submit(0, { OIDC: "60", JIT: "40" });
  await f.submit(0, { OIDC: "20", JIT: "80" }); assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].url, `/api/assessments/${id}/weighted-preview`);
  assert.equal(String(f.requests[0].init.body), "expectedVersion=7&OIDC=60&JIT=40");
  f.requests[0].reply.resolve(Response.json(weighted())); await first;
  const compare = f.submit(1, { alternative_OIDC: "20", alternative_JIT: "80" });
  await f.submit(1, { alternative_OIDC: "30", alternative_JIT: "70" });
  await f.submit(0, { OIDC: "50", JIT: "50" }); assert.equal(f.requests.length, 2);
  assert.equal(f.requests[1].url, `/api/assessments/${id}/weight-sensitivity`);
  assert.equal(String(f.requests[1].init.body), "expectedVersion=7&baseline_OIDC=60&alternative_OIDC=20&baseline_JIT=40&alternative_JIT=80");
  for (const node of nodes(f.render()).filter(node => node.type === "button" && node.props.type === "submit" ||
    node.type === "input" && node.props.type === "number")) assert.equal(node.props.disabled, true);
  for (const { init } of f.requests) {
    assert.equal(init.credentials, "same-origin"); assert.equal(init.cache, "no-store"); assert.equal(init.redirect, "error");
    assert.equal(init.method, "POST"); assert.ok(init.signal instanceof AbortSignal);
  }
  f.requests[1].reply.resolve(Response.json(sensitivity())); await compare;
  assert.match(f.html(), /fictional-alternative/); assert.match(f.html(), /not a ranking or recommendation/);
}));

test("cancel releases inputs for an explicit retry; a late old response cannot clear the new request", async () => withForm(async f => {
  const first = f.submit(0, { OIDC: "60", JIT: "40" }); f.cancel();
  assert.equal(f.requests[0].init.signal!.aborted, true); assert.match(f.html(), /Calculation canceled/);
  const second = f.submit(0, { OIDC: "20", JIT: "80" });
  f.requests[0].reply.resolve(Response.json(weighted("late-old"))); await first;
  assert.equal(f.html().includes("late-old"), false); assert.match(f.html(), /Calculating…/);
  await f.submit(0, { OIDC: "50", JIT: "50" }); assert.equal(f.requests.length, 2);
  f.requests[1].reply.resolve(Response.json(weighted("explicit-retry"))); await second;
  assert.match(f.html(), /explicit-retry/); assert.equal(f.html().includes("Calculation canceled"), false);
}));

test("editing the baseline cancels an alternative even during body reading and invalidates stale callbacks", async () => withForm(async f => {
  await f.startBaseline();
  const oldCompare = f.forms()[1].props.onSubmit as (event: unknown) => Promise<void>;
  const body = deferred<unknown>(), started = deferred<void>();
  const compare = f.submit(1, { alternative_OIDC: "20", alternative_JIT: "80" });
  f.requests[1].reply.resolve({ ok: true, json() { started.resolve(); return body.promise; } } as Response);
  await started.promise; f.change(0);
  assert.equal(f.requests[1].init.signal!.aborted, true); assert.equal(f.forms().length, 1);
  await oldCompare({ preventDefault() {}, currentTarget: { alternative_OIDC: "20", alternative_JIT: "80" } });
  assert.equal(f.requests.length, 2);
  const next = f.submit(0, { OIDC: "50", JIT: "50" });
  body.resolve(sensitivity()); await compare;
  assert.equal(f.html().includes("fictional-alternative"), false); assert.match(f.html(), /Calculating…/);
  f.requests[2].reply.resolve(Response.json(weighted("new-baseline"))); await next;
  const nextCompare = f.submit(1, { alternative_OIDC: "40", alternative_JIT: "60" });
  assert.match(String(f.requests[3].init.body), /baseline_OIDC=50.*baseline_JIT=50/);
  f.cancel(); f.requests[3].reply.reject(new Error("private late failure")); await nextCompare;
  assert.match(f.html(), /new-baseline/); assert.equal(f.html().includes("private late failure"), false);
}));

test("editing alternative weights clears its result but keeps the confirmed baseline", async () => withForm(async f => {
  await f.startBaseline(); const compare = f.submit(1, { alternative_OIDC: "20", alternative_JIT: "80" });
  f.requests[1].reply.resolve(Response.json(sensitivity())); await compare;
  f.change(1); assert.equal(f.forms().length, 2); assert.match(f.html(), /fictional-baseline/);
  assert.equal(f.html().includes("fictional-alternative"), false);
  const pending = f.submit(1, { alternative_OIDC: "30", alternative_JIT: "70" });
  f.change(1); assert.equal(f.requests[2].init.signal!.aborted, true);
  f.requests[2].reply.resolve(Response.json(sensitivity())); await pending;
  assert.match(f.html(), /fictional-baseline/); assert.equal(f.html().includes("fictional-alternative"), false);
}));

test("leaving Comparison aborts either request and suppresses late success or error updates", async () => {
  for (const alternative of [false, true]) for (const failure of [false, true]) await withForm(async f => {
    if (alternative) await f.startBaseline();
    const done = f.submit(alternative ? 1 : 0, alternative ?
      { alternative_OIDC: "20", alternative_JIT: "80" } : { OIDC: "60", JIT: "40" });
    f.memory.cleanup!(); const updates = f.memory.updates;
    const request = f.requests.at(-1)!; assert.equal(request.init.signal!.aborted, true);
    if (failure) request.reply.reject(new Error("private upstream failure"));
    else request.reply.resolve(Response.json(alternative ? sensitivity() : weighted()));
    await done; assert.equal(f.memory.updates, updates);
  });
});

test("bounded refusal, malformed and timeout feedback allow only manual retries", async () => {
  const timeout = Object.getOwnPropertyDescriptor(AbortSignal, "timeout")!;
  try {
    for (const alternative of [false, true]) for (const outcome of [400, 401, 403, 404, 409, 503, "wrong-version", "json-error", "timeout"] as const) {
      await withForm(async f => {
        if (alternative) await f.startBaseline();
        const clock = new AbortController();
        Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: (ms: number) => { assert.equal(ms, 10_000); return clock.signal; } });
        const done = f.submit(alternative ? 1 : 0, alternative ?
          { alternative_OIDC: "20", alternative_JIT: "80" } : { OIDC: "60", JIT: "40" });
        const request = f.requests.at(-1)!;
        if (typeof outcome === "number") request.reply.resolve(new Response("private upstream details", { status: outcome }));
        else if (outcome === "wrong-version") request.reply.resolve(Response.json({ ...(alternative ? sensitivity() : weighted()), assessmentVersion: 8 }));
        else if (outcome === "json-error") request.reply.resolve(new Response("private invalid JSON"));
        else { clock.abort(new DOMException("private timeout", "TimeoutError")); request.reply.reject(clock.signal.reason); }
        await done;
        const html = f.html(); assert.match(html, /role="alert"/); assert.equal(html.includes("private"), false);
        assert.equal(html.includes("fictional-alternative"), false);
        assert.equal(f.requests.length, alternative ? 2 : 1);
        for (const node of nodes(f.render()).filter(node => node.type === "button" && node.props.type === "submit")) assert.equal(node.props.disabled, false);
        const retry = f.submit(alternative ? 1 : 0, alternative ?
          { alternative_OIDC: "20", alternative_JIT: "80" } : { OIDC: "60", JIT: "40" });
        assert.equal(f.requests.length, alternative ? 3 : 2);
        f.requests.at(-1)!.reply.resolve(Response.json(alternative ? sensitivity() : weighted())); await retry;
        Object.defineProperty(AbortSignal, "timeout", timeout);
      });
    }
  } finally { Object.defineProperty(AbortSignal, "timeout", timeout); }
});

test("the ten-second deadline also rejects a late body from a transport that ignores cancellation", async () => {
  const timeout = Object.getOwnPropertyDescriptor(AbortSignal, "timeout")!;
  try {
    for (const alternative of [false, true]) await withForm(async f => {
      if (alternative) await f.startBaseline();
      const clock = new AbortController(), body = deferred<unknown>(), started = deferred<void>();
      Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: (ms: number) => { assert.equal(ms, 10_000); return clock.signal; } });
      const done = f.submit(alternative ? 1 : 0, alternative ?
        { alternative_OIDC: "20", alternative_JIT: "80" } : { OIDC: "60", JIT: "40" });
      f.requests.at(-1)!.reply.resolve({ ok: true, json() { started.resolve(); return body.promise; } } as Response);
      await started.promise; clock.abort(new DOMException("private deadline", "TimeoutError"));
      body.resolve(alternative ? sensitivity() : weighted("late-timeout")); await done;
      assert.match(f.html(), /temporarily unavailable/); assert.equal(f.html().includes("private deadline"), false);
      assert.equal(f.html().includes("late-timeout"), false); assert.equal(f.html().includes("fictional-alternative"), false);
      Object.defineProperty(AbortSignal, "timeout", timeout);
    });
  } finally { Object.defineProperty(AbortSignal, "timeout", timeout); }
});
