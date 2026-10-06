import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { operationsPlanningExpectation, validateOperationsPlanning, operationsMetrics } from "./helpers/operations-planning-spec.mjs";

const root = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/operations-planning-preflight.v1.schema.json");
const at = "2026-09-12T12:00:00Z";
const saved = { workspaceId: "60000000-0000-4000-8000-000000000001", id: "80000000-0000-4000-8000-000000000001", version: 7, profile: { operations: {
  hosting: "MANAGED", deploymentTarget: "AZURE", identityExpertise: "LIMITED", budgetSensitivity: "HIGH",
  usagePlanning: { scopeDescription: "Synthetic pilot", assumptions: [], volumes: Object.fromEntries(operationsMetrics.map(metric => [metric, { basis: "OBSERVED", value: 0 }])) } } } };
test("Operations planning keeps all 384 preference contexts unverified and retains both alternatives", () => {
  let count = 0;
  for (const hosting of ["MANAGED", "SELF_HOSTED", "NO_PREFERENCE", "UNKNOWN"]) for (const deploymentTarget of ["AZURE", "AWS", "GOOGLE_CLOUD", "ON_PREMISES", "MULTI_CLOUD", "UNDECIDED"])
    for (const identityExpertise of ["LIMITED", "MODERATE", "ADVANCED", "UNKNOWN"]) for (const budgetSensitivity of ["HIGH", "MODERATE", "LOW", "UNKNOWN"]) {
      const context = structuredClone(saved); Object.assign(context.profile.operations, { hosting, deploymentTarget, identityExpertise, budgetSensitivity });
      const result = operationsPlanningExpectation(context, at); assert.equal(validate(result), true, ajv.errorsText(validate.errors)); validateOperationsPlanning(result, context, at);
      assert.equal(result.options.length, 2); assert.equal(result.pricingEvaluated, false); assert.equal(result.operationalReadinessVerified, false);
      assert.equal(result.status, result.missingPaths.length ? "NEEDS_INFORMATION" : "INPUTS_RECORDED"); count++;
    }
  assert.equal(count, 384);
});
test("Operations contract rejects authority, fabricated prices, partial alternatives and extra input fields", () => {
  const fixture = operationsPlanningExpectation(saved, at); assert.equal(validate(fixture), true);
  for (const [key, value] of Object.entries(fixture)) if (value === false) assert.equal(validate({ ...fixture, [key]: true }), false, key);
  for (const key of ["estimatedCost", "winner", "score", "eligible", "budgetLimit", "sourceUrl", "scopeDescription"]) assert.equal(validate({ ...fixture, [key]: "forged" }), false);
  for (const mutate of [r => r.options.pop(), r => r.options.reverse(), r => r.options[1] = r.options[0], r => r.inputs.region = "any", r => r.usageInputs.recordedMetrics.push("UNKNOWN_METRIC")]) {
    const forged = structuredClone(fixture); mutate(forged); assert.equal(validate(forged), false);
  }
});
test("Independent saved-input replay rejects shape-valid preference, usage, support, budget and binding substitutions", () => {
  const fixture = operationsPlanningExpectation(saved, at);
  for (const mutate of [r => r.inputs.hosting = "SELF_HOSTED", r => r.inputs.deploymentTarget = "AWS", r => r.assessmentVersion++, r => r.workspaceId = "60000000-0000-4000-8000-000000000002",
    r => r.status = "NEEDS_INFORMATION", r => r.usageInputs.recordedMetrics.pop(), r => r.options[0].hostingAlignment = "PREFERENCE_DIFFERS",
    r => r.options[1].supportPlanning = "RESPONSIBILITY_PLAN_NEEDED", r => r.options[0].budgetPlanning = "BUDGET_SCOPE_UNDEFINED", r => r.options[0].advantages[0] = "Managed is always cheaper",
    r => r.missingPaths.push("operations.hosting"), r => r.evaluatedAt = "2026-09-12T12:00:01Z"]) {
    const forged = structuredClone(fixture); mutate(forged); assert.equal(validate(forged), true, ajv.errorsText(validate.errors)); assert.throws(() => validateOperationsPlanning(forged, saved, at));
  }
  const partial = structuredClone(saved); partial.profile.operations.usagePlanning = { scopeDescription: " ", assumptions: [], volumes: { MONTHLY_ACTIVE_USERS: { basis: "ASSUMED", value: 0 } } };
  const result = operationsPlanningExpectation(partial, at); assert.equal(validate(result), true); assert.equal(result.status, "NEEDS_INFORMATION");
  assert.deepEqual(result.usageInputs.recordedMetrics, ["MONTHLY_ACTIVE_USERS"]); assert.ok(result.missingPaths.includes("operations.usagePlanning.assumptions"));
  assert.ok(!result.missingPaths.includes("operations.usagePlanning.volumes.MONTHLY_ACTIVE_USERS"));
  for (const [scope, missing] of [["", true], ["\u2003", true], ["\u00a0", false], ["\ufeff", false]]) {
    const context = structuredClone(saved); context.profile.operations.usagePlanning.scopeDescription = scope;
    const replay = operationsPlanningExpectation(context, at); assert.equal(replay.usageInputs.missingPaths.includes("operations.usagePlanning.scopeDescription"), missing);
  }
});
