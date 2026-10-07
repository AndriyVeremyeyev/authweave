import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { comparisonMatrix, matrixSelection, evidenceFamilies } from "../src/lib/assessment/comparison-matrix.ts";
import { comparisonMatrixFixture } from "./fixtures/comparison-matrix.mts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

test("matrix joins every exact option/path across five groups without reordering Core candidates or promoting evidence", () => {
  const { comparison, evidence } = comparisonMatrixFixture();
  evidence.reverse(); for (const option of evidence) { option.groups.reverse(); for (const group of option.groups) group.rows.reverse(); }
  const before = structuredClone({ comparison, evidence }), matrix = comparisonMatrix(comparison.candidates, evidence)!;
  assert.deepEqual(matrix.options.map(o => o.optionId), comparison.candidates.map(c => c.optionId));
  assert.deepEqual(matrix.groups.map(g => g.family), Object.keys(evidenceFamilies));
  assert.deepEqual(matrix.groups.map(g => g.rows.length), [9, 19, 4, 36, 6]);
  const cells = matrix.groups[0].rows.find(r => r.path === "facts.SCIM")!.cells;
  assert.deepEqual(cells.map(c => c.evidence.gate), ["UNREVIEWED", "STALE", "FUTURE", "MISSING"]);
  assert.deepEqual(cells.map(c => c.evidence.claim), ["UNKNOWN", "OPTIONAL", "MANDATORY", null]);
  assert.equal(matrix.groups[4].rows[0].cells[0].evidence.configuration, "Synthetic logging enabled");
  assert.deepEqual({ comparison, evidence }, before);
  cells[0].evidence.claim = "Changed display copy";
  assert.deepEqual({ comparison, evidence }, before, "Cells are copied, never a mutable alias into the guarded snapshot.");
});

test("incomplete, duplicate or foreign inventories never become a fabricated missing-evidence table", () => {
  const seed = comparisonMatrixFixture();
  for (const mutate of [s => s.evidence.pop(), s => s.evidence[0].optionId = "foreign",
    s => s.evidence[0].optionId = s.evidence[1].optionId, s => s.comparison.candidates[0].optionId = s.comparison.candidates[1].optionId,
    s => s.evidence[0].groups.pop(), s => s.evidence[0].groups[0].family = "CONTEXT",
    s => s.evidence[0].groups[0].rows.pop(), s => s.evidence[0].groups[0].rows[0].path = s.evidence[0].groups[0].rows[1].path,
    s => s.evidence[1].groups[0].rows[0].path = "facts.FOREIGN", s => s.evidence[1].groups[0].rows[0].label = "Foreign label"] as ((s: typeof seed) => void)[]) {
    const data = structuredClone(seed); mutate(data); assert.equal(comparisonMatrix(data.comparison.candidates, data.evidence), null);
  }
  assert.equal(comparisonMatrix([], []), null);
});

test("selection limits display to three in Core order, including excluded/unresolved options, with no winner inference", () => {
  const { comparison } = comparisonMatrixFixture(), candidates = comparison.candidates;
  assert.deepEqual(matrixSelection(candidates, ["fictional-d", "fictional-a", "fictional-c"]).map(c => c.optionId), ["fictional-a", "fictional-c", "fictional-d"]);
  assert.deepEqual(matrixSelection(candidates, candidates.map(c => c.optionId).reverse()).map(c => c.optionId), ["fictional-a", "fictional-b", "fictional-c"]);
  assert.deepEqual(matrixSelection(candidates, ["fictional-a", "fictional-a", "foreign"]).map(c => c.hardVerdict), ["EXCLUDED"]);
  assert.deepEqual(matrixSelection(candidates, []), []);
});

