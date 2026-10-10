import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { resultPage, resultSummary } from "../../../apps/web/tests/fixtures/decision-results.mts";
import { recordingRequest } from "../../../apps/web/src/lib/assessment/decision-recording.ts";
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(new URL("../schemas/", import.meta.url))) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/${file}`, import.meta.url), "utf8")));
for (const [name, fixture] of [["page", resultPage], ["summary", resultSummary]]) {
  const schema = ajv.getSchema(`https://authweave.dev/contracts/assessment-decision-result-${name}.v1.schema.json`);
  test(`owned result ${name} schema is strict and separates discovery from replay/approval`, () => {
    assert(schema(fixture()), ajv.errorsText(schema.errors));
    for (const key of Object.keys(fixture())) { const body = fixture(); delete body[key]; assert.equal(schema(body), false, key); }
    for (const key of ["profile", "auditActor", "sourceBody", "approvedBy"]) assert.equal(schema({ ...fixture(), [key]: {} }), false);
    assert.equal(schema({ ...fixture(), historicalReplayVerified: name !== "summary" }), false);
  });
}
test("result history schema bounds metadata and every exact cursor reference", () => {
  const schema = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result-page.v1.schema.json"), body = resultPage();
  assert.equal(schema({ ...body, items: Array(21).fill(body.items[0]) }), false);
  for (const key of ["resultId", "version", "resultSha256"]) { const bad = structuredClone(body); bad.nextBefore = { ...bad.items[0].reference }; delete bad.nextBefore[key]; assert.equal(schema(bad), false); }
  const bad = structuredClone(body); bad.items[0].assessmentVersion = 9007199254740992; assert.equal(schema(bad), false);
});
test("result summary schema denies stronger source/configuration/compliance authority and out-of-bounds scores", () => {
  const schema = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result-summary.v1.schema.json"), body = resultSummary();
  for (const key of ["externalSourceVerificationPerformed", "configurationVerified", "complianceVerified", "decisionApproved"]) assert.equal(schema({ ...body, [key]: true }), false);
  assert.equal(schema({ ...body, verificationGapCount: 0 }), false);
  const bad = structuredClone(body); bad.candidates[0].score = { lowerBound: -1, upperBound: 101, unknownWeight: 100 }; assert.equal(schema(bad), false);
});
test("bounded recording uses the existing exact request/summary contracts and all nine actual capability dimensions", () => {
  const requestSchema = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result-request.v1.schema.json");
  const summarySchema = ajv.getSchema("https://authweave.dev/contracts/assessment-decision-result-summary.v1.schema.json");
  const dimensionSchema = ajv.getSchema("https://authweave.dev/contracts/decision-result.v1.schema.json").schema.$defs.capability;
  for (const capability of dimensionSchema.enum) {
    const summary = resultSummary(); summary.weights.values[0].capability = capability;
    const request = recordingRequest({ schemaVersion: 1, resultId: summary.item.reference.resultId,
      expectedAssessmentVersion: summary.item.assessmentVersion, catalog: summary.item.catalog, previousResult: null,
      weights: summary.weights, confirmation: "RECORD_DECISION_RESULT" });
    assert.ok(requestSchema(request), ajv.errorsText(requestSchema.errors)); assert.ok(summarySchema(summary), ajv.errorsText(summarySchema.errors));
  }
  const summary = resultSummary(); summary.weights.values[0].capability = "PASSKEYS";
  assert.equal(summarySchema(summary), false);
});
