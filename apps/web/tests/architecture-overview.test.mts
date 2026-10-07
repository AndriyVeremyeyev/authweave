import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import type { ArchitecturePatternPreflightSummary } from "../src/lib/auth/core-client.ts";
import { architectureOverview, architectureOutcomeText, architectureStatusText } from "../src/lib/assessment/architecture-presentation.ts";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";
import { prerequisiteAssessmentId, prerequisiteFixture } from "./fixtures/architecture-prerequisites.mts";

// Display projection only, not a substitute Core evaluation or authenticated browser flow.
const fixture = (clients = ["BROWSER"]) => prerequisiteFixture(undefined, clients).preflight as ArchitecturePatternPreflightSummary;

test("overview keeps Core order, exact outcomes and token locations without ranking or input mutation", () => {
  const preview = fixture(), before = structuredClone(preview), rows = architectureOverview(preview)!;
  assert.deepEqual(rows.map(r => r.displayName), preview.patterns.map(p => p.displayName));
  assert.deepEqual(rows.map(r => r.tokenLocation), ["Application server", "Application server", "Browser code", "Native app", "Workload"]);
  assert.deepEqual(rows.map(r => r.status), preview.patterns.map(p => p.status));
  assert.deepEqual(rows.map(r => r.clientScope), ["Selected client type", "Selected client type", "Selected client type", "Client type not selected", "Client type not selected"]);
  for (const [index, row] of rows.entries()) {
    assert.equal(row.anchor, `architecture-pattern-${index}`);
    assert.deepEqual(row.checks.map(c => c.outcome), preview.patterns[index].checks.map(c => architectureOutcomeText[c.outcome]));
  }
  assert.deepEqual(Object.keys(rows[0]), ["anchor", "displayName", "clientType", "clientScope", "tokenLocation", "status", "checks"]);
  rows[0].checks[0].outcome = "Changed display"; assert.deepEqual(preview, before);
});

test("empty clients stay unrecorded, never five inapplicable patterns or an inferred browser default", () => {
  const preview = fixture([]), rows = architectureOverview(preview)!;
  assert.ok(rows.every(r => r.clientScope === "Client types not recorded"));
  assert.ok(rows.every(r => r.status === "NEEDS_INFORMATION"));
  assert.ok(rows.every(r => r.checks.every(c => c.outcome === "Needs clarification")));
  assert.deepEqual(preview.selectedClients, []);
});

test("mixed clients retain independent native/workload scope and Not applied remains not a pass", () => {
  const rows = architectureOverview(fixture(["BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"]))!;
  assert.ok(rows.every(r => r.clientScope === "Selected client type"));
  assert.deepEqual(rows.slice(3).map(r => r.checks[1].outcome), ["Not applied — not a pass", "Not applied — not a pass"]);
  assert.deepEqual(rows.slice(3).map(r => r.status), ["MATCHES_CHECKED_REQUIREMENTS", "MATCHES_CHECKED_REQUIREMENTS"]);
  assert.equal(rows[2].status, "NEEDS_INFORMATION");
});

test("exact profile paths join the two checks independently of array order; patterns are never sorted", () => {
  const preview = fixture(); preview.patterns.reverse(); preview.patterns.forEach(p => p.checks.reverse());
  const before = structuredClone(preview), rows = architectureOverview(preview)!;
  assert.deepEqual(rows.map(r => r.displayName), preview.patterns.map(p => p.displayName));
  assert.equal(rows[0].tokenLocation, "Workload");
  assert.deepEqual(rows[2].checks, [{ label: "Client selection", outcome: "Matches this check only" },
    { label: "Browser token minimization", outcome: "Needs clarification" }]);
  assert.deepEqual(preview, before);
});

