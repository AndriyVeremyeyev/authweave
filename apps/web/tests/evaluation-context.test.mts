import assert from "node:assert/strict";
import { test } from "node:test";

import { evaluationContextValues, assuranceExpectationSaveMatches, InvalidEvaluationContextForm, parseEvaluationContextForm,
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
    dataResidencyDetails: { allowedCountries: ["US"], dataCategories: ["USER_PROFILES"] },
  },
  operations: { source: "keep" },
};

const valid = new URLSearchParams({
  expectedVersion: "3", applicationType: "B2B_SAAS", tenancy: "MULTI_TENANT_ORGANIZATIONS",
  membership: "MULTIPLE_ORGANIZATIONS_PER_USER", dataResidency: "REQUIRED",
  allowedCountries: "CA, US",
  browserTokenExposureMinimization: "REQUIRED",
  phishingResistance: "NOT_REQUIRED", nonExportableKeys: "NOT_REQUIRED",
  stepUpAuthentication: "NOT_REQUIRED", complianceScopeStatus: "NONE_IDENTIFIED",
});

test("all assurance planning labels round-trip without deriving or changing independent controls", () => {
  const source = { ...profile, security: { ...profile.security, assurance: "UNKNOWN", multiFactorAuthentication: "REQUIRED" } };
  const before = structuredClone(source);
  for (const expectation of ["BASELINE", "ELEVATED", "HIGH", "UNKNOWN"] as const) {
    const form = new URLSearchParams(valid); form.set("assuranceExpectation", expectation);
    const values = parseEvaluationContextForm(form).values;
    assert.equal(values.assuranceExpectation, expectation);
    const changed = withEvaluationContextValues(source, values);
    assert.equal(evaluationContextValues(changed)?.assuranceExpectation, expectation);
    assert.equal((changed.security as Record<string, unknown>).multiFactorAuthentication, "REQUIRED");
    assert.deepEqual((changed.security as Record<string, unknown>).authenticationControls,
      { phishingResistance: "NOT_REQUIRED", nonExportableKeys: "NOT_REQUIRED", stepUpAuthentication: "NOT_REQUIRED" });
    assert.deepEqual(changed.protocols, source.protocols); assert.deepEqual(changed.operations, source.operations);
    assert.equal(assuranceExpectationSaveMatches(changed, structuredClone(changed)), true);
    assert.equal(assuranceExpectationSaveMatches(changed, { ...changed,
      security: { ...(changed.security as object), assurance: expectation === "HIGH" ? "BASELINE" : "HIGH" } }), false);
    assert.equal(assuranceExpectationSaveMatches(changed, profile), false);
  }
  assert.deepEqual(source, before);
});

test("older context forms preserve existing assurance and absence is not a guessed default", () => {
  assert.equal(Object.hasOwn(parseEvaluationContextForm(valid).values, "assuranceExpectation"), false);
  assert.equal(Object.hasOwn(evaluationContextValues(profile)!, "assuranceExpectation"), false);
  assert.equal(Object.hasOwn(withEvaluationContextValues(profile, parseEvaluationContextForm(valid).values).security as object, "assurance"), false);
  for (const assurance of ["BASELINE", "ELEVATED", "HIGH", "UNKNOWN"]) {
    const source = { ...profile, security: { ...profile.security, assurance } };
    assert.equal((withEvaluationContextValues(source, parseEvaluationContextForm(valid).values).security as Record<string, unknown>).assurance, assurance);
    assert.equal(assuranceExpectationSaveMatches(source, structuredClone(source)), true);
  }
  assert.equal(assuranceExpectationSaveMatches(profile, profile), true);
  assert.equal(assuranceExpectationSaveMatches(profile, { ...profile, security: { ...profile.security, assurance: "UNKNOWN" } }), false);
});

