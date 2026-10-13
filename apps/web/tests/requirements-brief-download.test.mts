import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { RequirementsBriefDownloadError } from "../src/lib/assessment/requirements-brief.ts";
import { DecisionBriefDownloadError } from "../src/lib/assessment/decision-brief.ts";

function moduleUrl(source: string) { return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`; }
type View = { type: unknown; props: Record<string, unknown> };
function nodes(value: unknown): View[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const view = value as View;
  return [view, ...nodes(view.props.children)];
}

// Real component handlers, deterministic hook/DOM test doubles, not browser E2E.
for (const kind of ["requirements", "decision"] as const) test(`${kind} brief download blocks duplicate callbacks, safely reports refusals and never downloads after unmount`, async () => {
  const slot = `__authweave_brief_download_${crypto.randomUUID()}`;
  const globals = globalThis as unknown as Record<string, unknown>;
  const originals = new Map(["window", "document"].map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const createUrl = Object.getOwnPropertyDescriptor(URL, "createObjectURL"), revokeUrl = Object.getOwnPropertyDescriptor(URL, "revokeObjectURL");
  const react = moduleUrl(`export function useState(initial) { const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++; if(!(i in s.states))s.states[i]=initial;return [s.states[i],value=>{s.updates++;s.states[i]=value;}]; }
    export function useRef(initial) { const s=globalThis[${JSON.stringify(slot)}]; return s.ref ??= {current:initial}; }
    export function useEffect(run) { const s=globalThis[${JSON.stringify(slot)}]; s.cleanup ??= run(); }`);
  const reader = moduleUrl(`export { ${kind === "requirements" ? "RequirementsBriefDownloadError" : "DecisionBriefDownloadError"} } from ${JSON.stringify(new URL(`../src/lib/assessment/${kind === "requirements" ? "requirements" : "decision"}-brief.ts`, import.meta.url).href)};
    export function ${kind === "requirements" ? "requestSavedRequirementsBrief" : "requestSavedDecisionBrief"}(...args) {return globalThis[${JSON.stringify(slot)}].request(...args);}`);
  const source = await readFile(new URL(`../src/app/assessments/[id]/${kind === "requirements" ? "saved-requirements-export" : "results/result-export"}.tsx`, import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replaceAll('"react"', JSON.stringify(react)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll(`"@/lib/assessment/${kind === "requirements" ? "requirements" : "decision"}-brief"`, JSON.stringify(reader));
  const component = await import(moduleUrl(compiled));
  const Export = kind === "requirements" ? component.SavedRequirementsExport : component.SavedResultExport;
  const id = "80000000-0000-4000-8000-000000000001", reference = { resultId: "90000000-0000-4000-8000-000000000001", version: 7, resultSha256: "a".repeat(64) };
  const filename = kind === "requirements" ? `authweave-requirements-${id}-v7.md` : `authweave-decision-${id}-${reference.resultId}-v7.md`;
  try {
    for (const outcome of ["saved", "session", "not-found", "stale", "unavailable", "unexpected", "link-failure", "unmounted-saved", "unmounted-error"] as const) {
      if (kind === "decision" && outcome === "stale") continue;
      let resolve: (result: { filename: string; markdown: string }) => void = () => { throw new Error("No pending request"); };
      let reject: (error: Error) => void = () => { throw new Error("No pending request"); };
      const reply = new Promise<{ filename: string; markdown: string }>((done, fail) => { resolve = done; reject = fail; });
      let calls = 0, creations = 0, clicks = 0, removals = 0;
      const signals: AbortSignal[] = [], scheduled: (() => void)[] = [], revoked: string[] = [];
      const link = { href: "", download: "", click() { clicks++; if (outcome === "link-failure") throw new Error("private browser failure"); }, remove() { removals++; } };
      const memory = { cursor: 0, states: [] as unknown[], updates: 0, cleanup: undefined as (() => void) | undefined,
        request(assessmentId: string, version: number | typeof reference, signal: AbortSignal) {
          calls++; assert.equal(assessmentId, id); assert.deepEqual(version, kind === "requirements" ? 7 : reference); assert.ok(signal instanceof AbortSignal); signals.push(signal); return reply;
        } };
      globals[slot] = memory;
      Object.defineProperty(globalThis, "document", { configurable: true, value: {
        createElement(tag: string) { assert.equal(tag, "a"); creations++; return link; },
        body: { appendChild(child: unknown) { assert.equal(child, link); assert.equal(link.href, "blob:synthetic-brief"); assert.equal(link.download, filename); } },
      } });
      Object.defineProperty(globalThis, "window", { configurable: true, value: {
        setTimeout(callback: () => void, delay: number) { assert.equal(delay, 30_000); scheduled.push(callback); return scheduled.length; },
      } });
      Object.defineProperty(URL, "createObjectURL", { configurable: true, value: (blob: Blob) => { assert.equal(blob.type, "text/markdown;charset=utf-8"); return "blob:synthetic-brief"; } });
      Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: (url: string) => revoked.push(url) });
      const render = () => { memory.cursor = 0; return Export({ assessmentId: id, version: 7, reference }); };
      const button = (tree: unknown) => nodes(tree).find(node => node.type === "button")!;
      const initial = render();
      if (kind === "decision") {
        const html = renderToStaticMarkup(initial);
        assert.ok(html.includes('aria-labelledby="result-export-heading"')); assert.ok(html.includes('id="result-export-heading"'));
        assert.ok(html.includes("Take this saved calculation with you"));
      }
      const handler = button(initial).props.onClick as () => Promise<void>;
      const request = handler(); await handler();
      assert.equal(calls, 1); assert.equal(signals[0].aborted, false);
      const pending = renderToStaticMarkup(render()); assert.ok(pending.includes('disabled=""')); assert.ok(pending.includes(kind === "requirements" ? "Preparing saved brief" : "Preparing calculation brief"));
      const unmounted = outcome.startsWith("unmounted");
      if (unmounted) memory.cleanup!();
      const updates = memory.updates;
      if (["saved", "link-failure", "unmounted-saved"].includes(outcome)) resolve({ filename, markdown: "# Synthetic validated brief" });
      else reject(["unexpected", "unmounted-error"].includes(outcome) ? new Error("private upstream failure") :
        kind === "requirements" ? new RequirementsBriefDownloadError(outcome as RequirementsBriefDownloadError["kind"]) :
          new DecisionBriefDownloadError(outcome as DecisionBriefDownloadError["kind"]));
      await request;
      if (unmounted) {
        assert.equal(signals[0].aborted, true); assert.equal(memory.updates, updates);
        assert.equal(creations, 0); assert.equal(clicks, 0); assert.equal(scheduled.length, 0);
      } else {
        const html = renderToStaticMarkup(render());
        assert.equal(html.includes("private upstream"), false); assert.equal(html.includes("private browser"), false);
        assert.equal(button(render()).props.disabled, false);
        if (outcome === "saved") {
          assert.equal(clicks, 1); assert.equal(removals, 1);
          assert.ok(html.includes(kind === "requirements" ? "Download requested for saved version 7" : "Download requested for calculation version 7")); assert.equal(html.includes('role="alert"'), false);
        } else {
          assert.ok(html.includes('role="alert"')); assert.equal(html.includes("Download requested"), false);
          if (outcome !== "link-failure") assert.equal(creations, 0);
        }
        if (outcome === "saved" || outcome === "link-failure") {
          assert.equal(scheduled.length, 1); assert.deepEqual(revoked, []); scheduled[0](); assert.deepEqual(revoked, ["blob:synthetic-brief"]);
        }
        await handler(); assert.equal(calls, 2); // Only another explicit click retries.
        memory.cleanup!();
      }
    }
  } finally {
    Reflect.deleteProperty(globals, slot);
    for (const [name, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor); else Reflect.deleteProperty(globalThis, name);
    }
    if (createUrl) Object.defineProperty(URL, "createObjectURL", createUrl);
    if (revokeUrl) Object.defineProperty(URL, "revokeObjectURL", revokeUrl);
  }
});
