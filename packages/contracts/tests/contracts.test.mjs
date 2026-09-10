import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

import {
  assertResultMatchesOperation,
  operationErrorCodes,
} from "../src/operation-result-guards.mjs";

const contractsRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const schemasRoot = path.join(contractsRoot, "schemas");
const fixturesRoot = path.join(contractsRoot, "tests", "fixtures");

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

for (const schemaFile of await readdir(schemasRoot)) {
  if (schemaFile.endsWith(".schema.json")) {
    ajv.addSchema(await readJson(path.join(schemasRoot, schemaFile)));
  }
}

const requestSchemaId =
  "https://authweave.dev/contracts/requirements-extraction-request.v1.schema.json";
const resultSchemaId =
  "https://authweave.dev/contracts/requirements-extraction-result.v1.schema.json";
const problemSchemaId =
  "https://authweave.dev/contracts/operation-problem.v1.schema.json";
const assessmentResponseSchemaId =
  "https://authweave.dev/contracts/assessment-response.v1.schema.json";
const updateAssessmentProfileSchemaId =
  "https://authweave.dev/contracts/update-assessment-profile-request.v1.schema.json";
const coreProblemSchemaId =
  "https://authweave.dev/contracts/core-problem.v1.schema.json";

const validateRequest = ajv.getSchema(requestSchemaId);
const validateResult = ajv.getSchema(resultSchemaId);
const validateProblem = ajv.getSchema(problemSchemaId);
const validateAssessmentResponse = ajv.getSchema(assessmentResponseSchemaId);
const validateUpdateAssessmentProfile = ajv.getSchema(updateAssessmentProfileSchemaId);
const validateCoreProblem = ajv.getSchema(coreProblemSchemaId);

const validRequest = await readJson(
  path.join(fixturesRoot, "requirements-extraction-request.valid.json"),
);
const validResult = await readJson(
  path.join(fixturesRoot, "requirements-extraction-result.valid.json"),
);
const validProblem = await readJson(
  path.join(fixturesRoot, "operation-problem.valid.json"),
);
const validAssessmentResponse = await readJson(
  path.join(fixturesRoot, "assessment-response.valid.json"),
);
const validCoreProblem = await readJson(
  path.join(fixturesRoot, "core-problem.valid.json"),
);

function validationMessage(validate) {
  return ajv.errorsText(validate.errors, { separator: "\n" });
}

test("valid fixtures satisfy their JSON Schemas", () => {
  assert.equal(validateRequest(validRequest), true, validationMessage(validateRequest));
  assert.equal(validateResult(validResult), true, validationMessage(validateResult));
  assert.equal(validateProblem(validProblem), true, validationMessage(validateProblem));
});

test("Core API fixtures satisfy their JSON Schemas", () => {
  const updateRequest = {
    expectedVersion: validAssessmentResponse.version,
    profile: validAssessmentResponse.profile,
  };

  assert.equal(
    validateAssessmentResponse(validAssessmentResponse),
    true,
    validationMessage(validateAssessmentResponse),
  );
  assert.equal(
    validateUpdateAssessmentProfile(updateRequest),
    true,
    validationMessage(validateUpdateAssessmentProfile),
  );
  assert.equal(
    validateCoreProblem(validCoreProblem),
    true,
    validationMessage(validateCoreProblem),
  );
});

test("AI proposals and profiles share the canonical criticality vocabulary", () => {
  for (const criticality of ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN"]) {
    const result = structuredClone(validResult);
    result.proposal.requirements[0].criticalityProposal.value = criticality;
    assert.equal(validateResult(result), true, validationMessage(validateResult));

    const request = { expectedVersion: 0, profile: structuredClone(validAssessmentResponse.profile) };
    request.profile.protocols.socialLogin = criticality;
    assert.equal(validateUpdateAssessmentProfile(request), true, validationMessage(validateUpdateAssessmentProfile));
  }
});

test("legacy AI criticality labels cannot silently enter a canonical profile", () => {
  for (const value of ["hard-requirement", "important", "preference"]) {
    const result = structuredClone(validResult);
    result.proposal.requirements[0].criticalityProposal.value = value;
    assert.equal(validateResult(result), false);
  }
});