test("actual SSR table has scoped headers, explicit limitations, inert source text and a readable no-JavaScript default", async () => {
  const { comparison, evidence } = comparisonMatrixFixture(), components = await assessmentUiComponents();
  evidence[0].groups[0].rows[0].sourceUrl = 'https://catalog.invalid/doc?literal=<script>fictional()</script>&quote="text"';
  const before = structuredClone({ comparison, evidence });
  const html = renderToStaticMarkup(createElement(components.ComparisonMatrix, { candidates: comparison.candidates, evidence }));
  for (const copy of ["Compare fictional evidence side by side", "not provider recommendations", "not real source or deployed-behavior verification",
    "not answers, verdicts, weights or Core order", "for readability, not quality", "Excluded and unresolved options remain selectable",
    "not an applied-check count or complete coverage", "JavaScript is required", "No fact recorded", "Unreviewed claim", "Older than 90 days", "Future-dated",
    "Excluded by a checked requirement", "Passes checked requirements only", "2026-10-02T12:00:00.123456789Z"]) assert.ok(html.includes(copy), copy);
  assert.equal((html.match(/scope="col"/g) ?? []).length, 4); assert.equal((html.match(/scope="row"/g) ?? []).length, 9);
  assert.ok(html.includes('<fieldset disabled=""')); assert.ok(html.includes('tabindex="0"'));
  assert.ok(html.includes("--matrix-mobile-width:672px")); assert.ok(html.includes("--matrix-desktop-width:896px"));
  assert.ok(html.includes("w-36")); assert.ok(html.includes("sm:w-56"));
  assert.equal((html.match(/<td /g) ?? []).length, 27);
  assert.ok(html.includes("&lt;script&gt;fictional()&lt;/script&gt;")); assert.equal(html.includes("<script>fictional"), false);
  assert.equal(html.includes("<a "), false); assert.equal(html.includes("<form"), false);
  assert.deepEqual({ comparison, evidence }, before);
  const unavailable = renderToStaticMarkup(createElement(components.ComparisonMatrix, { candidates: comparison.candidates, evidence: [] }));
  assert.ok(unavailable.includes("matrix is unavailable")); assert.equal(unavailable.includes("<table"), false);
});

const slot = `__authweave_matrix_${crypto.randomUUID()}`;
const globals = globalThis as unknown as Record<string, unknown>;
const moduleUrl = (text: string) => `data:text/javascript;base64,${Buffer.from(text).toString("base64")}`;
const hooks = moduleUrl(`export function useState(initial){const s=globalThis[${JSON.stringify(slot)}],i=s.cursor++;
  if(!(i in s.slots))s.slots[i]=typeof initial==='function'?initial():initial;return [s.slots[i],v=>{s.slots[i]=typeof v==='function'?v(s.slots[i]):v;}];}
  export function useSyncExternalStore(subscribe,snapshot){return snapshot();}`);
