import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { planningFamilies, planningCoverageFlags, planningInputRoutes, planningRegressionBindings, planningVerificationGaps, planningManifestSha256 } from "./helpers/profile-planning-coverage-spec.mjs";

const root = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const id = "https://authweave.dev/contracts/catalog-profile-planning-coverage.v1.schema.json", schema = ajv.getSchema(id).schema;
test("independent planning manifest covers all 34 source inputs without rewriting historical coverage", () => {
  const routes = planningInputRoutes(); assert.equal(routes.length, 34);
  assert.deepEqual(routes.find(r => r.profilePath === "application.clients").planningRegressions, ["ARCHITECTURE_CONFIGURATION", "ASSURANCE_COMPLIANCE"]);
  assert.deepEqual(routes.find(r => r.profilePath === "security.auditability").planningRegressions, []);
  assert.deepEqual(routes.find(r => r.profilePath === "security.assurance").planningRegressions, ["ASSURANCE_COMPLIANCE"]);
  assert.equal(planningManifestSha256(), "808c2414bd4617cda4edf6003ed3e12c3d87bfb3ab42c1daf40f4912bb82d08a");
  assert.equal(schema.properties.manifestSha256.const, planningManifestSha256());
});
test("complete response graph is strict, incomplete and explicitly non-authoritative", () => {
  assert.equal(schema.additionalProperties, false); assert.deepEqual(new Set(schema.required), new Set(Object.keys(schema.properties)));
  for (const flag of planningCoverageFlags) assert.equal(schema.properties[flag].const, false, flag);
  assert.equal(schema.properties.status.const, "INCOMPLETE"); assert.equal(schema.properties.structuralCoverage.properties.status.const, "INCOMPLETE");
  assert.equal(schema.properties.dimensions.minItems, 136); assert.equal(schema.properties.regressions.minItems, 4);
  assert.equal(schema.properties.planningAddedToDeferredDimensions.const, 36); assert.equal(schema.properties.unroutedDeferredDimensions.const, 0);
});
test("regression bindings retain original schema, source, policy and declared case counts", () => {
  const validate = ajv.getSchema(`${id}#/$defs/binding`), bindings = planningRegressionBindings();
  assert.deepEqual(bindings.map(b => b.family), planningFamilies); assert.deepEqual(bindings.map(b => b.profileSchemaVersion), [5, 6, 6, 6]);
  assert.deepEqual(bindings.map(b => b.checkedCases), [252, 2016, 140, 36]);
  for (const binding of bindings) {
    assert.equal(validate(binding), true, ajv.errorsText(validate.errors));
    for (const key of Object.keys(binding)) { const partial = structuredClone(binding); delete partial[key]; assert.equal(validate(partial), false, key); }
    for (const [key, value] of [["policyVersion", "future"], ["profileSchemaVersion", 4], ["checkedCases", binding.checkedCases - 1], ["scenarioSetSha256", "0".repeat(64)], ["sourceUrl", "private"], ["configurationVerified", true]]) assert.equal(validate({ ...binding, [key]: value }), false, key);
  }
});
test("input routing cannot cross families or silently claim synthetic auditability as observed coverage", () => {
  const validate = ajv.getSchema(`${id}#/$defs/dimension`);
  for (const route of planningInputRoutes()) {
    const row = { scenarioId: "b2b-saas-scoped", profilePath: route.profilePath, structuralState: "DEFERRED_DIMENSION", planningRegressions: route.planningRegressions };
    assert.equal(validate(row), true, ajv.errorsText(validate.errors));
    const wrong = route.planningRegressions.includes("OPERATIONS_PLANNING") ? ["ASSURANCE_COMPLIANCE"] : ["OPERATIONS_PLANNING"];
    assert.equal(validate({ ...row, planningRegressions: wrong }), false);
    assert.equal(validate({ ...row, structuralState: "VERIFIED" }), false); assert.equal(validate({ ...row, candidateChangesEvaluated: true }), false);
  }
});
test("all 22 additional planning verification gaps remain exact and ordered", () => {
  const validate = ajv.getSchema(`${id}#/properties/verificationGaps`), gaps = planningVerificationGaps();
  assert.equal(gaps.length, 22); assert.equal(validate(gaps), true, ajv.errorsText(validate.errors));
  assert.equal(validate(gaps.slice(1)), false); assert.equal(validate([...gaps].reverse()), false);
  assert.equal(validate([...gaps.slice(0, -1), gaps[0]]), false);
});
