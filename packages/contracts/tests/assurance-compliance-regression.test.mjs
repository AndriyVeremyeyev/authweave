import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { operationsRegressionHash as hash } from "./helpers/operations-planning-regression-spec.mjs";
import { assuranceRegressionFixture, assuranceRegressionInputs, assuranceRegressionFlags, validateAssuranceRegression } from "./helpers/assurance-compliance-regression-spec.mjs";

const root = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-assurance-compliance-regression-check.v1.schema.json");
test("bounded investigation inputs independently bind every frozen source and v6 supplement", () => {
  const { cases, definitions } = assuranceRegressionInputs(), profileSchema = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v6.schema.json");
  assert.equal(cases.length, 36); assert.equal(new Set(definitions.map(d => `${d.scenarioId}/${d.variant}`)).size, 36);
  for (const { definition, profile } of cases) {
    assert.equal(profileSchema(profile), true, ajv.errorsText(profileSchema.errors)); assert.equal(hash(profile), definition.profileSha256);
    assert.equal(Object.hasOwn(definition, "profile"), false);
    if (definition.variant === "BASE_PROFILE") assert.equal(definition.sourceProfileSha256, definition.profileSha256);
    if (definition.variant === "HUMAN_NONE_IDENTIFIED") { assert.equal(definition.inputs.assuranceExpectation, "HIGH"); assert.equal(definition.inputs.controls.multiFactorAuthentication, "NOT_REQUIRED"); }
    if (definition.variant === "MIXED_PARTIAL_SCOPE") { assert.equal(definition.inputs.complianceScopeStatus, "UNKNOWN"); assert.deepEqual(definition.inputs.complianceTargets, ["OTHER", "SOC_2"]); }
    const base = cases.find(c => c.definition.scenarioId === definition.scenarioId && c.definition.variant === "BASE_PROFILE").profile;
    for (const key of ["operations", "protocols", "provisioning"]) assert.deepEqual(profile[key], base[key]);
    for (const key of ["auditability", "auditabilityRequirements", "dataResidency", "dataResidencyDetails", "browserTokenExposureMinimization"]) assert.deepEqual(profile.security[key], base.security[key]);
  }
});
test("body-free regression is structurally bounded and cannot claim evidence or authority", () => {
  const fixture = assuranceRegressionFixture(); assert.equal(validate(fixture), true, ajv.errorsText(validate.errors)); validateAssuranceRegression(fixture);
  assert.deepEqual(fixture.assurance, { inputClarificationNeeded: 60, evidenceNeeded: 154, notApplied: 38 }); assert.deepEqual(fixture.compliance, { inputClarificationNeeded: 16, evidenceNeeded: 29, notApplied: 0 });
  assert.deepEqual(fixture.humanScopes, { machineOnly: 4, humanScopeRecorded: 28, scopeUnresolved: 4 }); assert.equal(fixture.checkedComplianceItems, 45);
  assert.equal(fixture.scenarioSetSha256, "5d8795219810f2b1d94a2de004bbbebf7f7e08d9a0752524906afa035b575378"); assert.equal(fixture.analysisSha256, "6f353da48419166703db3d96a0dab408b33f768d7d160624f8f3dc4dfcb08ea5");
  for (const key of assuranceRegressionFlags) assert.equal(validate({ ...fixture, [key]: true }), false, key);
  for (const key of ["profile", "inputs", "rows", "workspaceId", "actor", "sourceUrl", "criterion", "winner"]) assert.equal(validate({ ...fixture, [key]: "private" }), false, key);
  for (const key of ["variants", "itemIds", "checkedPaths", "deferredBoundaries"]) { const partial = structuredClone(fixture); partial[key].pop(); assert.equal(validate(partial), false, key); }
  for (const value of [-1, true, "36", 0.5, 253]) assert.equal(validate({ ...fixture, assurance: { ...fixture.assurance, evidenceNeeded: value } }), false);
});
test("independent replay rejects balanced substitutions and a false fresh-clock claim", () => {
  for (const mutate of [r => { r.assurance.inputClarificationNeeded--; r.assurance.evidenceNeeded++; }, r => { r.compliance.inputClarificationNeeded--; r.compliance.evidenceNeeded++; }, r => { r.humanScopes.machineOnly--; r.humanScopes.humanScopeRecorded++; },
    r => r.checkedComplianceItems--, r => r.complianceScopeNotApplied--, ...["scenarioSetSha256", "auditabilityScenarioSetSha256", "analysisSha256", "definitionsSha256"].map(key => r => r[key] = "0".repeat(64)), r => r.evaluatedAt = "2026-09-12T12:00:01Z"]) {
    const forged = assuranceRegressionFixture(); mutate(forged); assert.equal(validate(forged), true, ajv.errorsText(validate.errors)); assert.throws(() => validateAssuranceRegression(forged));
  }
  const a = assuranceRegressionFixture(), b = assuranceRegressionFixture("2026-09-12T12:00:01Z");
  assert.equal(a.scenarioSetSha256, b.scenarioSetSha256); assert.equal(a.definitionsSha256, b.definitionsSha256); assert.deepEqual(a.assurance, b.assurance); assert.notEqual(a.analysisSha256, b.analysisSha256); assert.equal(b.sourceVerificationPerformed, false);
});