const source = await readFile(new URL("../src/app/assessments/[id]/comparison-matrix.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
  .replaceAll('"react"', JSON.stringify(hooks)).replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
  .replaceAll('"@/lib/assessment/comparison-matrix"', JSON.stringify(new URL("../src/lib/assessment/comparison-matrix.ts", import.meta.url).href))
  .replaceAll('"@/lib/assessment/comparison-presentation"', JSON.stringify(new URL("../src/lib/assessment/comparison-presentation.ts", import.meta.url).href));
const { ComparisonMatrix } = await import(moduleUrl(compiled));
type Node = { type: unknown; key?: string; props: Record<string, unknown> };
function nodes(value: unknown): Node[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const node = value as Node; return [node, ...nodes(node.props.children)];
}
async function withMatrix(run: (h: ReturnType<typeof harness>) => void | Promise<void>) {
  const previous = globals[slot], fetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("Matrix must not perform network calls"); };
  const h = harness();
  try { await run(h); } finally { if (previous === undefined) delete globals[slot]; else globals[slot] = previous; globalThis.fetch = fetch; }
}
// Real component handlers, deterministic state hooks; browser hydration/keyboard are checked separately.
function harness() {
  const memory = { cursor: 0, slots: [] as unknown[] }, data = comparisonMatrixFixture(); globals[slot] = memory;
  const render = () => { memory.cursor = 0; return ComparisonMatrix({ candidates: data.comparison.candidates, evidence: data.evidence }); };
  const tree = () => nodes(render());
  const headers = () => tree().filter(n => n.type === "th" && n.props.scope === "col").slice(1).map(n => n.key);
  const checks = () => tree().filter(n => n.type === "input" && n.props.type === "checkbox");
  const toggle = (index: number) => (checks()[index].props.onChange as () => void)();
  const choose = (value: string) => (tree().find(n => n.type === "select")!.props.onChange as (e: unknown) => void)({ currentTarget: { value } });
  return { data, memory, render, tree, headers, checks, toggle, choose, html: () => renderToStaticMarkup(render()) };
}

test("real handlers enforce three-option limit, preserve Core order across rapid clicks and keep verdicts/evidence immutable", async () => withMatrix(h => {
  const before = structuredClone(h.data);
  assert.deepEqual(h.headers(), ["fictional-a", "fictional-b", "fictional-c"]); assert.equal(h.checks()[3].props.disabled, true);
  h.toggle(3); assert.deepEqual(h.headers(), ["fictional-a", "fictional-b", "fictional-c"]);
  h.toggle(0); h.toggle(3); assert.deepEqual(h.headers(), ["fictional-b", "fictional-c", "fictional-d"]);
  h.toggle(1); h.toggle(0); assert.deepEqual(h.headers(), ["fictional-a", "fictional-c", "fictional-d"]);
  assert.ok(h.html().includes("Core status: Excluded by a checked requirement")); assert.deepEqual(h.data, before);
}));
test("all five families retain exact counts, claim scopes and unknowns; lookalike family values cannot navigate or infer data", async () => withMatrix(h => {
  const before = structuredClone(h.data);
  for (const [index, family] of Object.keys(evidenceFamilies).entries()) {
    h.choose(family); const html = h.html();
    assert.equal(h.tree().filter(n => n.type === "th" && n.props.scope === "row").length, [9, 19, 4, 36, 6][index]);
    assert.ok(html.includes(evidenceFamilies[family as keyof typeof evidenceFamilies]));
    if (family === "RESIDENCY") { assert.ok(html.includes("PARTIAL · DE, US")); assert.ok(html.includes("not a region menu")); }
    if (family === "AUTHENTICATION_CONTROL") { assert.ok(html.includes("Enforcement capability: UNKNOWN")); assert.ok(html.includes("enrollment/recovery")); }
    if (family === "AUDITABILITY") { assert.ok(html.includes("Exact configuration: Synthetic logging enabled")); assert.ok(html.includes("not application logs")); }
  }
  for (const invalid of ["__proto__", "constructor", "capability", "../other", "https://catalog.invalid/"]) {
    h.choose(invalid); assert.ok(h.html().includes("Identity-provider auditability fictional evidence table"));
  }
  assert.deepEqual(h.data, before);
}));
test("empty selection is explicit, restoration is manual and a fresh mount resets only temporary display choices", async () => withMatrix(h => {
  for (const index of [0, 1, 2]) h.toggle(index);
  assert.ok(h.html().includes("Choose at least one option")); assert.equal(h.html().includes("<table"), false);
  assert.ok(h.checks().every(n => n.props.disabled === false));
  h.choose("AUDITABILITY"); h.toggle(3); assert.deepEqual(h.headers(), ["fictional-d"]);
  const before = structuredClone(h.data); h.memory.slots = []; assert.deepEqual(h.headers(), ["fictional-a", "fictional-b", "fictional-c"]);
  assert.ok(h.html().includes("Identity capabilities fictional evidence table")); assert.deepEqual(h.data, before);
}));
test("server Comparison passes only display scopes and remounts matrix on changed snapshot, without replacing option cards", async () => {
  const components = await assessmentUiComponents(), data = comparisonMatrixFixture();
  const render = () => components.ComparisonSection({ comparison: data.comparison, evidence: data.evidence, profile: savedRequirementsFixture(), editable: false });
  const matrix = () => nodes(render()).find(n => n.type === components.ComparisonMatrix)!;
  const first = matrix(), before = first.key;
  assert.deepEqual(Object.keys((first.props.candidates as Record<string, unknown>[])[0]), ["optionId", "displayName", "plan", "region", "hardVerdict"]);
  data.comparison.assessmentVersion++; assert.notEqual(matrix().key, before);
  const html = renderToStaticMarkup(render()); assert.ok(html.includes("Compare fictional evidence side by side"));
  assert.ok(html.includes('aria-label="Fictional options in Core order"')); assert.ok(html.includes("Inspect fictional evidence"));
  assert.equal(html.includes("Best provider"), false);
});
