import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { InvalidUsagePlanningForm, parseUsagePlanningForm, usageMetrics, usagePlanningValues,
  type UsagePlanningValues, type UsageQuantity } from "../src/lib/assessment/usage-planning.ts";
import { usageAssumptionGuidance, usageBasisGuidance, usageGuidance, usageScopeGuidance } from "../src/lib/assessment/usage-guidance.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

const id = "4640bbac-c20f-476a-a4dc-23efad5ff14f";
const { UsagePlanningEditor } = await assessmentUiComponents();
function render(values: UsagePlanningValues, version = 5) {
  return renderToStaticMarkup(createElement(UsagePlanningEditor, { assessmentId: id, version, values }));
}

function decode(value: string) {
  const entities: Record<string, string> = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&#x27;": "'" };
  return value.replace(/&(?:amp|lt|gt|quot|#x27);/g, entity => entities[entity]);
}

// Successful native controls from the actual SSR form; decode React's escaped text.
function formData(html: string) {
  const params = new URLSearchParams();
  for (const [tag] of html.matchAll(/<input\b[^>]*>/g)) {
    params.append(tag.match(/\bname="([^"]+)"/)![1], decode(tag.match(/\bvalue="([^"]*)"/)![1]));
  }
  for (const [, name, text] of html.matchAll(/<textarea\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)<\/textarea>/g)) {
    // HTML parsing discards one leading newline in textarea content; React compensates for it.
    params.append(name, decode(text.replace(/^\n/, "")));
  }
  for (const [, name, options] of html.matchAll(/<select\b[^>]*name="([^"]+)"[^>]*>(.*?)<\/select>/g)) {
    const selected = [...options.matchAll(/<option\b[^>]*>/g)].filter(([tag]) => tag.includes('selected=""'));
    assert.equal(selected.length, 1);
    params.append(name, selected[0][0].match(/\bvalue="([^"]*)"/)![1]);
  }
  return params;
}

test("usage guidance covers exactly the four project metrics and three basis states", () => {
  assert.deepEqual(Object.keys(usageGuidance), usageMetrics.map(metric => metric.key));
  assert.deepEqual(Object.keys(usageBasisGuidance), ["UNKNOWN", "ASSUMED", "OBSERVED"]);
  assert.deepEqual(Object.values(usageGuidance).map(guide => guide.unitLabel),
    ["users / month", "configured connections", "token issuances / month", "successful logins / second"]);
  assert.ok(usageScopeGuidance.example.includes("fictional"));
  assert.ok(usageAssumptionGuidance.example.includes("do not fill any field"));
});

test("all 625 unknown, assumed and observed zero/nonzero combinations round-trip without inference", () => {
  const states: Array<UsageQuantity | undefined> = [undefined, { basis: "ASSUMED", value: 0 },
    { basis: "ASSUMED", value: 7 }, { basis: "OBSERVED", value: 0 }, { basis: "OBSERVED", value: 7 }];
  for (let combination = 0; combination < states.length ** usageMetrics.length; combination++) {
    const values: UsagePlanningValues = { scopeDescription: "Fictional monthly pilot", assumptions: [], volumes: {} };
    for (const [index, metric] of usageMetrics.entries()) {
      const state = states[Math.floor(combination / states.length ** index) % states.length];
      if (state) values.volumes[metric.key] = { ...state };
    }
    const before = structuredClone(values);
    const html = render(values);
    assert.ok(html.includes(`action="/api/assessments/${id}/usage-planning" method="post"`));
    assert.deepEqual(parseUsagePlanningForm(formData(html)), { expectedVersion: 5, values });
    assert.equal([...html.matchAll(/<input\b/g)].length, 5);
    assert.equal([...html.matchAll(/<select\b/g)].length, 4);
    assert.equal([...html.matchAll(/<textarea\b/g)].length, 11);
    assert.equal([...html.matchAll(/<button\b/g)].length, 1);
    assert.deepEqual(values, before);
  }
});

