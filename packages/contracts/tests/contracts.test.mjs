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

test("profile v2 requires explicit residency details without silently expanding v1", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/update-assessment-profile-request.v2.schema.json");
  const validateResponse = ajv.getSchema("https://authweave.dev/contracts/assessment-response.v2.schema.json");
  const profile = structuredClone(validAssessmentResponse.profile);
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false, "Missing details must not be defaulted by the v2 API.");
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  assert.equal(validate(request), true, validationMessage(validate));
  assert.equal(validateUpdateAssessmentProfile(request), false, "v1 must reject the new field, even when empty.");
  const response = { ...validAssessmentResponse, profileSchemaVersion: 2, profile };
  assert.equal(validateResponse(response), true, validationMessage(validateResponse));
  assert.equal(validateResponse({ ...response, profileSchemaVersion: 1 }), false);
  for (const details of [null, {}, { allowedCountries: ["de"], dataCategories: [] },
    { allowedCountries: ["DE", "DE"], dataCategories: [] },
    { allowedCountries: ["DE"], dataCategories: ["BACKUPS", "BACKUPS"] },
    { allowedCountries: ["DE"], dataCategories: ["ALL"] },
    { allowedCountries: ["DE"], dataCategories: [], compliant: true }]) {
    profile.security.dataResidencyDetails = details;
    assert.equal(validate(request), false);
  }
  profile.security.dataResidencyDetails = { allowedCountries: ["DE", "FR"], dataCategories: ["USER_PROFILES", "BACKUPS"] };
  assert.equal(validate(request), true, validationMessage(validate));
});

test("v2 history preserves mixed schema versions and rejects mislabeled snapshots", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/assessment-revision-page.v2.schema.json");
  const old = { workspaceId: validAssessmentResponse.workspaceId, assessmentId: validAssessmentResponse.id,
    version: 0, status: "DRAFT", profileSchemaVersion: 1, profile: validAssessmentResponse.profile,
    origin: "CREATED", recordedAt: validAssessmentResponse.createdAt };
  const expanded = structuredClone(old);
  expanded.version = 1;
  expanded.origin = "UPDATED";
  expanded.profileSchemaVersion = 2;
  expanded.profile.security.dataResidencyDetails = { allowedCountries: ["DE"], dataCategories: ["BACKUPS"] };
  const page = { items: [old, expanded], nextAfterVersion: null };
  assert.equal(validate(page), true, validationMessage(validate));
  for (const revision of [{ ...old, profileSchemaVersion: 2 }, { ...expanded, profileSchemaVersion: 1 },
    { ...expanded, profileSchemaVersion: 3 }]) {
    assert.equal(validate({ items: [revision], nextAfterVersion: null }), false);
  }
  assert.equal(validate({ items: [], nextAfterVersion: null }), true);
});

test("runnable synthetic seeds match the canonical profile contract", async () => {
  const seeds = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/seed/assessments.v1.json"));
  assert.equal(seeds.length, 3);
  assert.equal(new Set(seeds.map(seed => seed.id)).size, 3);
  assert.deepEqual(new Set(seeds.map(seed => seed.key)),
    new Set(["b2b-saas", "public-sector-portal", "internal-workforce"]));
  const validate = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v1.schema.json");
  for (const seed of seeds) {
    assert.equal(validate(seed.profile), true, `${seed.key}: ${validationMessage(validate)}`);
  }
});

test("synthetic catalog requires dated, scoped provenance without claiming real vendor evidence", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v1.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.equal(new Set(catalog.options.map(option => option.id)).size, catalog.options.length);
  for (const field of ["sourceUrl", "observedAt", "evidenceStatus"]) {
    const invalid = structuredClone(catalog);
    delete invalid.options[0].facts.SCIM[field];
    assert.equal(validate(invalid), false, "Missing " + field + " must be rejected");
  }
  for (const field of ["plan", "region"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0][field] = " ";
    assert.equal(validate(invalid), false);
  }
  for (const url of ["https://vendor.example.com/facts", "https://user:password@example.invalid/scim", "file:///etc/passwd"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].facts.SCIM.sourceUrl = url;
    assert.equal(validate(invalid), false);
  }
  const unknown = structuredClone(catalog);
  unknown.options[0].facts.SCIM.availability = "MAYBE";
  assert.equal(validate(unknown), false);
  const missing = structuredClone(catalog);
  delete missing.options[0].facts.SCIM;
  assert.equal(validate(missing), true, "A missing fact is allowed; the evaluator must not infer support.");
});

test("catalog v2 requires explicit context evidence and rejects unclassified categories", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v2.json"));
  const legacy = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v2.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.equal(validate(legacy), false);
  assert.deepEqual(catalog.options.map(({ compatibility, ...option }) => option), legacy.options,
    "Catalog v2 must preserve every existing capability fact and option identity.");
  for (const field of ["support", "sourceUrl", "observedAt", "evidenceStatus"]) {
    const invalid = structuredClone(catalog);
    delete invalid.options[0].compatibility.applications.B2B_SAAS[field];
    assert.equal(validate(invalid), false, "Missing " + field + " must be rejected");
  }
  for (const [group, key] of [["applications", "OTHER"], ["applications", "UNKNOWN"],
    ["clients", "WEB"], ["populations", "EVERYONE"], ["tenancy", "UNKNOWN"], ["membership", "UNKNOWN"]]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].compatibility[group][key] = catalog.options[0].compatibility.applications.B2B_SAAS;
    assert.equal(validate(invalid), false);
  }
  for (const value of ["MAYBE", null, true]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].compatibility.applications.B2B_SAAS.support = value;
    assert.equal(validate(invalid), false);
  }
  for (const url of ["https://vendor.example.com/facts", "https://user:secret@example.invalid/facts"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].compatibility.applications.B2B_SAAS.sourceUrl = url;
    assert.equal(validate(invalid), false);
  }
  const empty = structuredClone(catalog);
  for (const group of Object.keys(empty.options[0].compatibility)) {
    empty.options[0].compatibility[group] = {};
  }
  assert.equal(validate(empty), true, "Absence of facts must be representable without inferring lack of support.");
  delete empty.options[0].compatibility.clients;
  assert.equal(validate(empty), false, "All context groups must be explicit, even when empty.");
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
