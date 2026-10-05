import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { parseUsagePlanningForm, usageMetrics } from "../src/lib/assessment/usage-planning.ts";
import { usagePlanningFormIssues } from "../src/lib/assessment/usage-form-validation.ts";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";

function form() {
  const params = new URLSearchParams({ expectedVersion: "0", scopeDescription: "" });
  for (let i = 0; i < 10; i++) params.append("assumption", "");
  for (const metric of usageMetrics) {
    params.set(`basis_${metric.key}`, "UNKNOWN"); params.set(`value_${metric.key}`, "");
  }
  return params;
}

test("all 625 valid quantity combinations remain saveable without scope or assumption inference", () => {
  const states = [["UNKNOWN", ""], ["ASSUMED", "0"], ["ASSUMED", "7"], ["OBSERVED", "0"], ["OBSERVED", "7"]];
  for (let combination = 0; combination < 625; combination++) {
    const params = form();
    usageMetrics.forEach((metric, index) => {
      const [basis, value] = states[Math.floor(combination / 5 ** index) % 5];
      params.set(`basis_${metric.key}`, basis); params.set(`value_${metric.key}`, value);
    });
    const before = params.toString();
    assert.deepEqual(usagePlanningFormIssues(params), []);
    assert.doesNotThrow(() => parseUsagePlanningForm(params));
    assert.equal(params.toString(), before);
  }
});

test("each Unknown/number and Assumed or Observed/blank pair points to its exact value field", () => {
  for (const metric of usageMetrics) {
    for (const [basis, value] of [["UNKNOWN", "0"], ["UNKNOWN", "7"], ["ASSUMED", ""], ["OBSERVED", ""]]) {
      const params = form(); params.set(`basis_${metric.key}`, basis); params.set(`value_${metric.key}`, value);
      const before = params.toString(); const issues = usagePlanningFormIssues(params);
      assert.equal(issues.length, 1); assert.equal(issues[0].fieldId, `usage-value-${metric.key}`);
      assert.ok(issues[0].message.includes(metric.label)); assert.ok(issues[0].message.includes("Unknown"));
      assert.throws(() => parseUsagePlanningForm(params)); assert.equal(params.toString(), before);
    }
  }
});

test("numeric feedback retains the existing canonical safe-integer boundary", () => {
  for (const metric of usageMetrics) {
    for (const value of ["0", "1", "9007199254740991"]) {
      const params = form(); params.set(`basis_${metric.key}`, "OBSERVED"); params.set(`value_${metric.key}`, value);
      assert.deepEqual(usagePlanningFormIssues(params), []);
    }
    for (const value of ["-1", "+1", "01", "1.0", "1e3", " 1", "NaN", "Infinity", "9007199254740992"]) {
      const params = form(); params.set(`basis_${metric.key}`, "ASSUMED"); params.set(`value_${metric.key}`, value);
      assert.equal(usagePlanningFormIssues(params)[0].fieldId, `usage-value-${metric.key}`);
      assert.throws(() => parseUsagePlanningForm(params));
    }
  }
});

test("assumption feedback addresses whitespace, exact duplicates and text limits without echoing text", () => {
  for (const [index, text, expected] of [[0, " \n ", "spaces alone"], [4, "x".repeat(501), "500 characters"],
    [8, "</textarea><script>private-looking input</script>", "already recorded"]] as const) {
    const params = form(); const entries = params.getAll("assumption"); entries[index] = text;
    if (index === 8) entries[1] = text;
    params.delete("assumption"); entries.forEach(value => params.append("assumption", value));
    const before = params.toString(); const issues = usagePlanningFormIssues(params);
    assert.equal(issues.length, 1); assert.equal(issues[0].fieldId, `usage-assumption-${index}`);
    assert.ok(issues[0].message.includes(expected)); assert.ok(!issues[0].message.includes(text));
    assert.equal(params.toString(), before); assert.throws(() => parseUsagePlanningForm(params));
  }
  const params = form(); params.delete("assumption");
  ["Pilot", "pilot", " Pilot", "Pilot ", "first\nsecond", ...Array(5).fill("")].forEach(text=>params.append("assumption",text));
  assert.deepEqual(usagePlanningFormIssues(params), []); // Match exact server semantics, not a new normalization policy.
});

test("scope limit and multiple feedback items remain fixed messages in stable field order", () => {
  const params = form(); params.set("scopeDescription", "private ".repeat(100));
  for (const metric of usageMetrics) params.set(`basis_${metric.key}`, "ASSUMED");
  const issues = usagePlanningFormIssues(params);
  assert.deepEqual(issues.map(issue=>issue.fieldId), ["usage-scope", ...usageMetrics.map(metric=>`usage-value-${metric.key}`)]);
  assert.ok(issues.every(issue=>!issue.message.includes("private")));
  assert.throws(() => parseUsagePlanningForm(params));
});

test("malformed shape, metadata and enums cannot be accepted by the UI helper or supply arbitrary links", () => {
  const mutations = [
    (p: URLSearchParams)=>p.set("expectedVersion", "<script>"),
    (p: URLSearchParams)=>p.append("expectedVersion", "0"),
    (p: URLSearchParams)=>p.delete("scopeDescription"),
    (p: URLSearchParams)=>p.append("scopeDescription", ""),
    (p: URLSearchParams)=>p.append("unexpected", "private"),
    (p: URLSearchParams)=>p.delete("assumption"),
    (p: URLSearchParams)=>p.append("assumption", ""),
    (p: URLSearchParams)=>p.set("basis_MONTHLY_ACTIVE_USERS", "javascript:alert(1)"),
    (p: URLSearchParams)=>p.delete("value_MONTHLY_ACTIVE_USERS"),
    (p: URLSearchParams)=>p.append("basis_MONTHLY_ACTIVE_USERS", "UNKNOWN"),
  ];
  for (const mutate of mutations) {
    const params = form(); mutate(params);
    assert.deepEqual(usagePlanningFormIssues(params), [{ fieldId: null,
      message: "These inputs could not be read safely. Reload this page and try again." }]);
    assert.throws(() => parseUsagePlanningForm(params));
  }
});

test("form enhancement keeps the native POST and server children without additional submitted controls", async () => {
  const { UsagePlanningForm } = await assessmentUiComponents();
  const action = "/api/assessments/4640bbac-c20f-476a-a4dc-23efad5ff14f/usage-planning";
  const html = renderToStaticMarkup(createElement(UsagePlanningForm, { action },
    createElement("input", { name: "expectedVersion", type: "hidden", value: "5" })));
  assert.ok(html.includes(`action="${action}" method="post"`));
  assert.equal((html.match(/<input\b/g) ?? []).length, 1);
  assert.equal(html.includes('role="alert"'), false); assert.equal(html.includes("<script"), false);
});

test("blocked form submissions do not clear the workflow dirty guard or introduce client persistence", async () => {
  const source = await readFile(new URL("../src/app/assessments/[id]/assessment-workflow.tsx", import.meta.url), "utf8");
  assert.ok(source.includes('onSubmit={event => {\n          if (!step.input || event.defaultPrevented) return;'));
  assert.equal(source.includes("onSubmitCapture="), false);
  const formSource = await readFile(new URL("../src/app/assessments/[id]/usage-planning-form.tsx", import.meta.url), "utf8");
  assert.ok(formSource.includes("event.preventDefault()")); assert.ok(formSource.includes("summary.current?.focus()"));
  assert.ok(formSource.includes("details.open = true"));
  for (const unsupported of ["localStorage", "sessionStorage", "setCustomValidity", ".submit(", ".requestSubmit("])
    assert.equal(formSource.includes(unsupported), false);
});
