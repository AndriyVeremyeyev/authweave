import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";
import { assessmentSteps, type AssessmentStep, type WorkflowState } from "../src/lib/assessment/workflow.ts";

type NodeView = { type: unknown; props: Record<string, unknown> };
type ClickHandler = (event?: { currentTarget: unknown }) => void;
type SaveLifecycle = { setSaving: (busy: boolean) => void; isSaving: () => boolean; allowReload: () => void };
function clickHandler(node: NodeView) { return node.props.onClick as ClickHandler; }
function nodes(value: unknown): NodeView[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const node = value as NodeView;
  return [node, ...nodes(node.props.children)];
}
function moduleUrl(source: string) { return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`; }

// Invoke the real component's handlers with deterministic hook lifecycles. This is
// not a browser DOM, OIDC session, Core evaluator or persistent-write test.
test("workflow exit and navigation handlers preserve edits, restore focus and block pending-save races", async () => {
  const slot = `__authweave_workflow_${crypto.randomUUID()}`;
  const globals = globalThis as unknown as Record<string, unknown>;
  const originals = new Map(["window", "requestAnimationFrame"].map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const react = moduleUrl(`export { createContext } from ${JSON.stringify(import.meta.resolve("react"))};
    export function useContext() { return globalThis[${JSON.stringify(slot)}].context; }
    export function useState(initial) { const s=globalThis[${JSON.stringify(slot)}], i=s.cursor++; if(!(i in s.states))s.states[i]=initial; return [s.states[i], value=>{s.states[i]=value;}]; }
    export function useReducer(reducer, initial) { const s=globalThis[${JSON.stringify(slot)}], i=s.cursor++; if(!(i in s.states))s.states[i]=initial; return [s.states[i], event=>{s.states[i]=reducer(s.states[i],event);}]; }
    export function useRef(initial) { const s=globalThis[${JSON.stringify(slot)}], i=s.refCursor++; return s.refs[i] ??= {current:initial}; }
    export function useEffect(run, deps) { const s=globalThis[${JSON.stringify(slot)}], i=s.effectCursor++, previous=s.effects[i];
      if(previous && deps.every((value,index)=>Object.is(value,previous.deps[index])))return;
      s.effects[i]={deps,cleanup:previous?.cleanup}; s.queue.push(()=>{previous?.cleanup?.();s.effects[i].cleanup=run();}); }`);
  const router = moduleUrl(`export function useRouter() { return globalThis[${JSON.stringify(slot)}].router; }`);
  const source = await readFile(new URL("../src/app/assessments/[id]/assessment-workflow.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replaceAll('"react"', JSON.stringify(react)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"next/navigation"', JSON.stringify(router))
    .replaceAll('"@/lib/assessment/workflow"', JSON.stringify(new URL("../src/lib/assessment/workflow.ts", import.meta.url).href));
  const { AssessmentWorkflow, AssessmentStepButton } = await import(moduleUrl(compiled));
  const panels = Object.fromEntries(assessmentSteps.map(step => [step.id, `Saved ${step.id} controls`]));

  function mount(initialStep: AssessmentStep, editable = true) {
    const destinations: string[] = [], urls: string[] = [];
    const listeners = new Map<string, (event: unknown) => void>();
    let headingFocus = 0, sourceFocus = 0, opens = 0;
    const dialog = { open: false, showModal() { this.open = true; opens++; }, close() { this.open = false; } };
    const sourceButton = { focus() { sourceFocus++; } };
    const memory = { cursor: 0, refCursor: 0, effectCursor: 0, states: [] as unknown[], refs: [] as unknown[],
      effects: [] as { cleanup?: () => void }[], queue: [] as (() => void)[], context: null as unknown,
      router: { push(path: string) { destinations.push(path); } } };
    globals[slot] = memory;
    const location = { href: "http://localhost:3000/assessments/00000000-0000-0000-0000-000000000001?step=context&contextError=invalid&editError=stale&auditError=locked&usageError=invalid&operationsError=stale" };
    Object.defineProperty(globalThis, "window", { configurable: true, value: {
      location, history: { replaceState(_state: unknown, _title: string, url: URL) { location.href = url.href; urls.push(url.href); } },
      addEventListener(name: string, callback: (event: unknown) => void) { listeners.set(name, callback); },
      removeEventListener(name: string, callback: (event: unknown) => void) { assert.equal(listeners.get(name), callback); listeners.delete(name); },
      confirm() { throw new Error("Workspace navigation must use the in-page dialog"); },
    } });
    Object.defineProperty(globalThis, "requestAnimationFrame", { configurable: true, value: (callback: () => void) => callback() });
    let tree: NodeView[] = [];
    function render() {
      memory.cursor = 0; memory.refCursor = 0; memory.effectCursor = 0;
      tree = nodes(AssessmentWorkflow({ initialStep, panels, editable }));
      (tree.find(node => node.type === "dialog")!.props.ref as { current: unknown }).current = dialog;
      (tree.find(node => node.props.id === "assessment-step-heading")!.props.ref as { current: unknown }).current = { focus() { headingFocus++; } };
      for (const effect of memory.queue.splice(0)) effect();
    }
    function button(text: string) { return tree.find(node => node.type === "button" && node.props.children === text)!; }
    function click(text: string) { clickHandler(button(text))({ currentTarget: sourceButton }); }
    function edit() { (tree.find(node => node.props.onChangeCapture)!.props.onChangeCapture as () => void)(); }
    function lifecycle() { return tree.find(node => node.props.value && typeof node.props.value === "object" && Object.hasOwn(node.props.value, "setSaving"))!.props.value as SaveLifecycle; }
    function unloadBlocked() {
      let prevented = false;
      const event = { returnValue: undefined as string | undefined, preventDefault() { prevented = true; } };
      listeners.get("beforeunload")!(event);
      assert.equal(event.returnValue, prevented ? "" : undefined);
      return prevented;
    }
    render();
    return { render, click, edit, lifecycle, unloadBlocked, button, dialog, destinations, urls, memory,
      tree: () => tree, sourceButton, focus: () => ({ headingFocus, sourceFocus, opens }),
      unmount() { for (const effect of memory.effects) effect.cleanup?.(); assert.equal(listeners.size, 0); } };
  }

  try {
    for (const { id: step } of assessmentSteps.filter(step => step.input)) {
      const fixture = mount(step);
      assert.equal(fixture.unloadBlocked(), false);
      fixture.edit(); fixture.render(); assert.equal(fixture.unloadBlocked(), true);
      fixture.click("← Your assessments"); fixture.render();
      assert.equal(fixture.dialog.open, true); assert.deepEqual(fixture.destinations, []);
      assert.ok(fixture.tree().some(node => node.props.id === "discard-edits-description" && typeof node.props.children === "string" && node.props.children.includes("without saving")));
      const discard = clickHandler(fixture.button("Discard and leave"));
      fixture.click("Stay and review");
      discard(); // A queued callback from the now-closed dialog cannot discard edits.
      fixture.render();
      assert.equal(fixture.dialog.open, false); assert.equal(fixture.unloadBlocked(), true);
      assert.equal(fixture.focus().sourceFocus, 1); assert.deepEqual(fixture.destinations, []);
      fixture.click("← Your assessments"); fixture.render();
      let cancelled = false;
      (fixture.tree().find(node => node.type === "dialog")!.props.onCancel as (event: { preventDefault: () => void }) => void)({ preventDefault() { cancelled = true; } });
      fixture.render(); assert.equal(cancelled, true); assert.equal(fixture.unloadBlocked(), true);
      assert.equal(fixture.focus().sourceFocus, 2);

      // Both button disabling and synchronous refs protect before React rerenders.
      const exitHandler = clickHandler(fixture.button("← Your assessments"));
      const nextHandler = clickHandler(fixture.button(step === "usage" ? "Next: Review →" : step === "context" ? "Next: Requirements →" : step === "capabilities" ? "Next: Audit →" : "Next: Usage →"));
      fixture.lifecycle().setSaving(true);
      assert.equal(fixture.lifecycle().isSaving(), true);
      exitHandler({ currentTarget: fixture.sourceButton }); nextHandler({ currentTarget: fixture.sourceButton });
      fixture.render();
      assert.equal(fixture.dialog.open, false); assert.deepEqual(fixture.destinations, []);
      assert.equal(fixture.button("← Your assessments").props.disabled, true);
      assert.equal(fixture.unloadBlocked(), true);
      const navigation = fixture.tree().find(node => typeof node.props.value === "function")!.props.value;
      fixture.memory.context = navigation;
      AssessmentStepButton({ step: "review", children: "Review shortcut" }).props.onClick({ currentTarget: fixture.sourceButton });
      fixture.render(); assert.equal(fixture.dialog.open, false);
      fixture.lifecycle().setSaving(false); fixture.render();
      assert.equal(fixture.lifecycle().isSaving(), false);
      assert.equal(fixture.unloadBlocked(), true); // A refused/uncertain save does not release edits.

      fixture.click("← Your assessments"); fixture.render();
      const leave = clickHandler(fixture.button("Discard and leave"));
      leave(); leave(); fixture.render();
      assert.deepEqual(fixture.destinations, ["/assessments"]);
      assert.equal(fixture.unloadBlocked(), false); assert.equal(fixture.dialog.open, false);
      assert.deepEqual(fixture.urls, []); // No list destination becomes a step URL.
      assert.equal((fixture.memory.states[0] as WorkflowState).step, step);
      fixture.unmount();
    }

    const fixture = mount("context");
    fixture.edit(); fixture.render(); fixture.click("Next: Requirements →"); fixture.render();
    fixture.click("Stay and review"); fixture.render(); assert.equal((fixture.memory.states[0] as WorkflowState).step, "context");
    assert.equal(fixture.unloadBlocked(), true);
    fixture.click("Next: Requirements →"); fixture.render(); fixture.click("Discard and continue"); fixture.render();
    assert.equal((fixture.memory.states[0] as WorkflowState).step, "capabilities"); assert.equal(fixture.unloadBlocked(), false);
    assert.equal(fixture.focus().headingFocus, 1); assert.deepEqual(fixture.destinations, []);
    const url = new URL(fixture.urls[0]); assert.equal(url.searchParams.get("step"), "capabilities");
    for (const key of ["contextError", "editError", "auditError", "usageError", "operationsError"]) assert.equal(url.searchParams.has(key), false);
    fixture.edit(); fixture.render(); fixture.lifecycle().allowReload(); fixture.render();
    assert.equal(fixture.unloadBlocked(), false); fixture.click("← Your assessments");
    assert.deepEqual(fixture.destinations, ["/assessments"]); fixture.unmount();

    for (const step of ["review", "comparison", "architecture"] as const) {
      const preview = mount(step); preview.edit(); preview.render();
      assert.equal(preview.unloadBlocked(), false); preview.click("← Your assessments");
      assert.deepEqual(preview.destinations, ["/assessments"]); assert.equal(preview.dialog.open, false); preview.unmount();
    }
    const readOnly = mount("context", false); readOnly.edit(); readOnly.render();
    assert.equal(readOnly.unloadBlocked(), false); readOnly.click("← Your assessments");
    assert.deepEqual(readOnly.destinations, ["/assessments"]); readOnly.unmount();
  } finally {
    Reflect.deleteProperty(globals, slot);
    for (const [name, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor); else Reflect.deleteProperty(globalThis, name);
    }
  }
});
