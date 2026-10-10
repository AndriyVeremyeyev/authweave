import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { sensitivityFixture } from "../../../apps/web/tests/fixtures/decision-sensitivity.mts";
import { sensitivityFromCore } from "../../../apps/web/src/lib/assessment/decision-sensitivity.ts";
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-sensitivity.v1.schema.json");
const request = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-sensitivity-request.v1.schema.json");
const guard = f => sensitivityFromCore(f.value, f.baseline.summary.workspaceId, f.baseline.summary.assessmentId, f.input, f.baseline);
test("sensitivity schema and BFF preserve fixed original inputs, rank reversal and ties", () => {
  for (const weight of [20, 50, 70]) { const f = sensitivityFixture(undefined, weight);
    assert(request(f.input), ajv.errorsText(request.errors)); assert(validate(f.value), ajv.errorsText(validate.errors)); assert.deepEqual(guard(f), f.value); }
});
test("comparison contracts reject authority, new pins, raw inputs and missing properties", () => {
  const f = sensitivityFixture();
  for (const key of Object.keys(f.value)) { const v = structuredClone(f.value); delete v[key]; assert.equal(validate(v), false, key); }
  for (const key of ["profile", "catalog", "evaluatedAt", "resultId", "confirmation"]) assert.equal(request({ ...f.input, [key]: {} }), false, key);
  assert.equal(validate({ ...f.value, writesPerformed: true }), false);
  assert.equal(validate({ ...f.value, after: { ...f.value.after, historicalReplayVerified: true } }), false);
});
test("schema-valid evidence, contribution and rank drift fail the bounded BFF guard", () => {
  for (const change of [f => f.value.after.candidates[0].hardChecks.configuration = "Changed scope",
    f => f.value.after.candidates[0].hardChecks.findings[0].evidence.observedAt = "2026-01-01T00:00:00Z",
    f => f.value.after.candidates[0].score.contributions[0].reasonCode = "CHANGED_FACT",
    f => f.value.after.rankGroups[0].rank = 2]) {
    const f = sensitivityFixture(); f.value = structuredClone(f.value); change(f);
    assert(validate(f.value), ajv.errorsText(validate.errors)); assert.throws(() => guard(f));
  }
});