test("incomplete, duplicate, foreign and unknown inventories disable the overview without fabricating checks", () => {
  for (const mutate of [p => p.patterns.pop(), p => p.patterns.push(p.patterns[0]),
    p => p.patterns[0].patternId = p.patterns[1].patternId, p => p.patterns[0].patternId = "__proto__" as never,
    p => p.patterns[0].checks.pop(), p => p.patterns[0].checks[0].profilePath = p.patterns[0].checks[1].profilePath,
    p => p.patterns[0].checks[0].profilePath = "application.clients.extra", p => p.patterns[0].checks[0].outcome = "PASS_NEW" as never,
    p => p.patterns[0].tokenHandling = "__proto__" as never, p => p.patterns[0].status = "constructor" as never,
    p => p.patterns[0].clientType = "BROWSER_NEW" as never] as ((p: ArchitecturePatternPreflightSummary) => void)[]) {
    const preview = fixture(); mutate(preview); assert.equal(architectureOverview(preview), null);
  }
});

test("actual SSR table has scoped headers, inert native links, explicit limits and no form or JavaScript requirement", async () => {
  const components = await assessmentUiComponents(), preview = fixture(), before = structuredClone(preview);
  const html = renderToStaticMarkup(createElement(components.ArchitectureOverview, { preview }));
  for (const text of ["Compare the saved-input boundaries", "not a shortlist or ranking", "not the temporary declarations",
    "not a measurement of your app", "does not mean no browser session or credential", "not verified by the browser criterion",
    "Saved version 2", "not an architecture recommendation", "Client selection", "Browser token minimization"]) assert.ok(html.includes(text), text);
  assert.equal((html.match(/scope="row"/g) ?? []).length, 5); assert.equal((html.match(/scope="col"/g) ?? []).length, 4);
  assert.equal((html.match(/<td /g) ?? []).length, 15); assert.ok(html.includes('<caption class="sr-only">'));
  assert.ok(html.includes('tabindex="0"')); assert.ok(html.includes("min-w-[688px]")); assert.ok(html.includes("sticky left-0"));
  for (let index = 0; index < 5; index++) assert.ok(html.includes(`href="#architecture-pattern-${index}"`));
  assert.equal(html.includes("<form"), false); assert.equal(html.includes("<input"), false); assert.equal(html.includes("https://"), false);
  assert.deepEqual(preview, before);
});

test("Core text is escaped and raw names or identifiers never supply an executable or foreign link", async () => {
  const components = await assessmentUiComponents(), preview = fixture();
  preview.patterns[0].displayName = '<script>literal()</script>&"name"';
  const html = renderToStaticMarkup(createElement(components.ArchitectureOverview, { preview }));
  assert.ok(html.includes("&lt;script&gt;literal()&lt;/script&gt;")); assert.equal(html.includes("<script>literal"), false);
  assert.ok(html.includes('href="#architecture-pattern-0"')); assert.equal(html.includes('href="#BFF_SESSION"'), false);
  preview.patterns[0].patternId = "javascript:literal()" as never;
  const unavailable = renderToStaticMarkup(createElement(components.ArchitectureOverview, { preview }));
  assert.ok(unavailable.includes("overview is unavailable")); assert.equal(unavailable.includes("<table"), false);
  assert.equal(unavailable.includes("javascript:"), false);
});

test("full Architecture retains detailed cards/forms and exact keyboard-focusable anchor targets", async () => {
  const components = await assessmentUiComponents(), preview = fixture(), before = structuredClone(preview);
  const html = renderToStaticMarkup(createElement(components.ArchitecturePatterns, { preview, assessmentId: prerequisiteAssessmentId }));
  const cards = html.slice(html.indexOf('aria-label="Detailed architecture patterns in Core order"'));
  for (const [index, pattern] of preview.patterns.entries()) {
    assert.ok(cards.includes(`id="architecture-pattern-${index}" tabindex="-1"`));
    assert.ok(cards.includes(`${pattern.displayName}</h3>`)); assert.ok(html.includes(architectureStatusText[pattern.status]));
  }
  assert.equal((cards.match(/<select /g) ?? []).length, 51);
  assert.equal((cards.match(/Try concrete settings/g) ?? []).length, 5);
  assert.deepEqual(preview, before);
});
