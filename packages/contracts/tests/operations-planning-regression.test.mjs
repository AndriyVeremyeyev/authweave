import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { operationsRegressionFixture, operationsRegressionHash, operationsRegressionInputs, validateOperationsRegression } from "./helpers/operations-planning-regression-spec.mjs";

const root = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-operations-planning-regression-check.v1.schema.json");
const sourceRoot = new URL("../../../services/core-api/src/main/resources/catalog/", import.meta.url);
const sources = JSON.parse(await readFile(new URL("scoped-impact-scenarios.v1.json", sourceRoot), "utf8"));
const supplements = JSON.parse(await readFile(new URL("scoped-auditability-scenarios.v1.json", sourceRoot), "utf8"));
test("bounded operations matrix compiles valid v6 overlays without replacing frozen requirements", () => {
  const { definitions } = operationsRegressionInputs();
  assert.equal(definitions.length, 140); assert.equal(new Set(definitions.map(d => `${d.scenarioId}/${d.preferenceVariant}/${d.usageVariant}`)).size, 140);
  const validateProfile = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v6.schema.json");
  for (const d of definitions) {
    const source = sources.find(s => s.id === d.scenarioId), profile = structuredClone(source.profile);
    // Explicit v6 supplement is separate from unchanged frozen v5 requirements.
    const supplement = supplements.scenarios.find(s => s.scenarioId === d.scenarioId);
    profile.security.auditabilityRequirements = { selectedCriteria: supplement.selectedCriteria, minimumRetentionDays: supplement.minimumRetentionDays };
    assert.equal(operationsRegressionHash(profile), d.sourceProfileSha256);
    profile.operations = { ...d.inputs, usagePlanning: d.usagePlanning };
    assert.equal(validateProfile(profile), true, ajv.errorsText(validateProfile.errors));
    assert.equal(operationsRegressionHash(profile), d.profileSha256);
    for (const key of Object.keys(source.profile).filter(key => !["operations", "security"].includes(key))) assert.deepEqual(profile[key], source.profile[key]);
    const { auditabilityRequirements, ...security } = profile.security;
    assert.deepEqual(security, source.profile.security); assert.deepEqual(auditabilityRequirements.selectedCriteria, supplement.selectedCriteria);
  }
});
test("body-free operations summary keeps exact inventories and all authority claims false", () => {
  const fixture = operationsRegressionFixture(); assert.equal(validate(fixture), true, ajv.errorsText(validate.errors)); validateOperationsRegression(fixture);
  assert.equal(fixture.scenarioSetSha256, "8e1f536ba8194d889a08c256972051a37d5e671cf5366a284ab29f6ea61909a3");
  assert.equal(fixture.definitionsSha256, "56448797d617cc284a39d8dfbcfa9105bac739a81b591950f0e2ad9bfecaff83");
  assert.equal(fixture.analysisSha256, "a8a644c6a3eec5559c063b9c39f1c73fa4385ecfb77e09ec7554b3c0c9e2fd83");
  assert.deepEqual(fixture.inputResults, { inputsRecorded: 46, needsInformation: 94 }); assert.deepEqual(fixture.usageResults, { inputsRecorded: 56, needsInformation: 84 });
  assert.deepEqual(fixture.hosting, { preferenceAligned: 95, preferenceDiffers: 95, noPreference: 40, preferenceUnknown: 50 });
  assert.deepEqual(fixture.support, { integrationSupportPlanNeeded: 30, operatorSupportPlanNeeded: 30, responsibilityPlanNeeded: 170, supportCapacityUndefined: 50 });
  assert.deepEqual(fixture.budget, { costModelNeeded: 230, budgetScopeUndefined: 50 }); assert.equal(fixture.recordedMetricChecks, 364); assert.equal(fixture.missingInputChecks, 380);
  for (const [key,value] of Object.entries(fixture)) if (value === false) assert.equal(validate({ ...fixture, [key]: true }), false, key);
  for (const key of ["profile", "inputs", "usagePlanning", "scopeDescription", "assumptions", "rows", "actor", "sourceUrl", "workspaceId", "estimatedCost", "winner"]) assert.equal(validate({ ...fixture, [key]: "private" }), false, key);
  for (const key of ["preferenceVariants", "usageVariants", "optionIds", "usageMetrics", "checkedPaths", "deferredBoundaries"]) { const partial = structuredClone(fixture); partial[key].pop(); assert.equal(validate(partial), false, key); }
  for (const value of [-1, true, "140", 0.5, 141]) assert.equal(validate({ ...fixture, inputResults: { ...fixture.inputResults, inputsRecorded: value } }), false);
});
test("independent replay rejects balanced counters, digests, clocks and incomplete semantic reports", () => {
  for (const mutate of [r => { r.inputResults.inputsRecorded--; r.inputResults.needsInformation++; }, r => { r.usageResults.inputsRecorded--; r.usageResults.needsInformation++; },
    r => { r.hosting.preferenceAligned--; r.hosting.preferenceDiffers++; }, r => { r.support.integrationSupportPlanNeeded--; r.support.operatorSupportPlanNeeded++; },
    r => { r.budget.costModelNeeded--; r.budget.budgetScopeUndefined++; }, r => r.recordedMetricChecks--, r => r.missingInputChecks--,
    ...["scenarioSetSha256", "auditabilityScenarioSetSha256", "analysisSha256", "definitionsSha256"].map(key => r => r[key] = "0".repeat(64)),
    r => r.evaluatedAt = "2026-09-12T12:00:01Z"]) {
    const forged = operationsRegressionFixture(); mutate(forged);
    assert.equal(validate(forged), true, ajv.errorsText(validate.errors)); assert.throws(() => validateOperationsRegression(forged));
  }
  const now = operationsRegressionFixture(), later = operationsRegressionFixture("2026-09-12T12:00:01Z");
  assert.equal(now.scenarioSetSha256, later.scenarioSetSha256); assert.equal(now.definitionsSha256, later.definitionsSha256); assert.notEqual(now.analysisSha256, later.analysisSha256);
});
