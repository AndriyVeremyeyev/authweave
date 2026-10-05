import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";
import { prerequisiteAssessmentId, prerequisiteFixture } from "./fixtures/architecture-prerequisites.mts";

test("architecture cards explain saved inputs, preserve all five alternatives and distinguish partial checks from temporary design", async () => {
  const components = await assessmentUiComponents(), preview = prerequisiteFixture().preflight;
  const before = structuredClone(preview);
  const html = renderToStaticMarkup(createElement(components.ArchitecturePatterns, { assessmentId: prerequisiteAssessmentId, preview }));
  for (const text of ["Saved version 2", "Saved client types", "Browser", "Saved browser token minimization", "Required",
    "Review saved Context", "display order is not a ranking", "Mixed client types may need several patterns",
    "Matches the applied checks only", "Needs more information", "Client type not selected", "Not applied — not a pass",
    "Application server", "Browser code", "Native app", "Workload", "Advantages", "Trade-offs", "Prerequisites to verify",
    "No current what-if result", "Technical check details", "not an architecture recommendation"]) assert.ok(html.includes(text), text);
  assert.equal((html.match(/<h3 /g) ?? []).length, 5);
  assert.equal((html.match(/<select /g) ?? []).length, 51);
  assert.equal((html.match(/value="UNKNOWN" selected=""/g) ?? []).length, 51);
  assert.equal((html.match(/Try concrete settings/g) ?? []).length, 5);
  for (const pattern of preview.patterns) {
    assert.ok(html.includes(pattern.displayName));
    for (const check of pattern.checks) {
      assert.ok(html.includes(check.explanation)); assert.ok(html.includes(check.profilePath)); assert.ok(html.includes(check.reasonCode));
    }
    for (const text of [...pattern.advantages, ...pattern.tradeoffs, ...pattern.prerequisites, ...pattern.references]) assert.ok(html.includes(text));
  }
  assert.deepEqual(preview, before);
  assert.ok(html.indexOf("BFF_SESSION</h3>") < html.indexOf("SPA_CODE_PKCE</h3>"));
  assert.ok(html.includes('type="button"')); assert.equal(html.includes('type="submit">Save'), false);
});

test("empty saved clients stay unknown rather than becoming five inapplicable patterns", async () => {
  const components = await assessmentUiComponents(), preview = prerequisiteFixture(undefined, []).preflight;
  const html = renderToStaticMarkup(createElement(components.ArchitecturePatterns, { assessmentId: prerequisiteAssessmentId, preview }));
  assert.ok(html.includes("Not recorded")); assert.ok(html.includes("Select at least one client type"));
  assert.equal((html.match(/Needs more information/g) ?? []).length, 5);
  assert.equal((html.match(/Client types are not recorded/g) ?? []).length, 5);
  assert.equal(html.includes("Client type not selected"), false);
  assert.equal(html.includes("Matches the applied checks only"), false);
});

test("mixed clients do not turn an unapplied browser criterion into a workload token-storage check", async () => {
  const components = await assessmentUiComponents(), preview = prerequisiteFixture(undefined, ["BROWSER", "MACHINE_TO_MACHINE"]).preflight;
  const html = renderToStaticMarkup(createElement(components.ArchitecturePatterns, { assessmentId: prerequisiteAssessmentId, preview }));
  assert.ok(html.includes("Browser, Machine to machine"));
  assert.ok(html.includes("Native and workload token storage are not assessed by the browser criterion"));
  assert.equal((html.match(/This client type is not selected\. These declarations/g) ?? []).length, 1);
  const workload = html.slice(html.indexOf("M2M_CLIENT_CREDENTIALS</h3>"));
  assert.ok(workload.includes("Not applied — not a pass")); assert.ok(workload.includes("BROWSER_CRITERION_NOT_APPLICABLE"));
});

for (const requirement of ["UNKNOWN", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN"] as const) {
  test(`saved ${requirement} token-minimization input keeps its meaning without a score or recommendation`, async () => {
    const components = await assessmentUiComponents(), preview = prerequisiteFixture().preflight;
    // Display projection only: test the recorded input label, not a substitute Core evaluation.
    preview.browserTokenExposureRequirement = requirement;
    const html = renderToStaticMarkup(createElement(components.ArchitecturePatterns, { assessmentId: prerequisiteAssessmentId, preview }));
    const label = { UNKNOWN: "Unknown / not recorded", PREFERRED: "Preferred", NOT_REQUIRED: "Not required", FORBIDDEN: "Forbidden" }[requirement];
    assert.ok(html.includes(`Saved browser token minimization</dt><dd class="mt-1 text-sm">${label}`));
    assert.ok(html.includes("Preferred is not scored")); assert.ok(html.includes("not a blanket ban on browser tokens"));
    assert.ok(html.includes("does not evaluate these remaining areas or override the saved-input result"));
  });
}
