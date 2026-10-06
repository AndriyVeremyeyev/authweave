import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { configurationRegressionFixture, validateConfigurationRegression } from "./helpers/architecture-configuration-regression-spec.mjs";

const root = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-architecture-configuration-regression-check.v1.schema.json");
test("Body-free regression has exact inventories and cannot assert authority or expose profiles", () => {
  const fixture = configurationRegressionFixture(); assert.equal(validate(fixture), true, ajv.errorsText(validate.errors)); validateConfigurationRegression(fixture);
  assert.deepEqual(fixture.outcomes, { conditionallySatisfied: 292, conditionallyNotSatisfied: 18, unknown: 986, notApplicable: 708 });
  assert.deepEqual(fixture.results, { conditionallyMatches: 22, conditionallyDoesNotMatch: 18, needsInformation: 120, notApplicable: 92 });
  assert.equal(fixture.checkedCases, 252); assert.equal(fixture.checkedSettings, 2004); assert.equal(fixture.savedInputNeedsInformation, 96);
  for (const [key, value] of Object.entries(fixture)) if (value === false) assert.equal(validate({ ...fixture, [key]: true }), false, key);
  for (const key of ["profile", "settings", "actor", "sourceUrl", "optionId", "workspaceId", "rows"]) assert.equal(validate({ ...fixture, [key]: "private" }), false, key);
  for (const key of ["reasons", "exercisedSettings", "checkedPaths", "deferredBoundaries"]) { const partial = structuredClone(fixture); partial[key].pop(); assert.equal(validate(partial), false, key); }
  for (const value of [-1, true, "252", 0.5, 2005]) assert.equal(validate({ ...fixture, selectedCases: value }), false);
});
test("Independent wire replay rejects shape-valid hash, clock, scope and balanced-count substitutions", () => {
  const mutations = [r => r.outcomes.conditionallySatisfied--, r => { r.outcomes.conditionallySatisfied--; r.outcomes.conditionallyNotSatisfied++; },
    r => { r.results.conditionallyMatches--; r.results.needsInformation++; }, r => { r.selectedCases--; r.unknownClientCases++; },
    r => r.savedInputNeedsInformation--, r => { r.reasons[0].checks--; r.reasons[1].checks++; }, r => r.scenarioSetSha256 = "0".repeat(64),
    r => r.definitionsSha256 = "0".repeat(64), r => r.analysisSha256 = "0".repeat(64), r => r.evaluatedAt = "2026-09-12T12:00:01Z"];
  for (const mutate of mutations) { const forged = configurationRegressionFixture(); mutate(forged); assert.equal(validate(forged), true, ajv.errorsText(validate.errors)); assert.throws(() => validateConfigurationRegression(forged)); }
});
