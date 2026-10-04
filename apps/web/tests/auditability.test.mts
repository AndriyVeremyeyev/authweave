import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";
import { auditCriteria, auditabilityValues, InvalidAuditabilityForm, maximumRetentionDays,
  parseAuditabilityForm, withAuditabilityValues } from "../src/lib/assessment/auditability.ts";

const profile = { application: { type: "B2B_SAAS" }, operations: { hosting: "MANAGED" },
  security: { assurance: "UNKNOWN", auditability: "REQUIRED", auditabilityRequirements: {
    selectedCriteria: ["AUTHENTICATION_FAILURE_EVENTS", "AUDIT_LOG_RETENTION"], minimumRetentionDays: 90 } } };
const unknown = { criticality: "UNKNOWN" as const, selectedCriteria: [], minimumRetentionDays: null };
const form = () => new URLSearchParams({ expectedVersion: "2", criticality: "REQUIRED",
  selectedCriteria: "AUDIT_LOG_RETENTION", minimumRetentionDays: "90" });

test("auditability editor criteria and duration bounds match the v6 contract", async () => {
  const schema = JSON.parse(await readFile(new URL("../../../packages/contracts/schemas/application-identity-profile.v6.schema.json", import.meta.url), "utf8"));
  const requirements = schema.$defs.auditabilityRequirements;
  assert.deepEqual(auditCriteria.map(criterion => criterion.key), requirements.properties.selectedCriteria.items.enum);
  assert.equal(auditCriteria.length, requirements.properties.selectedCriteria.maxItems);
  assert.equal(maximumRetentionDays, requirements.properties.minimumRetentionDays.maximum);
  assert.equal(requirements.properties.minimumRetentionDays.minimum, 1);
});

test("auditability form canonicalizes explicit scope without inferring defaults", () => {
  const params = form();
  params.append("selectedCriteria", "AUTHENTICATION_FAILURE_EVENTS");
  assert.deepEqual(parseAuditabilityForm(params), { expectedVersion: 2, values: {
    criticality: "REQUIRED", selectedCriteria: ["AUTHENTICATION_FAILURE_EVENTS", "AUDIT_LOG_RETENTION"], minimumRetentionDays: 90 } });
  params.delete("selectedCriteria"); params.delete("minimumRetentionDays");
  assert.deepEqual(parseAuditabilityForm(params).values, { ...unknown, criticality: "REQUIRED" });
  params.set("criticality", "NOT_REQUIRED");
  assert.equal(parseAuditabilityForm(params).values.selectedCriteria.length, 0);
  for (const days of [1, maximumRetentionDays]) {
    const boundary = form(); boundary.set("minimumRetentionDays", String(days));
    assert.equal(parseAuditabilityForm(boundary).values.minimumRetentionDays, days);
  }
});

test("auditability patch changes only its two security fields and supports explicit clear", () => {
  const before = structuredClone(profile);
  const values = parseAuditabilityForm(form()).values;
  const updated = withAuditabilityValues(profile, values);
  assert.deepEqual(updated.application, profile.application);
  assert.deepEqual(updated.operations, profile.operations);
  assert.equal((updated.security as Record<string, unknown>).assurance, "UNKNOWN");
  assert.deepEqual(auditabilityValues(withAuditabilityValues(updated, unknown)), unknown);
  assert.deepEqual(profile, before);
});

test("auditability parser rejects foreign keys, duplicates, unsafe versions and malformed durations", () => {
  for (const [key, value] of [["workspaceId", "forged"], ["__proto__", "x"], ["configurationVerified", "true"],
    ["criticality", "REQUIRED"], ["selectedCriteria", "AUDIT_LOG_RETENTION"], ["minimumRetentionDays", "90"], ["expectedVersion", "2"]]) {
    const params = form(); params.append(key, value);
    assert.throws(() => parseAuditabilityForm(params), InvalidAuditabilityForm);
  }
  for (const [key, value] of [["criticality", "VERIFIED"], ["selectedCriteria", "toString"],
    ["expectedVersion", "02"], ["expectedVersion", "-1"], ["expectedVersion", "9007199254740992"],
    ...["0", "36501", "9007199254740992", "1.5", "-1", "090", "9e1", " 90", ""].map(value => ["minimumRetentionDays", value])]) {
    const params = form(); params.set(key, value);
    assert.throws(() => parseAuditabilityForm(params), InvalidAuditabilityForm, `${key}=${value}`);
  }
  for (const key of ["expectedVersion", "criticality", "minimumRetentionDays"]) {
    const params = form(); params.delete(key);
    assert.throws(() => parseAuditabilityForm(params), InvalidAuditabilityForm);
  }
  const orphan = form(); orphan.delete("selectedCriteria");
  assert.throws(() => parseAuditabilityForm(orphan), InvalidAuditabilityForm);
});

test("auditability reads fail closed on missing, future or contradictory Core shape", () => {
  assert.deepEqual(auditabilityValues(profile), { criticality: "REQUIRED",
    selectedCriteria: ["AUTHENTICATION_FAILURE_EVENTS", "AUDIT_LOG_RETENTION"], minimumRetentionDays: 90 });
  assert.equal(auditabilityValues({ security: { auditability: "REQUIRED" } }), null);
  for (const requirements of [null, {}, { selectedCriteria: [], minimumRetentionDays: 90 },
    { selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: null },
    { selectedCriteria: ["AUTHENTICATION_SUCCESS_EVENTS", "AUTHENTICATION_SUCCESS_EVENTS"], minimumRetentionDays: null },
    { selectedCriteria: ["OTHER"], minimumRetentionDays: null },
    { selectedCriteria: [], minimumRetentionDays: null, futureField: true }]) {
    const malformed = { ...profile, security: { ...profile.security, auditabilityRequirements: requirements } };
    assert.equal(auditabilityValues(malformed), null);
    assert.throws(() => withAuditabilityValues(malformed, unknown), InvalidAuditabilityForm);
  }
});

test("auditability form renders labelled criteria, disabled unknown duration and explicit limits", async () => {
  const component = await assessmentUiComponents();
  const render = (values: ReturnType<typeof auditabilityValues>) => renderToStaticMarkup(createElement(component.AuditabilityEditor,
    { assessmentId: "80000000-0000-4000-8000-000000000001", version: 2, values }));
  const empty = render(unknown);
  assert.equal((empty.match(/type="checkbox"/g) ?? []).length, 6);
  assert.equal((empty.match(/checked=""/g) ?? []).length, 0);
  assert.ok(empty.includes('value="UNKNOWN" selected=""'));
  assert.match(empty.match(/<input[^>]*id="audit-retention-days"[^>]*>/)![0], /disabled=""/);
  assert.ok(empty.includes("Current provider previews still defer auditability"));
  assert.ok(empty.includes("uncheck all six criteria and save"));
  const recorded = render(auditabilityValues(profile));
  assert.equal((recorded.match(/checked=""/g) ?? []).length, 2);
  const durationInput = recorded.match(/<input[^>]*id="audit-retention-days"[^>]*>/)![0];
  assert.ok(durationInput.includes('required=""')); assert.ok(durationInput.includes('value="90"'));
  assert.equal(recorded.includes('disabled=""'), false);
  for (const criterion of auditCriteria) {
    assert.ok(recorded.includes(`for="audit-${criterion.key}"`));
    assert.ok(recorded.includes(`aria-describedby="audit-help-${criterion.key}"`));
  }
});
