import assert from "node:assert/strict";
import { test } from "node:test";

import { InvalidUsagePlanningForm, parseUsagePlanningForm, usageMetrics, usagePlanningValues,
  withUsagePlanningValues } from "../src/lib/assessment/usage-planning.ts";

const profile = {
  application: { type: "B2B_SAAS" },
  operations: {
    hosting: "UNKNOWN",
    budgetSensitivity: "HIGH",
    usagePlanning: {
      scopeDescription: "Production tenant in the first year",
      assumptions: ["Growth is uncertain"],
      volumes: { MONTHLY_ACTIVE_USERS: { basis: "ASSUMED", value: 1000 } },
    },
  },
};

function form(): URLSearchParams {
  const params = new URLSearchParams({ expectedVersion: "3", scopeDescription: "Production in year two" });
  for (let index = 0; index < 10; index++) params.append("assumption", index === 0 ? "New sales forecast" : "");
  for (const metric of usageMetrics) {
    params.set(`basis_${metric.key}`, "UNKNOWN");
    params.set(`value_${metric.key}`, "");
  }
  params.set("basis_MONTHLY_ACTIVE_USERS", "ASSUMED");
  params.set("value_MONTHLY_ACTIVE_USERS", "2000");
  params.set("basis_ENTERPRISE_SSO_CONNECTIONS", "OBSERVED");
  params.set("value_ENTERPRISE_SSO_CONNECTIONS", "0");
  return params;
}

test("usage form records explicit zero but leaves absent metrics unknown", () => {
  const { expectedVersion, values } = parseUsagePlanningForm(form());
  assert.equal(expectedVersion, 3);
  assert.deepEqual(values.volumes.ENTERPRISE_SSO_CONNECTIONS, { basis: "OBSERVED", value: 0 });
  assert.equal(values.volumes.MONTHLY_M2M_TOKEN_ISSUANCES, undefined);
  const changed = withUsagePlanningValues(profile, values);
  assert.deepEqual((changed.operations as Record<string, unknown>).usagePlanning, values);
  assert.equal((changed.operations as Record<string, unknown>).hosting, "UNKNOWN");
  assert.deepEqual(changed.application, profile.application);
  assert.deepEqual(profile.operations.usagePlanning.volumes,
    { MONTHLY_ACTIVE_USERS: { basis: "ASSUMED", value: 1000 } });
});

test("usage form preserves multiline assumptions and supports an explicit clear", () => {
  const params = form();
  const entries = params.getAll("assumption");
  entries[0] = "Seasonal peak\nmay double";
  params.delete("assumption");
  for (const entry of entries) params.append("assumption", entry);
  assert.deepEqual(parseUsagePlanningForm(params).values.assumptions, ["Seasonal peak\nmay double"]);
  params.delete("assumption");
  for (let index = 0; index < 10; index++) params.append("assumption", "");
  for (const metric of usageMetrics) {
    params.set(`basis_${metric.key}`, "UNKNOWN");
    params.set(`value_${metric.key}`, "");
  }
  params.set("scopeDescription", "   ");
  assert.deepEqual(parseUsagePlanningForm(params).values,
    { scopeDescription: "", assumptions: [], volumes: {} });
});

test("usage form rejects forged keys, duplicates, invalid pairs and unsafe quantities", () => {
  const changes: Array<[string, string]> = [
    ["expectedVersion", "03"], ["expectedVersion", "9007199254740992"],
    ["scopeDescription", "x".repeat(501)],
    ["basis_MONTHLY_ACTIVE_USERS", "UNKNOWN"],
    ["basis_MONTHLY_ACTIVE_USERS", "FAKE"],
    ["value_MONTHLY_ACTIVE_USERS", "-1"], ["value_MONTHLY_ACTIVE_USERS", "1.5"],
    ["value_MONTHLY_ACTIVE_USERS", "01"],
    ["value_MONTHLY_ACTIVE_USERS", "9007199254740992"],
  ];
  for (const [key, value] of changes) {
    const invalid = form();
    invalid.set(key, value);
    assert.throws(() => parseUsagePlanningForm(invalid), InvalidUsagePlanningForm);
  }
  for (const [key, value] of [["expectedVersion", "3"], ["assumption", "extra"],
    ["basis_MONTHLY_ACTIVE_USERS", "OBSERVED"], ["forged", "true"]]) {
    const invalid = form();
    invalid.append(key, value);
    assert.throws(() => parseUsagePlanningForm(invalid), InvalidUsagePlanningForm);
  }
  const missing = form();
  missing.delete("value_MONTHLY_ACTIVE_USERS");
  assert.throws(() => parseUsagePlanningForm(missing), InvalidUsagePlanningForm);
  const duplicateAssumptions = form();
  const entries = duplicateAssumptions.getAll("assumption");
  entries[1] = entries[0];
  duplicateAssumptions.delete("assumption");
  for (const entry of entries) duplicateAssumptions.append("assumption", entry);
  assert.throws(() => parseUsagePlanningForm(duplicateAssumptions), InvalidUsagePlanningForm);
  const blankAssumption = form();
  const blankEntries = blankAssumption.getAll("assumption");
  blankEntries[1] = "  ";
  blankAssumption.delete("assumption");
  for (const entry of blankEntries) blankAssumption.append("assumption", entry);
  assert.throws(() => parseUsagePlanningForm(blankAssumption), InvalidUsagePlanningForm);
});

test("usage editor fails closed on malformed or future Core planning shape", () => {
  assert.deepEqual(usagePlanningValues(profile), profile.operations.usagePlanning);
  assert.equal(usagePlanningValues({ ...profile, operations: { usagePlanning: {
    scopeDescription: "", assumptions: [], volumes: { OTHER: { basis: "ASSUMED", value: 1 } },
  } } }), null);
  assert.equal(usagePlanningValues({ ...profile, operations: { usagePlanning: {
    scopeDescription: "", assumptions: [], volumes: {}, newCoreField: "must not disappear",
  } } }), null);
});
