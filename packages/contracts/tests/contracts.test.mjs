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

test("catalog v3 preserves old facts and requires category-scoped residency evidence", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v3.json"));
  const legacy = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v2.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v3.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.deepEqual(catalog.options.map(({ residency, ...option }) => option), legacy.options,
    "Residency evidence must not rewrite existing capability or context facts.");
  assert.equal(validate(legacy), false);
  const original = catalog.options[0].residency.USER_PROFILES;
  for (const field of ["coverage", "storageCountries", "evidenceStatus", "sourceUrl", "observedAt"]) {
    const invalid = structuredClone(catalog);
    delete invalid.options[0].residency.USER_PROFILES[field];
    assert.equal(validate(invalid), false, field);
  }
  for (const changes of [{ coverage: "COMPLETE", storageCountries: [] },
    { coverage: "PARTIAL", storageCountries: [] }, { coverage: "UNKNOWN", storageCountries: ["DE"] },
    { storageCountries: ["DE", "DE"] }, { storageCountries: ["de"] },
    { sourceUrl: "https://vendor.example.com/residency" }, { coverage: "MAYBE" }, { compliant: true }]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].residency.USER_PROFILES = { ...original, ...changes };
    assert.equal(validate(invalid), false, JSON.stringify(changes));
  }
  const missing = structuredClone(catalog);
  missing.options[0].residency = {};
  assert.equal(validate(missing), true, "Missing categories must remain representable as unknown evidence.");
  missing.options[0].residency.ALL = original;
  assert.equal(validate(missing), false);
  delete missing.options[0].residency;
  assert.equal(validate(missing), false, "The v3 category map must be explicit, even when empty.");
});

test("profile v3 requires independent controls and preserves exact mixed history", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const validate = schema("update-assessment-profile-request.v3");
  const profile = structuredClone(validAssessmentResponse.profile);
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false);
  const fields = ["phishingResistance", "nonExportableKeys", "stepUpAuthentication"];
  profile.security.authenticationControls = Object.fromEntries(fields.map(f => [f, "UNKNOWN"]));
  assert.equal(validate(request), true, validationMessage(validate));
  assert.equal(schema("update-assessment-profile-request.v2")(request), false);
  for (const field of fields) {
    for (const criticality of ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN"]) {
      profile.security.authenticationControls[field] = criticality;
      assert.equal(validate(request), true, validationMessage(validate));
    }
    for (const invalid of [null, true, 3, "AAL3", "MAYBE"]) {
      profile.security.authenticationControls[field] = invalid;
      assert.equal(validate(request), false);
    }
    delete profile.security.authenticationControls[field];
    assert.equal(validate(request), false);
    profile.security.authenticationControls[field] = "UNKNOWN";
  }
  const response = { ...validAssessmentResponse, profile, profileSchemaVersion: 3 };
  assert.equal(schema("assessment-response.v3")(response), true);
  const old = { workspaceId: response.workspaceId, assessmentId: response.id, version: 0,
    status: "DRAFT", profileSchemaVersion: 1, profile: validAssessmentResponse.profile,
    origin: "CREATED", recordedAt: response.createdAt };
  const middle = structuredClone(old);
  middle.version = 1; middle.profileSchemaVersion = 2;
  middle.profile.security.dataResidencyDetails = { allowedCountries: ["DE"], dataCategories: ["BACKUPS"] };
  const latest = { ...old, version: 2, profileSchemaVersion: 3, profile };
  const page = { items: [old, middle, latest], nextAfterVersion: null };
  assert.equal(schema("assessment-revision-page.v3")(page), true);
  assert.equal(schema("assessment-revision-page.v2")(page), false);
  for (const revision of [old, middle, latest]) {
    for (const version of [1, 2, 3, 4].filter(v => v !== revision.profileSchemaVersion)) {
      assert.equal(schema("assessment-revision-page.v3")({ items: [{ ...revision, profileSchemaVersion: version }], nextAfterVersion: null }), false);
    }
  }
});