test("assurance rejects blank, duplicated, unrecognized and malformed values without correction", () => {
  for (const raw of ["", "high", "HIGH ", "AAL3", "synthetic-sensitive-invalid", "UNKNOWN,BASELINE"]) {
    const form = new URLSearchParams(valid); form.set("assuranceExpectation", raw);
    assert.throws(() => parseEvaluationContextForm(form), InvalidEvaluationContextForm);
  }
  const duplicate = new URLSearchParams(valid);
  duplicate.append("assuranceExpectation", "HIGH"); duplicate.append("assuranceExpectation", "HIGH");
  assert.throws(() => parseEvaluationContextForm(duplicate), InvalidEvaluationContextForm);
  for (const assurance of [undefined, null, 1, [], {}, "", "AAL3"]) {
    const source = { ...profile, security: { ...profile.security, assurance } };
    assert.equal(evaluationContextValues(source), null);
    assert.throws(() => withEvaluationContextValues(source, parseEvaluationContextForm(valid).values));
    assert.equal(assuranceExpectationSaveMatches(profile, source), false);
  }
  assert.throws(() => withEvaluationContextValues(profile, { ...parseEvaluationContextForm(valid).values,
    assuranceExpectation: "invalid" as "HIGH" }), InvalidEvaluationContextForm);
});
valid.append("clients", "BROWSER");
valid.append("selectedPopulations", "EXTERNAL_CUSTOMERS");
valid.append("selectedPopulations", "PARTNERS");
valid.append("selectedDataCategories", "USER_PROFILES");
valid.append("selectedDataCategories", "BACKUPS");

test("context form changes only its selected fields and preserves unrelated v5 profile details", () => {
  const before = evaluationContextValues(profile);
  assert.equal(before?.applicationType, "UNKNOWN");
  const { expectedVersion, values } = parseEvaluationContextForm(valid);
  assert.equal(expectedVersion, 3);
  const changed = withEvaluationContextValues(profile, values);
  assert.equal((changed.application as Record<string, unknown>).type, "B2B_SAAS");
  assert.deepEqual((changed.audience as Record<string, unknown>).populations,
    ["EXTERNAL_CUSTOMERS", "PARTNERS"]);
  assert.equal((changed.security as Record<string, unknown>).dataResidency, "REQUIRED");
  assert.equal((changed.security as Record<string, unknown>).browserTokenExposureMinimization, "REQUIRED");
  assert.deepEqual((changed.security as Record<string, unknown>).complianceTargets, []);
  assert.deepEqual(changed.protocols, profile.protocols);
  assert.deepEqual(changed.operations, profile.operations);
  assert.deepEqual((changed.security as Record<string, unknown>).dataResidencyDetails,
    { allowedCountries: ["CA", "US"], dataCategories: ["USER_PROFILES", "BACKUPS"] });
  assert.deepEqual(profile.security.dataResidencyDetails,
    { allowedCountries: ["US"], dataCategories: ["USER_PROFILES"] });
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
    ["allowedCountries", "us, CA"], ["allowedCountries", "US, US"],
    ["allowedCountries", "US,"], ["allowedCountries", "USA"],
    ["expectedVersion", "03"], ["expectedVersion", "9007199254740992"],
  ]) {
    const invalid = new URLSearchParams(valid);
    invalid.set(key, value);
    assert.throws(() => parseEvaluationContextForm(invalid), InvalidEvaluationContextForm);
  }
  for (const [key, value] of [
    ["clients", "BROWSER"], ["selectedPopulations", "PARTNERS"], ["tenancy", "UNKNOWN"],
    ["selectedDataCategories", "BACKUPS"], ["selectedDataCategories", "OTHER"],
    ["allowedCountries", "US"],
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
  const missingCountries = new URLSearchParams(valid);
  missingCountries.delete("allowedCountries");
  assert.throws(() => parseEvaluationContextForm(missingCountries), InvalidEvaluationContextForm);
  const duplicateTargets = new URLSearchParams(valid);
  duplicateTargets.append("selectedComplianceTargets", "SOC_2");
  duplicateTargets.append("selectedComplianceTargets", "SOC_2");
  assert.throws(() => parseEvaluationContextForm(duplicateTargets), InvalidEvaluationContextForm);
  assert.equal(evaluationContextValues({ ...profile, security: { ...profile.security,
    authenticationControls: { phishingResistance: null } } }), null);
  assert.equal(evaluationContextValues({ ...profile, security: { ...profile.security,
    dataResidencyDetails: { allowedCountries: ["US", "US"], dataCategories: [] } } }), null);
  const unknownDetails = new URLSearchParams(valid);
  unknownDetails.set("allowedCountries", " ");
  unknownDetails.delete("selectedDataCategories");
  assert.deepEqual(parseEvaluationContextForm(unknownDetails).values.allowedCountries, []);
  assert.deepEqual(parseEvaluationContextForm(unknownDetails).values.selectedDataCategories, []);
});
