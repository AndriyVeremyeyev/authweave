import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";
import { usageSaveFeedback, usageReloadPath } from "../src/lib/assessment/usage-save.ts";
import { usageMetrics } from "../src/lib/assessment/usage-planning.ts";

function moduleUrl(source: string) { return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`; }
type ElementView = { type: unknown; props: Record<string, unknown> };
function elements(value: unknown): ElementView[] {
  if (Array.isArray(value)) return value.flatMap(elements);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const node = value as ElementView;
  return [node, ...elements(node.props.children)];
}

test("real form handlers retain conflict edits, block duplicate writes, and release the guard only for a receipt or explicit reload", async () => {
  const slot = `__authweave_usage_save_${crypto.randomUUID()}`;
  const globals = globalThis as unknown as Record<string, unknown>;
  const originalFormData = Object.getOwnPropertyDescriptor(globalThis, "FormData");
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  const action = "/api/assessments/4640bbac-c20f-476a-a4dc-23efad5ff14f/usage-planning";
  const params = new URLSearchParams({ expectedVersion: "0", scopeDescription: "Fictional unsaved input" });
  for (let i=0; i<10; i++) params.append("assumption", "");
  for (const metric of usageMetrics) { params.set(`basis_${metric.key}`, "UNKNOWN"); params.set(`value_${metric.key}`, ""); }
  const before = params.toString();
  const react = moduleUrl(`export function useState(initial) { const s=globalThis[${JSON.stringify(slot)}], i=s.cursor++; if(!(i in s.states))s.states[i]=initial; return [s.states[i], value=>{s.states[i]=value;}]; }
    export function useRef(initial) { const s=globalThis[${JSON.stringify(slot)}], i=s.refCursor++; return s.refs[i] ??= {current:initial}; }
    export function useEffect() {}`);
  const workflow = moduleUrl(`export function useAssessmentSave() { return globalThis[${JSON.stringify(slot)}].lifecycle; }`);
  const saving = moduleUrl(`export function postUsagePlanning(...args) { return globalThis[${JSON.stringify(slot)}].save(...args); }
    export const usageSaveFeedback=${JSON.stringify(usageSaveFeedback)};
    export function usageReloadPath(action) { return action === ${JSON.stringify(action)} ? ${JSON.stringify(usageReloadPath(action))} : null; }`);
  const source = await readFile(new URL("../src/app/assessments/[id]/usage-planning-form.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replaceAll('"react"', JSON.stringify(react)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflow)).replaceAll('"@/lib/assessment/usage-save"', JSON.stringify(saving))
    .replaceAll('"@/lib/assessment/usage-form-validation"', JSON.stringify(new URL("../src/lib/assessment/usage-form-validation.ts", import.meta.url).href));
  const { UsagePlanningForm } = await import(moduleUrl(compiled));
  Object.defineProperty(globalThis, "FormData", { configurable: true, value: class {
    [Symbol.iterator]() { return params.entries(); }
  } });
  try {
    for (const outcome of ["conflict", "uncertain", "saved", "detached"] as const) {
      let resolve: (value: string)=>void = ()=>{throw new Error("No deferred request");};
      const reply = new Promise<string>(done=>{resolve=done;});
      let calls=0, releases=0, opens=0, focusReturns=0;
      const busy: boolean[] = [], destinations: string[] = [];
      const memory = { cursor: 0, refCursor: 0, states: [] as unknown[], refs: [] as unknown[],
        lifecycle: { setSaving: (value: boolean)=>busy.push(value), allowReload: ()=>{releases++;} },
        save: (path: string, body: URLSearchParams)=>{calls++; assert.equal(path, action); assert.equal(body.toString(), before); return reply;} };
      globals[slot] = memory;
      Object.defineProperty(globalThis, "window", { configurable: true, value: {
        location: { assign: (path: string)=>destinations.push(path) },
      } });
      const render = () => {memory.cursor=0; memory.refCursor=0; return UsagePlanningForm({action,children:"Server-rendered fields"});};
      let form = render();
      const event = () => ({ currentTarget: { isConnected: true }, prevented: false,
        preventDefault() { this.prevented=true; } });
      const first = event(); const handler = form.props.onSubmit; const request = handler(first);
      assert.equal(first.prevented, true); assert.equal(calls, 1); assert.deepEqual(busy, [true]);
      const duplicate = event(); await handler(duplicate); assert.equal(duplicate.prevented, true); assert.equal(calls, 1);
      const pending = renderToStaticMarkup(render());
      assert.ok(pending.includes('aria-busy="true"')); assert.ok(pending.includes('<fieldset disabled=""'));
      if (outcome === "detached") first.currentTarget.isConnected=false;
      resolve(outcome === "detached" ? "saved" : outcome); await request;
      assert.deepEqual(busy, [true,false]); assert.equal(params.toString(), before);
      if (outcome === "saved") {
        assert.equal(releases, 1); assert.deepEqual(destinations, [usageReloadPath(action)]);
        const extra = event(); await handler(extra); assert.equal(extra.prevented, true); assert.equal(calls, 1);
      } else if (outcome === "detached") {
        assert.equal(releases, 0); assert.deepEqual(destinations, []);
      } else {
        assert.equal(releases, 0); assert.deepEqual(destinations, []);
        // Even the pre-render callback is blocked after the reply, before React commits new state.
        const extra=event(); await handler(extra); assert.equal(extra.prevented,true); assert.equal(calls,1);
        form=render(); const html=renderToStaticMarkup(form);
        assert.ok(html.includes(usageSaveFeedback[outcome].title));
        const nodes=elements(form);
        const reload=nodes.find(node=>node.type === "button" && node.props.children === "Load current saved version")!;
        const modal=nodes.find(node=>node.type === "dialog")!;
        const dialog={open:false,showModal(){this.open=true; opens++;},close(){this.open=false;}};
        (modal.props.ref as {current:unknown}).current=dialog;
        const clickReload=()=> (reload.props.onClick as (event:unknown)=>void)({currentTarget:{focus:()=>{focusReturns++;}}});
        const keep=nodes.find(node=>node.type === "button" && node.props.children === "Keep my edits")!;
        const discard=nodes.find(node=>node.type === "button" && node.props.children === "Discard and load current version")!;
        (discard.props.onClick as ()=>void)(); assert.equal(releases,0); assert.deepEqual(destinations,[]);
        clickReload(); assert.equal(opens,1); assert.equal(dialog.open,true); assert.equal(releases,0);
        (keep.props.onClick as ()=>void)(); assert.equal(dialog.open,false); assert.equal(focusReturns,1); assert.deepEqual(destinations,[]);
        clickReload(); let prevented=false;
        (modal.props.onCancel as (event:unknown)=>void)({preventDefault:()=>{prevented=true;}});
        assert.equal(prevented,true); assert.equal(dialog.open,false); assert.equal(focusReturns,2);
        assert.equal(releases,0); assert.equal(params.toString(),before);
        clickReload(); (discard.props.onClick as ()=>void)();
        assert.equal(opens,3); assert.equal(dialog.open,false); assert.equal(releases,1); assert.deepEqual(destinations,[usageReloadPath(action)]);
        (discard.props.onClick as ()=>void)(); assert.equal(destinations.length,1);
      }
    }
  } finally {
    Reflect.deleteProperty(globals, slot);
    if (originalFormData) Object.defineProperty(globalThis, "FormData", originalFormData);
    if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow); else Reflect.deleteProperty(globalThis,"window");
  }
});
