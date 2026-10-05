import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { auditCriteria, auditabilityValues, InvalidAuditabilityForm, maximumRetentionDays,
  parseAuditabilityForm, type AuditabilityValues } from "../src/lib/assessment/auditability.ts";
import { auditConceptReferences, auditCriticalityGuidance, auditGuidance, auditRetentionGuidance } from "../src/lib/assessment/audit-guidance.ts";
import { criticalities } from "../src/lib/assessment/capabilities.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

const id = "4640bbac-c20f-476a-a4dc-23efad5ff14f";
const { AuditabilityEditor } = await assessmentUiComponents();
function render(values: AuditabilityValues, version = 5) {
  return renderToStaticMarkup(createElement(AuditabilityEditor, { assessmentId: id, version, values }));
}

// Read successful native controls from the real rendered editor, including disabled retention.
function formData(html: string) {
  const params = new URLSearchParams();
  for (const [tag] of html.matchAll(/<input\b[^>]*>/g)) {
    if (tag.includes('disabled=""')) continue;
    if (tag.includes('type="checkbox"') && !tag.includes('checked=""')) continue;
    params.append(tag.match(/\bname="([^"]+)"/)![1], tag.match(/\bvalue="([^"]*)"/)![1]);
  }
  const select = html.match(/<select\b[^>]*name="criticality"[^>]*>(.*?)<\/select>/)![1];
  const selected = [...select.matchAll(/<option\b[^>]*>/g)].filter(([tag]) => tag.includes('selected=""'));
  assert.equal(selected.length, 1);
  params.append("criticality", selected[0][0].match(/\bvalue="([^"]*)"/)![1]);
  return params;
}

test("audit guidance covers exactly six unchanged criteria, five levels and two concept references", () => {
  assert.deepEqual(Object.keys(auditGuidance), auditCriteria.map(criterion => criterion.key));
  assert.deepEqual(Object.keys(auditCriticalityGuidance), [...criticalities]);
  assert.deepEqual(Object.values(auditGuidance).map(guide => guide.group), ["events", "events", "events", "events", "records", "records"]);
  assert.equal(auditConceptReferences.length, 2);
  for (const reference of auditConceptReferences) {
    const url = new URL(reference.href);
    assert.equal(url.protocol, "https:");
    assert.ok(["csrc.nist.gov", "cheatsheetseries.owasp.org"].includes(url.hostname));
    assert.equal(url.username + url.password + url.search, "");
  }
});

test("every criterion subset and criticality round-trips through the actual scoped form unchanged", () => {
  for (let subset = 0; subset < 2 ** auditCriteria.length; subset++) for (const criticality of criticalities) {
    const selectedCriteria = auditCriteria.filter((_, index) => subset & (1 << index)).map(criterion => criterion.key);
    const values: AuditabilityValues = { criticality, selectedCriteria,
      minimumRetentionDays: selectedCriteria.includes("AUDIT_LOG_RETENTION") ? 90 : null };
    const before = structuredClone(values);
    const html = render(values);
    assert.ok(html.includes(`action="/api/assessments/${id}/auditability" method="post"`));
    assert.deepEqual(parseAuditabilityForm(formData(html)), { expectedVersion: 5, values });
    assert.equal([...html.matchAll(/<input\b/g)].length, 8);
    assert.equal([...html.matchAll(/<select\b/g)].length, 1);
    assert.equal([...html.matchAll(/<button\b[^>]*type="submit"/g)].length, 1);
    assert.equal([...html.matchAll(/<button\b[^>]*type="button"/g)].length, 2);
    assert.deepEqual(values, before);
  }
});

test("retention stays conditional and bounded; illustrative durations are never defaults", () => {
  const unknown: AuditabilityValues = { criticality: "UNKNOWN", selectedCriteria: [], minimumRetentionDays: null };
  const html = render(unknown, 0);
  const params = formData(html);
  assert.equal(params.has("minimumRetentionDays"), false);
  assert.deepEqual(parseAuditabilityForm(params), { expectedVersion: 0, values: unknown });
  const input = html.match(/<input[^>]*id="audit-retention-days"[^>]*>/)![0];
  assert.ok(input.includes('disabled=""')); assert.ok(input.includes('value=""'));
  assert.equal(input.includes('placeholder='), false);
  for (const days of [1, 90, maximumRetentionDays]) {
    const values: AuditabilityValues = { ...unknown, criticality: "REQUIRED", selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: days };
    const rendered = render(values);
    assert.deepEqual(parseAuditabilityForm(formData(rendered)).values, values);
    const enabled = rendered.match(/<input[^>]*id="audit-retention-days"[^>]*>/)![0];
    assert.equal(enabled.includes('disabled=""'), false);
    assert.ok(enabled.includes('required=""')); assert.ok(enabled.includes('min="1"'));
    assert.ok(enabled.includes(`max="${maximumRetentionDays}"`)); assert.ok(enabled.includes('step="1"'));
  }
  const missing = formData(render({ ...unknown, selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: null }));
  assert.throws(() => parseAuditabilityForm(missing), InvalidAuditabilityForm);
});

test("three saved application profiles render their audit scope without changing adjacent inputs", () => {
  for (const applicationType of ["B2B_SAAS", "PUBLIC_SECTOR_PORTAL", "INTERNAL_WORKFORCE"] as const) {
    const profile = savedRequirementsFixture(); profile.application.type = applicationType;
    const before = structuredClone(profile);
    const values = auditabilityValues(profile)!;
    assert.deepEqual(parseAuditabilityForm(formData(render(values))).values, values);
    assert.deepEqual(profile, before);
  }
});

test("audit controls have unique labelled descriptions, closed help and safe external links", () => {
  const html = render(auditabilityValues(savedRequirementsFixture())!);
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  for (const idref of [...html.matchAll(/aria-(?:describedby|labelledby)="([^"]+)"/g)].flatMap(match => match[1].split(" "))) assert.ok(ids.includes(idref), idref);
  for (const criterion of auditCriteria) {
    assert.ok(html.includes(`for="audit-${criterion.key}"`));
    assert.ok(html.includes(`aria-describedby="audit-help-${criterion.key}"`));
    assert.ok(html.includes(`: ${criterion.label}</span>`));
  }
  assert.equal([...html.matchAll(/<details\b/g)].length, 9);
  assert.equal(/<details[^>]*\bopen\b/.test(html), false);
  assert.equal([...html.matchAll(/rel="noopener noreferrer"/g)].length, 2);
  assert.equal(html.includes("<script"), false);
});

test("audit examples preserve the separation of requested capabilities, evidence and deployed logging", () => {
  const html = render(auditabilityValues(savedRequirementsFixture())!);
  for (const text of ["No selection means unresolved scope", "does not turn logging on",
    "does not interpret it as an instruction to disable logging", "does not score it",
    "not proof of what the user later did", "Provisioning logs do not imply SCIM support",
    "not verified delivery", "external sink duration cannot supply its documented minimum",
    "Missing documented duration remains unknown", "project validation bound, not a standard",
    "when you save", "not a NIST baseline", "Current provider previews still defer auditability",
    "Do not use passwords or access tokens as log examples", "Opening explanations does not save anything"])
    assert.ok(html.includes(text), text);
  assert.ok(auditRetentionGuidance.example.includes("does not fill the input"));
  assert.equal(html.includes("configurationVerified"), false);
  assert.equal(html.includes("recommendationReady"), false);
});
