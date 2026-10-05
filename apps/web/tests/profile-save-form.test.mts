import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";
import { profileSaveFeedback, profileReloadPath, profileSaveSections, type ProfileSection } from "../src/lib/assessment/profile-save.ts";
import { profileFormFixture, profileSectionAction } from "./fixtures/profile-save.mts";

function moduleUrl(source: string) { return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`; }
type ElementView = { type: unknown; props: Record<string, unknown> };
function elements(value: unknown): ElementView[] {
  if (Array.isArray(value)) return value.flatMap(elements);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const node = value as ElementView;
  return [node, ...elements(node.props.children)];
}

test("all four real section form handlers retain conflict edits, block duplicate writes, and release the guard only for a receipt or explicit reload", async () => {
  const slot = `__authweave_usage_save_${crypto.randomUUID()}`;
  const globals = globalThis as unknown as Record<string, unknown>;
  const originalFormData = Object.getOwnPropertyDescriptor(globalThis, "FormData");
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  let params = new URLSearchParams();
  const react = moduleUrl(`export function useState(initial) { const s=globalThis[${JSON.stringify(slot)}], i=s.cursor++; if(!(i in s.states))s.states[i]=initial; return [s.states[i], value=>{s.states[i]=value;}]; }
    export function useRef(initial) { const s=globalThis[${JSON.stringify(slot)}], i=s.refCursor++; return s.refs[i] ??= {current:initial}; }
    export function useEffect() {}`);
  const workflow = moduleUrl(`export function useAssessmentSave() { return globalThis[${JSON.stringify(slot)}].lifecycle; }`);
  const saving = moduleUrl(`export function postProfileSection(...args) { return globalThis[${JSON.stringify(slot)}].save(...args); }
    export { profileFormIssues, profileReloadPath } from ${JSON.stringify(new URL("../src/lib/assessment/profile-save.ts", import.meta.url).href)};
    export const profileSaveFeedback=${JSON.stringify(profileSaveFeedback)};
    export const profileSaveSections=${JSON.stringify(profileSaveSections)};`);
  const source = await readFile(new URL("../src/app/assessments/[id]/assessment-section-form.tsx", import.meta.url), "utf8");
  // React must not reuse refusal state for another server-rendered section or loaded version.
  for (const [section, editor] of [["context", "evaluation-context-editor"],
    ["capabilities", "capability-editor"], ["auditability", "auditability-editor"]]) {
    const binding = await readFile(new URL(`../src/app/assessments/[id]/${editor}.tsx`, import.meta.url), "utf8");
    assert.ok(binding.includes(`key={\`${section}:\${assessmentId}:\${version}\`}`));
    assert.ok(binding.includes(`section="${section}"`));
  }
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replaceAll('"react"', JSON.stringify(react)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflow)).replaceAll('"@/lib/assessment/profile-save"', JSON.stringify(saving));
  const { AssessmentSectionForm } = await import(moduleUrl(compiled));
  Object.defineProperty(globalThis, "FormData", { configurable: true, value: class {
    [Symbol.iterator]() { return params.entries(); }
  } });
  try {
    for (const section of Object.keys(profileSaveSections) as ProfileSection[]) {
      const action = profileSectionAction(section);
      params = profileFormFixture(section); const before = params.toString();
      for (const outcome of ["conflict", "uncertain", "signed-out", "forbidden", "not-found", "locked", "invalid", "saved", "detached"] as const) {
      let resolve: (value: string)=>void = ()=>{throw new Error("No deferred request");};
      const reply = new Promise<string>(done=>{resolve=done;});
      let calls=0, releases=0, opens=0, focusReturns=0;
      const busy: boolean[] = [], destinations: string[] = [];
      const memory = { cursor: 0, refCursor: 0, states: [] as unknown[], refs: [] as unknown[],
        lifecycle: { setSaving: (value: boolean)=>busy.push(value), allowReload: ()=>{releases++;} },
        save: (submittedSection: string, path: string, body: URLSearchParams)=>{calls++; assert.equal(submittedSection,section); assert.equal(path, action); assert.equal(body.toString(), before); return reply;} };
      globals[slot] = memory;
      Object.defineProperty(globalThis, "window", { configurable: true, value: {
        location: { assign: (path: string)=>destinations.push(path) },
      } });
      const render = () => {memory.cursor=0; memory.refCursor=0; return AssessmentSectionForm({section,action,children:"Server-rendered fields"});};
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
        assert.equal(releases, 1); assert.deepEqual(destinations, [profileReloadPath(section, action)]);
        const extra = event(); await handler(extra); assert.equal(extra.prevented, true); assert.equal(calls, 1);
      } else if (outcome === "detached") {
        assert.equal(releases, 0); assert.deepEqual(destinations, []);
      } else if (outcome === "invalid") {
        assert.equal(releases,0); assert.deepEqual(destinations,[]);
        form=render(); assert.ok(renderToStaticMarkup(form).includes("inputs were rejected"));
        const submit=elements(form).find(node=>node.type === "button" && node.props.type === "submit")!;
        assert.equal(submit.props.disabled,false);
        form.props.onChange(); form=render(); assert.equal(renderToStaticMarkup(form).includes('role="alert"'),false);
        await form.props.onSubmit(event()); assert.equal(calls,2); assert.equal(releases,0);
      } else {
        assert.equal(releases, 0); assert.deepEqual(destinations, []);
        // Even the pre-render callback is blocked after the reply, before React commits new state.
        const extra=event(); await handler(extra); assert.equal(extra.prevented,true); assert.equal(calls,1);
        form=render(); const html=renderToStaticMarkup(form);
        assert.ok(html.includes(profileSaveFeedback[outcome].title));
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
        assert.equal(opens,3); assert.equal(dialog.open,false); assert.equal(releases,1); assert.deepEqual(destinations,[profileReloadPath(section,action)]);
        (discard.props.onClick as ()=>void)(); assert.equal(destinations.length,1);
      }
      }
      // Parser refusals are local, and a valid form outside the workflow keeps native POST.
      const memory={cursor:0,refCursor:0,states:[] as unknown[],refs:[] as unknown[],lifecycle:null,
        save:()=>{throw new Error("Unexpected transport during native fallback or validation refusal");}};
      globals[slot]=memory;
      const nativeEvent={currentTarget:{isConnected:true},prevented:false,preventDefault(){this.prevented=true;}};
      await AssessmentSectionForm({section,action,children:"Fields"}).props.onSubmit(nativeEvent);
      assert.equal(nativeEvent.prevented,false);
      memory.cursor=0; memory.refCursor=0; params.append("workspaceId","forged");
      const invalidEvent={...nativeEvent,prevented:false};
      await AssessmentSectionForm({section,action,children:"Fields"}).props.onSubmit(invalidEvent);
      assert.equal(invalidEvent.prevented,true);
      memory.cursor=0; memory.refCursor=0;
      assert.ok(renderToStaticMarkup(AssessmentSectionForm({section,action,children:"Fields"})).includes("Nothing was sent"));
    }
    for (const [section, key, invalid, fieldId] of [
      ["context", "allowedCountries", "US, US", "context-allowedCountries"],
      ["auditability", "minimumRetentionDays", "030", "audit-retention-days"],
    ] as const) {
      params=profileFormFixture(section); const valid=params.toString(); params.set(key,invalid);
      const before=params.toString(); let calls=0, focus=0, releases=0;
      const busy: boolean[]=[];
      const memory={cursor:0,refCursor:0,states:[] as unknown[],refs:[] as unknown[],
        lifecycle:{setSaving:(value:boolean)=>busy.push(value),allowReload:()=>{releases++;}},
        save:async()=>{calls++; assert.equal(params.toString(),valid); return "invalid";}};
      globals[slot]=memory;
      const render=()=>{memory.cursor=0;memory.refCursor=0;return AssessmentSectionForm({
        section,action:profileSectionAction(section),children:"Unchanged fields"});};
      const event={currentTarget:{isConnected:true},prevented:false,preventDefault(){this.prevented=true;}};
      await render().props.onSubmit(event);
      assert.equal(event.prevented,true);assert.equal(calls,0);assert.equal(releases,0);assert.deepEqual(busy,[]);
      assert.equal(params.toString(),before);
      let form=render();assert.ok(renderToStaticMarkup(form).includes("Nothing was sent"));
      const link=elements(form).find(node=>node.type==="a" && node.props.href===`#${fieldId}`)!;
      assert.ok(link);let prevented=false;
      const field={focus(){focus++;},closest(selector:string){assert.equal(selector,"details");return null;}};
      const ownerForm={querySelector(selector:string){assert.equal(selector,`#${fieldId}`);return field;}};
      (link.props.onClick as (event:unknown)=>void)({preventDefault(){prevented=true;},
        currentTarget:{closest(selector:string){assert.equal(selector,"form");return ownerForm;}}});
      assert.equal(prevented,true);assert.equal(focus,1);assert.equal(calls,0);assert.equal(releases,0);
      params=new URLSearchParams(valid);form.props.onChange();form=render();
      assert.equal(renderToStaticMarkup(form).includes('role="alert"'),false);
      assert.equal(calls,0); // Editing and following a field link never submit.
      await form.props.onSubmit({...event,prevented:false});
      assert.equal(calls,1);assert.equal(releases,0);assert.deepEqual(busy,[true,false]);
    }
  } finally {
    Reflect.deleteProperty(globals, slot);
    if (originalFormData) Object.defineProperty(globalThis, "FormData", originalFormData);
    if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow); else Reflect.deleteProperty(globalThis,"window");
  }
});