test("Core API contracts reject stale-shape and unknown-field inputs", () => {
  const staleUpdate = {
    expectedVersion: -1,
    profile: validAssessmentResponse.profile,
  };
  const responseWithUnknownField = {
    ...validAssessmentResponse,
    internalNote: "must not cross the API boundary",
  };

  assert.equal(validateUpdateAssessmentProfile(staleUpdate), false);
  assert.equal(validateAssessmentResponse(responseWithUnknownField), false);
});

test("history contracts accept snapshots and minimal events but reject profile values in events", () => {
  const revisions = ajv.getSchema("https://authweave.dev/contracts/assessment-revision-page.v1.schema.json");
  const events = ajv.getSchema("https://authweave.dev/contracts/assessment-event-page.v1.schema.json");
  const base = { workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id, version: 0 };
  const revision = {
    ...base, status: "DRAFT", profileSchemaVersion: 1, profile: validAssessmentResponse.profile,
    origin: "CREATED", recordedAt: validAssessmentResponse.createdAt,
  };
  const event = {
    ...base, id: "22222222-2222-4222-8222-222222222222", previousVersion: null,
    action: "assessment.created", actorType: "SERVICE", actorId: "core-api",
    correlationId: "33333333-3333-4333-8333-333333333333", outcome: "SUCCEEDED",
    changedSections: ["application"], occurredAt: validAssessmentResponse.createdAt,
  };
  assert.equal(revisions({ items: [revision], nextAfterVersion: 0 }), true, validationMessage(revisions));
  assert.equal(events({ items: [event], nextAfterVersion: null }), true, validationMessage(events));
  assert.equal(revisions({ items: [{ ...revision, origin: "BASELINE", version: 7 }], nextAfterVersion: null }), true);
  for (const validate of [revisions, events]) {
    assert.equal(validate({ items: [], nextAfterVersion: null }), true);
    assert.equal(validate({ items: [], nextAfterVersion: -1 }), false);
    assert.equal(validate({ items: [], nextAfterVersion: 9007199254740992 }), false);
    assert.equal(validate({ items: [] }), false);
  }
  assert.equal(events({ items: [{ ...event, profile: revision.profile }], nextAfterVersion: null }), false);
  assert.equal(events({ items: [{ ...event, changedSections: ["application", "application"] }], nextAfterVersion: null }), false);
  assert.equal(events({ items: [{ ...event, changedSections: ["sensitive-raw-value"] }], nextAfterVersion: null }), false);
  assert.equal(events({ items: [{ ...event, outcome: "FAILED" }], nextAfterVersion: null }), false);
  assert.equal(revisions({ items: [{ ...revision, profileSchemaVersion: 2 }], nextAfterVersion: null }), false);
  assert.equal(revisions({ items: [{ ...revision, origin: "INVENTED" }], nextAfterVersion: null }), false);
});

test("request rejects unknown fields", () => {
  const request = structuredClone(validRequest);
  request.unknown = true;

  assert.equal(validateRequest(request), false);
});

test("request rejects unsupported contract versions", () => {
  const request = structuredClone(validRequest);
  request.context.contractVersion = "2.0";

  assert.equal(validateRequest(request), false);
});

test("result must remain bound to the originating operation", () => {
  assert.doesNotThrow(() => assertResultMatchesOperation(validRequest, validResult));

  const result = structuredClone(validResult);
  result.context.operationId = "66666666-6666-4666-8666-666666666666";

  assert.throws(
    () => assertResultMatchesOperation(validRequest, result),
    ({ code }) => code === operationErrorCodes.resultContextMismatch,
  );
});

test("stale assessment results are rejected", () => {
  const result = structuredClone(validResult);
  result.context.assessment.basedOnVersion = 6;

  assert.throws(
    () => assertResultMatchesOperation(validRequest, result),
    ({ code }) => code === operationErrorCodes.staleAssessmentVersion,
  );
});

test("tool invocations outside the operation allowlist are rejected", () => {
  const result = structuredClone(validResult);
  result.toolInvocations.push({
    toolName: "provider-catalog.publish",
    outcome: "succeeded",
  });

  assert.throws(
    () => assertResultMatchesOperation(validRequest, result),
    ({ code }) => code === operationErrorCodes.forbiddenTool,
  );
});