test("catalog v4 freezes older facts and scopes enforceable human authentication", async () => {
  const catalog = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v4.json"));
  const old = await readJson(path.join(contractsRoot,
    "../../services/core-api/src/main/resources/catalog/synthetic.v3.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/synthetic-provider-catalog.v4.schema.json");
  assert.equal(validate(catalog), true, validationMessage(validate));
  assert.deepEqual(catalog.options.map(({ authenticationControls, ...option }) => option), old.options);
  assert.equal(validate(old), false);
  const select = c => c.options[0].authenticationControls.BROWSER.PARTNERS;
  const original = select(catalog).PHISHING_RESISTANCE;
  for (const field of ["availability", "enforcement", "evidenceStatus", "sourceUrl", "observedAt"]) {
    const invalid = structuredClone(catalog);
    delete select(invalid).PHISHING_RESISTANCE[field];
    assert.equal(validate(invalid), false, field);
  }
  for (const changes of [{ availability: "UNSUPPORTED", enforcement: "SUPPORTED" },
    { availability: "UNKNOWN", enforcement: "SUPPORTED" }, { availability: "OPTIONAL" },
    { enforcement: null }, { certified: true }, { sourceUrl: "https://vendor.example.com" },
    { sourceUrl: "https://user:secret@example.invalid" }, { observedAt: "yesterday" }]) {
    const invalid = structuredClone(catalog);
    select(invalid).PHISHING_RESISTANCE = { ...original, ...changes };
    assert.equal(validate(invalid), false, JSON.stringify(changes));
  }
  for (const client of ["MACHINE_TO_MACHINE", "WEB", "UNKNOWN"]) {
    const invalid = structuredClone(catalog);
    invalid.options[0].authenticationControls[client] = {};
    assert.equal(validate(invalid), false);
  }
  const missing = structuredClone(catalog);
  missing.options[0].authenticationControls = {};
  assert.equal(validate(missing), true);
  delete missing.options[0].authenticationControls;
  assert.equal(validate(missing), false);
  const otherPopulation = structuredClone(catalog);
  otherPopulation.options[0].authenticationControls.BROWSER.EVERYONE = select(catalog);
  assert.equal(validate(otherPopulation), false);
  const otherControl = structuredClone(catalog);
  select(otherControl).AAL3 = original;
  assert.equal(validate(otherControl), false);
});

test("profile v4 requires explicit compliance scope and preserves mixed historical formats", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const profile = structuredClone(validAssessmentResponse.profile);
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  profile.security.authenticationControls = { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" };
  const validate = schema("update-assessment-profile-request.v4");
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false);
  for (const status of ["UNKNOWN", "NONE_IDENTIFIED", "TARGETS_IDENTIFIED"]) {
    profile.security.complianceScopeStatus = status;
    assert.equal(validate(request), true, validationMessage(validate));
    // Target-list consistency is a domain invariant (422), not silent schema defaulting.
    for (const api of [1, 2, 3]) assert.equal(schema(`update-assessment-profile-request.v${api}`)(request), false);
  }
  for (const value of [null, true, 1, "COMPLIANT", "NOT_APPLICABLE", ""]) {
    profile.security.complianceScopeStatus = value;
    assert.equal(validate(request), false);
  }
  profile.security.complianceScopeStatus = "NONE_IDENTIFIED";
  profile.security.complianceTargets = [];
  const response = { ...validAssessmentResponse, profile, profileSchemaVersion: 4 };
  assert.equal(schema("assessment-response.v4")(response), true);
  assert.equal(schema("assessment-response.v4")({ ...response, profileSchemaVersion: 3 }), false);
  const latest = { workspaceId: response.workspaceId, assessmentId: response.id, version: 3,
    status: "DRAFT", profileSchemaVersion: 4, profile, origin: "UPDATED", recordedAt: response.createdAt };
  const items = [1, 2, 3, 4].map(format => {
    const item = structuredClone(latest);
    item.version = format - 1; item.profileSchemaVersion = format;
    if (format < 4) delete item.profile.security.complianceScopeStatus;
    if (format < 3) delete item.profile.security.authenticationControls;
    if (format < 2) delete item.profile.security.dataResidencyDetails;
    return item;
  });
  const page = { items, nextAfterVersion: null };
  assert.equal(schema("assessment-revision-page.v4")(page), true);
  for (const api of [1, 2, 3]) assert.equal(schema(`assessment-revision-page.v${api}`)(page), false);
  for (const revision of items) {
    for (const format of [1, 2, 3, 4, 5].filter(v => v !== revision.profileSchemaVersion)) {
      assert.equal(schema("assessment-revision-page.v4")({ items: [{ ...revision, profileSchemaVersion: format }], nextAfterVersion: null }), false);
    }
  }
});

