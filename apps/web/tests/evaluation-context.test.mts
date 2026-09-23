import assert from "node:assert/strict";
import { test } from "node:test";

import { evaluationContextValues, InvalidEvaluationContextForm, parseEvaluationContextForm,
  withEvaluationContextValues } from "../src/lib/assessment/evaluation-context.ts";

const profile = {
  application: { type: "UNKNOWN", clients: [] },
  audience: { populations: [], tenancy: "UNKNOWN", membership: "UNKNOWN" },
  protocols: { federation: { OIDC: "PREFERRED" } },
  security: {
    dataResidency: "UNKNOWN", browserTokenExposureMinimization: "UNKNOWN",
    complianceScopeStatus: "UNKNOWN", complianceTargets: [],
    authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN",
      stepUpAuthentication: "UNKNOWN" },
    dataResidencyDetails: { allowedCountries: ["US"] },
  },
  operations: { source: "keep" },
};

const valid = new URLSearchParams({
  expectedVersion: "3", applicationType: "B2B_SAAS", tenancy: "MULTI_TENANT_ORGANIZATIONS",
  membership: "MULTIPLE_ORGANIZATIONS_PER_USER", dataResidency: "NOT_REQUIRED",
  browserTokenExposureMinimization: "REQUIRED",
  phishingResistance: "NOT_REQUIRED", nonExportableKeys: "NOT_REQUIRED",
  stepUpAuthentication: "NOT_REQUIRED", complianceScopeStatus: "NONE_IDENTIFIED",
});
valid.append("clients", "BROWSER");
valid.append("selectedPopulations", "EXTERNAL_CUSTOMERS");
valid.append("selectedPopulations", "PARTNERS");

test("context form changes only its selected fields and preserves unrelated v5 profile details", () => {
  const before = evaluationContextValues(profile);
  assert.equal(before?.applicationType, "UNKNOWN");
  const { expectedVersion, values } = parseEvaluationContextForm(valid);
  assert.equal(expectedVersion, 3);
  const changed = withEvaluationContextValues(profile, values);
  assert.equal((changed.application as Record<string, unknown>).type, "B2B_SAAS");
  assert.deepEqual((changed.audience as Record<string, unknown>).populations,
    ["EXTERNAL_CUSTOMERS", "PARTNERS"]);
  assert.equal((changed.security as Record<string, unknown>).dataResidency, "NOT_REQUIRED");
  assert.equal((changed.security as Record<string, unknown>).browserTokenExposureMinimization, "REQUIRED");
  assert.deepEqual((changed.security as Record<string, unknown>).complianceTargets, []);
  assert.deepEqual(changed.protocols, profile.protocols);
  assert.deepEqual(changed.operations, profile.operations);
  assert.deepEqual((changed.security as Record<string, unknown>).dataResidencyDetails,
    profile.security.dataResidencyDetails);
  assert.equal(profile.application.type, "UNKNOWN");
  const targeted = new URLSearchParams(valid);
  targeted.set("complianceScopeStatus", "TARGETS_IDENTIFIED");
  targeted.append("selectedComplianceTargets", "SOC_2");
  assert.deepEqual((withEvaluationContextValues(profile,
    parseEvaluationContextForm(targeted).values).security as Record<string, unknown>).complianceTargets,
  ["SOC_2"]);
});

test("context form rejects unknown, duplicate or malformed choices", () => {
  for (const [key, value] of [
    ["applicationType", "PERSONAL_APP"], ["dataResidency", "MAYBE"],
    ["browserTokenExposureMinimization", "MAYBE"],
    ["expectedVersion", "03"], ["expectedVersion", "9007199254740992"],
  ]) {
    const invalid = new URLSearchParams(valid);
    invalid.set(key, value);
    assert.throws(() => parseEvaluationContextForm(invalid), InvalidEvaluationContextForm);
  }
  for (const [key, value] of [
    ["clients", "BROWSER"], ["selectedPopulations", "PARTNERS"], ["tenancy", "UNKNOWN"],
    ["forged", "true"],
  ]) {
    const invalid = new URLSearchParams(valid);
    invalid.append(key, value);
    assert.throws(() => parseEvaluationContextForm(invalid), InvalidEvaluationContextForm);
  }
  const missing = new URLSearchParams(valid);
  missing.delete("complianceScopeStatus");
  assert.throws(() => parseEvaluationContextForm(missing), InvalidEvaluationContextForm);
  const missingTokenCriterion = new URLSearchParams(valid);
  missingTokenCriterion.delete("browserTokenExposureMinimization");
  assert.throws(() => parseEvaluationContextForm(missingTokenCriterion), InvalidEvaluationContextForm);
  const duplicateTargets = new URLSearchParams(valid);
  duplicateTargets.append("selectedComplianceTargets", "SOC_2");
  duplicateTargets.append("selectedComplianceTargets", "SOC_2");
  assert.throws(() => parseEvaluationContextForm(duplicateTargets), InvalidEvaluationContextForm);
  assert.equal(evaluationContextValues({ ...profile, security: { ...profile.security,
    authenticationControls: { phishingResistance: null } } }), null);
});
