import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { assessmentSteps, assessmentStepFromQuery, workflowTransition,
  type WorkflowState } from "../src/lib/assessment/workflow.ts";

test("workflow has four saved-input sections followed by two conditional previews, not completion flags", () => {
  assert.deepEqual(assessmentSteps.map(step => step.id), ["context", "capabilities", "auditability", "usage", "comparison", "architecture"]);
  assert.deepEqual(assessmentSteps.map(step => step.input), [true, true, true, true, false, false]);
  assert.equal(new Set(assessmentSteps.map(step => step.id)).size, 6);
  for (const step of assessmentSteps) assert.ok(step.title && step.description && step.short);
});

test("step selection accepts only known UI names and known errors take precedence over navigation", () => {
  for (const step of assessmentSteps) assert.equal(assessmentStepFromQuery({ step: step.id }), step.id);
  for (const value of [undefined, "", "toString", "__proto__", "CONTEXT", "javascript:alert(1)", ["context", "usage"]]) {
    assert.equal(assessmentStepFromQuery({ step: value }), "context");
  }
  for (const [key, step] of [["contextError", "context"], ["editError", "capabilities"],
    ["auditError", "auditability"], ["usageError", "usage"]]) {
    for (const error of ["stale", "invalid", "locked"]) {
      assert.equal(assessmentStepFromQuery({ step: "architecture", [key]: error }), step);
    }
    for (const value of ["verified", "saved", "toString", ["stale", "invalid"]]) {
      assert.equal(assessmentStepFromQuery({ step: "architecture", [key]: value }), "architecture");
    }
  }
});

test("unsaved navigation stays on the original step until explicit discard, while cancel preserves edits", () => {
  for (const step of assessmentSteps.filter(step => step.input)) {
    const initial: WorkflowState = { step: step.id, dirty: false, pending: null };
    const edited = workflowTransition(initial, { type: "edit" });
    assert.deepEqual(initial, { step: step.id, dirty: false, pending: null });
    assert.equal(edited.dirty, true);
    assert.equal(workflowTransition(edited, { type: "navigate", step: step.id }), edited);
    const pending = workflowTransition(edited, { type: "navigate", step: "comparison" });
    assert.deepEqual(pending, { step: step.id, dirty: true, pending: "comparison" });
    assert.deepEqual(workflowTransition(pending, { type: "cancel" }), edited);
    assert.deepEqual(workflowTransition(pending, { type: "discard" }), { step: "comparison", dirty: false, pending: null });
    assert.equal(workflowTransition(edited, { type: "discard" }), edited);
  }
});

test("submitting clears only local navigation guard state and preview edits cannot become persisted requirements", () => {
  const input: WorkflowState = { step: "auditability", dirty: true, pending: "usage" };
  const submitted = workflowTransition(input, { type: "submit" });
  assert.deepEqual(submitted, { step: "auditability", dirty: false, pending: null });
  assert.deepEqual(Object.keys(submitted).sort(), ["dirty", "pending", "step"]);
  assert.deepEqual(workflowTransition(submitted, { type: "navigate", step: "usage" }), { step: "usage", dirty: false, pending: null });
  for (const step of ["comparison", "architecture"] as const) {
    const preview: WorkflowState = { step, dirty: false, pending: null };
    assert.equal(workflowTransition(preview, { type: "edit" }), preview);
  }
});

test("workflow renders one labelled panel, six keyboard buttons and honest saved/read-only status", async () => {
  const source = await readFile(new URL("../src/app/assessments/[id]/assessment-workflow.tsx", import.meta.url), "utf8");
  const router = `data:text/javascript;base64,${Buffer.from('export function useRouter() { return { push() { throw new Error("No navigation during rendering"); } }; }').toString("base64")}`;
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"react"', JSON.stringify(import.meta.resolve("react")))
    .replaceAll('"next/navigation"', JSON.stringify(router))
    .replaceAll('"@/lib/assessment/workflow"', JSON.stringify(new URL("../src/lib/assessment/workflow.ts", import.meta.url).href));
  const component = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
  const panels = Object.fromEntries(assessmentSteps.map(step => [step.id,
    createElement("div", { "data-fixture-panel": step.id }, `Saved ${step.id} fixture`)]));
  for (const step of assessmentSteps) {
    const html = renderToStaticMarkup(createElement(component.AssessmentWorkflow, { initialStep: step.id, panels, editable: true }));
    assert.equal((html.match(/aria-current="step"/g) ?? []).length, 1);
    assert.equal((html.match(/aria-controls="assessment-step-panel"/g) ?? []).length, 6);
    assert.equal((html.match(/data-fixture-panel=/g) ?? []).length, 1);
    assert.ok(html.includes(`data-fixture-panel="${step.id}"`));
    assert.ok(html.includes('aria-labelledby="assessment-step-heading"'));
    assert.ok(html.includes('id="assessment-step-heading" tabindex="-1"'));
    assert.ok(html.includes("There is no autosave"));
    assert.ok(html.includes("Step numbers show your location, not completion"));
    assert.ok(html.includes('aria-labelledby="discard-edits-heading"'));
    assert.equal(html.includes("100% complete"), false);
  }
  const readOnly = renderToStaticMarkup(createElement(component.AssessmentWorkflow, { initialStep: "context", panels, editable: false }));
  assert.ok(readOnly.includes("Showing a read-only saved assessment"));
  assert.equal(readOnly.includes("There is no autosave"), false);
});
