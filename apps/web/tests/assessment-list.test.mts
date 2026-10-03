import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import type { PersonalAssessmentListItem, PersonalAssessmentListPage } from "../src/lib/auth/core-client.ts";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";

const base: PersonalAssessmentListItem = {
  id: "80000000-0000-4000-8000-000000000001", status: "DRAFT", version: 7,
  createdAt: "2026-09-22T12:00:00Z", updatedAt: "2026-10-03T15:00:00Z",
  context: { applicationType: "B2B_SAAS", clients: ["BROWSER"], userPopulations: ["EXTERNAL_CUSTOMERS", "PARTNERS"] },
};
async function render(page: PersonalAssessmentListPage, paginated = false) {
  const { AssessmentList } = await assessmentUiComponents();
  return renderToStaticMarkup(createElement(AssessmentList, { page, paginated }));
}

for (const [applicationType, users, expected] of [
  ["B2B_SAAS", ["EXTERNAL_CUSTOMERS", "PARTNERS"], ["B2B SaaS", "External customers, Partners"]],
  ["PUBLIC_SECTOR_PORTAL", ["CITIZENS"], ["Public-sector portal", "Citizens"]],
  ["INTERNAL_WORKFORCE", ["EMPLOYEES", "CONTRACTORS"], ["Internal workforce application", "Employees, Contractors"]],
] as const) test(`actual list card identifies saved ${applicationType} without writes or inferred readiness`, async () => {
  const item = structuredClone(base);
  item.context = { applicationType, clients: ["BROWSER", "NATIVE_MOBILE"], userPopulations: [...users] };
  const page = { items: [item], nextBeforeId: null };
  const before = structuredClone(page), html = await render(page);
  for (const label of [...expected, "Browser, Native mobile", "Saved version 7", "Draft", "Updated", "Created", "UTC"]) assert.ok(html.includes(label), label);
  assert.ok(html.includes(`href="/assessments/${item.id}"`));
  assert.ok(html.includes("not unsaved edits or comparison results"));
  assert.ok(html.includes("do not measure completeness or recommendation readiness"));
  for (const forbidden of ["<form", "<input", "Winner", "recommended provider", "workspaceId", "profileSchemaVersion"]) assert.equal(html.includes(forbidden), false);
  assert.deepEqual(page, before);
});

test("list distinguishes unknown, Other, M2M-only and unreadable saved contexts", async () => {
  const items: PersonalAssessmentListItem[] = [
    { ...base, context: { applicationType: "UNKNOWN", clients: [], userPopulations: [] } },
    { ...base, id: "80000000-0000-4000-8000-000000000002", context: { applicationType: "OTHER", clients: ["MACHINE_TO_MACHINE"], userPopulations: [] } },
    { ...base, id: "80000000-0000-4000-8000-000000000003", context: null },
  ];
  const html = await render({ items, nextBeforeId: null });
  for (const label of ["Application type not recorded", "Other (needs definition)", "Machine to machine", "Saved context unavailable", "no context is inferred here"]) assert.ok(html.includes(label));
  assert.equal((html.match(/>Not recorded</g) ?? []).length, 3);
  assert.equal((html.match(/Open assessment/g) ?? []).length, 3);
  assert.equal(html.includes("B2B SaaS"), false);
});

test("list keeps bounded older navigation and a route back to the latest page", async () => {
  const html = await render({ items: [base], nextBeforeId: base.id }, true);
  assert.ok(html.includes(`href="/assessments?before=${base.id}"`));
  assert.ok(html.includes('href="/assessments"')); assert.ok(html.includes("Latest assessments"));
  assert.equal(html.includes("?offset="), false);
});

test("empty and exhausted pages explain the next action without inventing a profile", async () => {
  const empty = await render({ items: [], nextBeforeId: null });
  assert.ok(empty.includes("No saved assessments yet")); assert.ok(empty.includes("Return to Account to create a draft"));
  const older = await render({ items: [], nextBeforeId: null }, true);
  assert.ok(older.includes("No older assessments on this page")); assert.ok(older.includes("Return to the latest assessments"));
  assert.equal(older.includes("Older assessments →"), false);
});