test("unknowns stay empty, quantity boundaries remain unchanged and mismatched pairs still fail", () => {
  const empty: UsagePlanningValues = { scopeDescription: "", assumptions: [], volumes: {} };
  const html = render(empty, 0);
  assert.deepEqual(parseUsagePlanningForm(formData(html)), { expectedVersion: 0, values: empty });
  assert.equal([...html.matchAll(/value="UNKNOWN" selected=""/g)].length, 4);
  for (const metric of usageMetrics) {
    const input = html.match(new RegExp(`<input[^>]*name="value_${metric.key}"[^>]*>`))![0];
    assert.ok(input.includes('value=""')); assert.equal(input.includes('placeholder='), false);
    assert.ok(input.includes('min="0"')); assert.ok(input.includes('max="9007199254740991"'));
    assert.ok(input.includes('step="1"'));
    for (const value of [0, 1, Number.MAX_SAFE_INTEGER]) {
      const values: UsagePlanningValues = { ...empty, volumes: { [metric.key]: { basis: "OBSERVED", value } } };
      assert.deepEqual(parseUsagePlanningForm(formData(render(values))).values, values);
    }
    const unknownWithValue = formData(html); unknownWithValue.set(`value_${metric.key}`, "0");
    assert.throws(() => parseUsagePlanningForm(unknownWithValue), InvalidUsagePlanningForm);
    const assumedWithoutValue = formData(html); assumedWithoutValue.set(`basis_${metric.key}`, "ASSUMED");
    assert.throws(() => parseUsagePlanningForm(assumedWithoutValue), InvalidUsagePlanningForm);
  }
});

test("saved scope and all ten multiline assumptions remain literal safe text, not examples or markup", () => {
  const values: UsagePlanningValues = { scopeDescription: 'Fictional <team> & "tenant"\nYear one',
    assumptions: Array.from({ length: 10 }, (_, index) => `${index === 0 ? "\n" : ""}Assumption ${index + 1}\n</textarea><script>fictional & 'text'</script>`), volumes: {} };
  const before = structuredClone(values);
  const html = render(values);
  assert.deepEqual(parseUsagePlanningForm(formData(html)).values, values);
  assert.equal(html.includes("<script>"), false); assert.ok(html.includes("&lt;script&gt;"));
  assert.equal([...html.matchAll(/maxlength="500"/gi)].length, 11);
  assert.deepEqual(values, before);
});

test("three saved application profiles preserve their exact usage and adjacent requirements", () => {
  for (const applicationType of ["B2B_SAAS", "PUBLIC_SECTOR_PORTAL", "INTERNAL_WORKFORCE"] as const) {
    const profile = savedRequirementsFixture(); profile.application.type = applicationType;
    const before = structuredClone(profile);
    const values = usagePlanningValues(profile)!;
    assert.deepEqual(parseUsagePlanningForm(formData(render(values))).values, values);
    assert.deepEqual(profile, before);
  }
});

test("each metric has a unique accessible name, associated unit and closed native help", () => {
  const html = render(usagePlanningValues(savedRequirementsFixture())!);
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  for (const idref of [...html.matchAll(/aria-(?:describedby|labelledby)="([^"]+)"/g)].flatMap(match => match[1].split(" "))) assert.ok(ids.includes(idref), idref);
  for (const metric of usageMetrics) {
    assert.ok(html.includes(`for="usage-basis-${metric.key}"`));
    assert.ok(html.includes(`for="usage-value-${metric.key}"`));
    assert.ok(html.includes(`aria-describedby="usage-unit-${metric.key} usage-pair-help"`));
    assert.ok(html.includes(`: ${metric.label}</span>`));
    assert.ok(html.includes(`Unit: ${usageGuidance[metric.key].unitLabel}`));
  }
  assert.equal([...html.matchAll(/<details\b/g)].length, 8);
  assert.equal(/<details[^>]*\bopen\b/.test(html), false);
  assert.equal(html.includes("<script"), false);
});

test("usage examples do not become billing definitions, capacity results, defaults or verified observations", () => {
  const html = render(usagePlanningValues(savedRequirementsFixture())!);
  for (const text of ["record 100 users, not 1,000 sign-ins", "organization count does not establish connection count",
    "one issuance, not 100 requests", "not the monthly total divided into an average",
    "Choosing a basis alone does not change the number", "all-Observed inputs need no invented assumption",
    "Partial inputs can be saved", "uses saved answers, not unsaved edits", "not a universal vendor counting standard",
    "not independently verified evidence", "they do not mean zero cost", "No price is calculated here",
    "No budget limit, affordability finding or free tier is inferred", "Opening explanations does not save anything"])
    assert.ok(html.includes(text), text);
  assert.equal(html.includes("pricingEvaluated"), false);
  assert.equal(html.includes("recommendationReady"), false);
  assert.equal(html.includes("<a "), false);
});