test("profile v5 records bounded usage inputs without turning unknowns into zero", () => {
  const schema = name => ajv.getSchema(`https://authweave.dev/contracts/${name}.schema.json`);
  const profile = structuredClone(validAssessmentResponse.profile);
  profile.security.dataResidencyDetails = { allowedCountries: [], dataCategories: [] };
  profile.security.authenticationControls = { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" };
  profile.security.complianceScopeStatus = "UNKNOWN";
  const validate = schema("update-assessment-profile-request.v5");
  const request = { expectedVersion: 0, profile };
  assert.equal(validate(request), false);
  const usage = { scopeDescription: "", assumptions: [], volumes: {} };
  profile.operations.usagePlanning = usage;
  assert.equal(validate(request), true, validationMessage(validate));
  assert.deepEqual(usage.volumes, {});
  for (const api of [1, 2, 3, 4]) assert.equal(schema(`update-assessment-profile-request.v${api}`)(request), false);
  const metrics = ["MONTHLY_ACTIVE_USERS", "ENTERPRISE_SSO_CONNECTIONS", "MONTHLY_M2M_TOKEN_ISSUANCES", "PEAK_HUMAN_LOGINS_PER_SECOND"];
  for (const metric of metrics) {
    for (const basis of ["ASSUMED", "OBSERVED"]) {
      for (const value of [0, 9007199254740991]) {
        usage.volumes[metric] = { basis, value };
        assert.equal(validate(request), true, validationMessage(validate));
      }
    }
    for (const value of [null, {}, { basis: "ASSUMED" }, { value: 0 },
      { basis: "UNKNOWN", value: 0 }, { basis: 1, value: 0 }, { basis: "OBSERVED", value: -1 },
      { basis: "ASSUMED", value: 0.5 }, { basis: "ASSUMED", value: "0" },
      { basis: "ASSUMED", value: false }, { basis: "ASSUMED", value: 9007199254740992 },
      { basis: "OBSERVED", value: 0, price: 0 }]) {
      usage.volumes[metric] = value;
      assert.equal(validate(request), false, JSON.stringify(value));
    }
    usage.volumes[metric] = { basis: "ASSUMED", value: 0 };
  }
  for (const field of ["scopeDescription", "assumptions", "volumes"]) {
    const old = usage[field]; delete usage[field];
    assert.equal(validate(request), false);
    usage[field] = null; assert.equal(validate(request), false);
    usage[field] = old;
  }
  for (const assumptions of [[""], [" \t"], ["same", "same"], ["x".repeat(501)], [null], [1],
    Array.from({ length: 11 }, (_, i) => `Assumption ${i}`)]) {
    usage.assumptions = assumptions; assert.equal(validate(request), false);
  }
  usage.assumptions = Array.from({ length: 10 }, (_, i) => "x".repeat(499) + i);
  usage.scopeDescription = "x".repeat(500);
  assert.equal(validate(request), true);
  usage.scopeDescription += "x"; assert.equal(validate(request), false);
  usage.scopeDescription = "Synthetic pilot";
  usage.volumes.REGISTERED_USERS = { basis: "ASSUMED", value: 0 };
  assert.equal(validate(request), false); delete usage.volumes.REGISTERED_USERS;
  assert.equal(validate(request), true);
  const response = { ...validAssessmentResponse, profileSchemaVersion: 5, profile };
  assert.equal(schema("assessment-response.v5")(response), true);
  assert.equal(schema("assessment-response.v5")({ ...response, profileSchemaVersion: 4 }), false);
  const items = [1, 2, 3, 4, 5].map(format => {
    const revisionProfile = structuredClone(profile);
    if (format < 5) delete revisionProfile.operations.usagePlanning;
    if (format < 4) delete revisionProfile.security.complianceScopeStatus;
    if (format < 3) delete revisionProfile.security.authenticationControls;
    if (format < 2) delete revisionProfile.security.dataResidencyDetails;
    return { workspaceId: response.workspaceId, assessmentId: response.id, version: format - 1,
      status: "DRAFT", profileSchemaVersion: format, profile: revisionProfile, origin: "UPDATED", recordedAt: response.createdAt };
  });
  const page = { items, nextAfterVersion: null };
  assert.equal(schema("assessment-revision-page.v5")(page), true, validationMessage(schema("assessment-revision-page.v5")));
  for (const api of [1, 2, 3, 4]) assert.equal(schema(`assessment-revision-page.v${api}`)(page), false);
  for (const revision of items) {
    for (const format of [1, 2, 3, 4, 5, 6].filter(v => v !== revision.profileSchemaVersion)) {
      assert.equal(schema("assessment-revision-page.v5")({ items: [{ ...revision, profileSchemaVersion: format }], nextAfterVersion: null }), false);
    }
  }
});

test("provider catalog drafts require scoped provenance but cannot claim review or publication", async () => {
  const fixture = await readJson(path.join(fixturesRoot, "provider-catalog-draft.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/provider-catalog-draft.v1.schema.json");
  assert.equal(validate(fixture), true, validationMessage(validate));
  for (const version of [1, 2, 3, 4]) {
    assert.equal(ajv.getSchema(`https://authweave.dev/contracts/synthetic-provider-catalog.v${version}.schema.json`)(fixture), false);
  }
  for (const field of ["providerId", "product", "plan", "deployment", "region", "configuration", "compatibility", "residency", "authenticationControls"]) {
    const invalid = structuredClone(fixture); delete invalid.options[0][field];
    assert.equal(validate(invalid), false, field);
  }
  const selectors = [option => option.facts.SCIM, option => option.compatibility.clients.BROWSER,
    option => option.residency.USER_PROFILES, option => option.authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE];
  for (const select of selectors) {
    for (const field of ["conditions", "evidence"]) {
      const invalid = structuredClone(fixture); delete select(invalid.options[0])[field]; assert.equal(validate(invalid), false);
    }
    for (const field of ["sourceUrl", "observedAt", "summary"]) {
      const invalid = structuredClone(fixture); delete select(invalid.options[0]).evidence[field]; assert.equal(validate(invalid), false);
    }
    const reviewed = structuredClone(fixture); select(reviewed.options[0]).evidenceStatus = "REVIEWED";
    assert.equal(validate(reviewed), false);
    const valid = structuredClone(fixture); select(valid.options[0]).conditions = [];
    assert.equal(validate(valid), true, validationMessage(validate));
    for (const sourceUrl of ["http://example.invalid", "file:///private/example", "https://user:secret@example.invalid", "/source"]) {
      const invalid = structuredClone(fixture); select(invalid.options[0]).evidence.sourceUrl = sourceUrl;
      assert.equal(validate(invalid), false, sourceUrl);
    }
  }
  for (const forged of [{ approvedBy: "owner" }, { evidenceStatus: "REVIEWED" }, { kind: "APPROVED" }, { schemaVersion: 2 }]) {
    assert.equal(validate({ ...fixture, ...forged }), false);
  }
  for (const blank of ["", " \t", "\u00a0\u2003\ufeff"]) {
    const invalid = structuredClone(fixture); invalid.options[0].facts.SCIM.evidence.summary = blank;
    assert.equal(validate(invalid), false);
  }
  const unicode = structuredClone(fixture); unicode.options[0].facts.SCIM.evidence.summary = "\uD83D\uDD12".repeat(1000);
  assert.equal(validate(unicode), true); unicode.options[0].facts.SCIM.evidence.summary += "x"; assert.equal(validate(unicode), false);
  const empty = structuredClone(fixture); empty.options = []; assert.equal(validate(empty), false);
  const maximum = structuredClone(fixture);
  maximum.options = Array.from({ length: 100 }, (_, i) => ({ ...structuredClone(fixture.options[0]), id: `example-${i}`, configuration: `Configuration ${i}` }));
  assert.equal(validate(maximum), true, validationMessage(validate));
  maximum.options.push(structuredClone(fixture.options[0])); assert.equal(validate(maximum), false);
});

test("catalog draft shape and semantic review are deliberately separate", async () => {
  const fixture = await readJson(path.join(fixturesRoot, "provider-catalog-draft.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/provider-catalog-draft.v1.schema.json");
  const input = structuredClone(fixture);
  input.options[0].residency.USER_PROFILES.coverage = "UNKNOWN";
  input.options[0].residency.USER_PROFILES.storageCountries = ["ZZ"];
  input.options[0].authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE.availability = "UNSUPPORTED";
  input.options.push(structuredClone(input.options[0]));
  assert.equal(validate(input), true, "The Core dry run must report these contradictions; structural validity is not approval.");
  const unknown = structuredClone(fixture);
  unknown.options[0].facts.SCIM.availability = "UNKNOWN";
  unknown.options[0].facts.SCIM.evidence.observedAt = "2099-01-01T00:00:00Z";
  assert.equal(validate(unknown), true);
  delete unknown.options[0].facts.SCIM; assert.equal(validate(unknown), true);
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
