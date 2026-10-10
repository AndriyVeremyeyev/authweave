import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { resultAdvice, rankedAdvice } from "../../../apps/web/tests/fixtures/decision-advice.mts";
import { resultAdviceFromCore } from "../../../apps/web/src/lib/assessment/decision-advice.ts";
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result-advice.v1.schema.json");
const guard = r => resultAdviceFromCore(r, r.summary.workspaceId, r.summary.assessmentId, r.summary.item.reference);
test("advice contract preserves allowlisted historical fields and conditional evidence without authority", () => {
  for (const r of [resultAdvice(), rankedAdvice()]) { assert(validate(r), ajv.errorsText(validate.errors)); assert.deepEqual(guard(r), r); }
});
test("advice schema rejects missing fields, raw declarations and false authority", () => {
  for (const key of Object.keys(resultAdvice())) { const r = resultAdvice(); delete r[key]; assert.equal(validate(r), false, key); }
  for (const key of ["profile", "evaluationProfile", "request", "auditActor", "sourceAuthorityVerified"]) assert.equal(validate({ ...resultAdvice(), [key]: {} }), false);
  for (const key of ["externalSourceVerificationPerformed", "configurationVerified", "complianceVerified", "decisionApproved"]) {
    const r = resultAdvice(); r.summary[key] = true; assert.equal(validate(r), false, key);
  }
  const r = resultAdvice(); r.limitations[0].declaredValue = "personal details"; assert.equal(validate(r), false);
});
test("schema-valid points, rank and identity substitutions still fail BFF transport consistency", () => {
  for (const change of [r => r.candidates[0].score.contributions[0].earnedPoints = 99,
    r => r.rankGroups[0].optionIds = ["foreign"], r => r.candidates[0].hardChecks.product = "Different product"]) {
    const r = rankedAdvice(); change(r); assert(validate(r), ajv.errorsText(validate.errors)); assert.throws(() => guard(r));
  }
});
test("schema-valid unsafe URI schemes or forged prerequisite readiness fail the BFF", () => {
  for (const change of [r => r.candidates[0].hardChecks.findings[0].evidence.sourceUrl = "javascript:alert(1)",
    r => r.architecture.patterns[0].choice.references.push("data:text/html,bad")]) {
    const r = resultAdvice(); change(r); assert(validate(r), ajv.errorsText(validate.errors)); assert.throws(() => guard(r));
  }
  const r = resultAdvice(); r.architecture.patterns[0].prerequisites.recommendationReady = true;
  assert.equal(validate(r), false); assert.throws(() => guard(r));
});
