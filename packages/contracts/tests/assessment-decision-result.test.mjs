import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { resultPolicy } from "./helpers/assessment-decision-result-spec.mjs";
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
const root = "https://authweave.dev/contracts/";
const validate = ajv.getSchema(root + "assessment-decision-result-request.v1.schema.json");
const id = "00000000-0000-4000-8000-000000000001", sha = "1".repeat(64);
const request = () => ({ schemaVersion: 1, resultId: id, expectedAssessmentVersion: 1,
  catalog: { snapshotId: id, catalogVersion: "fictional-catalog", snapshotSha256: sha }, previousResult: null,
  weights: { mode: "NONE", values: [] }, confirmation: "RECORD_DECISION_RESULT" });
test("owned result request excludes transported calculations, authority and implicit input pins", () => {
  assert(validate(request()), ajv.errorsText(validate.errors));
  for (const key of Object.keys(request())) { const r = request(); delete r[key]; assert.equal(validate(r), false, key); }
  for (const key of ["profile", "policy", "evaluatedAt", "decision", "verdicts", "approvalGranted", "actor"]) assert.equal(validate({ ...request(), [key]: {} }), false);
});
test("re-evaluation requires an exact previous result and its matching explicit confirmation", () => {
  const r = request(); r.previousResult = { resultId: id, version: 1, resultSha256: sha }; assert.equal(validate(r), false);
  r.confirmation = "REEVALUATE_DECISION_RESULT"; assert(validate(r));
  for (const key of Object.keys(r.previousResult)) { const s = structuredClone(r); delete s.previousResult[key]; assert.equal(validate(s), false, key); }
  for (const value of [-1, 0, 1.5, 9007199254740992, "1"]) { const s = structuredClone(r); s.previousResult.version = value; assert.equal(validate(s), false); }
  r.previousResult = null; assert.equal(validate(r), false);
});
test("explicit capability weights and profile versions are bounded; no recurring-cost dimensions", () => {
  for (const v of [-1, 1.5, 9007199254740992, "1"]) assert.equal(validate({ ...request(), expectedAssessmentVersion: v }), false);
  const r = request(); r.weights = { mode: "EXPLICIT", values: [{ capability: "SAML", weight: 100 }] }; assert(validate(r));
  for (const v of [-1, 0, 100.5, 101, "100"]) { const s = structuredClone(r); s.weights.values[0].weight = v; assert.equal(validate(s), false); }
  r.weights.values[0].capability = "PRICE"; assert.equal(validate(r), false);
});
test("historical result projection/policy and denied authority have distinct strict contracts", () => {
  const p = ajv.getSchema(root + "assessment-decision-result.v1.schema.json#/$defs/policy"); assert(p(resultPolicy));
  for (const key of Object.keys(resultPolicy)) { const r = structuredClone(resultPolicy); delete r[key]; assert.equal(p(r), false, key); }
  assert.equal(p({ ...resultPolicy, profileProjectionVersion: "silent-migration" }), false);
  const problem = ajv.getSchema(root + "assessment-decision-result-problem.v1.schema.json");
  const r = { type: "urn:authweave:problem:assessment-decision-unsupported-policy", title: "Unavailable", status: 409,
    detail: "Historical policy is unsupported.", instance: "/api/v6/workspaces/test/assessments/test/decision-results/test", code: "assessment-decision-unsupported-policy" };
  assert(problem(r)); assert.equal(problem({ ...r, sourceUrl: "https://secret.invalid" }), false);
});
