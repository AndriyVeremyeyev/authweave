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

test("catalog change previews require bound inputs and cannot accept forged workflow authority", async () => {
  const input = await readJson(path.join(fixturesRoot, "catalog-change-preview-request.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-change-preview-request.v1.schema.json");
  assert.equal(validate(input), true, validationMessage(validate));
  for (const field of ["schemaVersion", "proposalId", "rationale", "expectedBaseSha256", "base", "candidate"]) {
    const invalid = structuredClone(input); delete invalid[field]; assert.equal(validate(invalid), false, field);
    invalid[field] = null; assert.equal(validate(invalid), false, field);
  }
  for (const extra of [{ proposalState: "APPROVED" }, { curatorId: "forged" }, { approvalGranted: true }]) {
    assert.equal(validate({ ...input, ...extra }), false);
  }
  for (const expectedBaseSha256 of ["", "A".repeat(64), "a".repeat(63), "a".repeat(65), 1]) {
    assert.equal(validate({ ...input, expectedBaseSha256 }), false);
  }
  assert.equal(validate({ ...input, expectedBaseSha256: "0".repeat(64) }), true, "Digest mismatch is a preview blocker, not a malformed hash.");
  for (const rationale of ["", " \t", "\u00a0\u2003", "x".repeat(1001), 1]) assert.equal(validate({ ...input, rationale }), false);
  assert.equal(validate({ ...input, rationale: "\uD83D\uDD12".repeat(1000) }), true);
  assert.equal(validate({ ...input, schemaVersion: 2 }), false);
  const invalid = structuredClone(input); invalid.candidate.options[0].facts.SCIM.evidenceStatus = "REVIEWED";
  assert.equal(validate(invalid), false);
});

test("catalog change reports preserve typed before/after values and fail closed", async () => {
  const input = await readJson(path.join(fixturesRoot, "catalog-change-preview-request.valid.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-change-preview.v1.schema.json");
  const review = catalogVersion => ({ catalogVersion, contentSha256: "0".repeat(64), status: "VALID_DRAFT", optionCount: 1,
    factCount: 9, freshness: { current: 9, stale: 0, future: 0 }, issues: [] });
  const report = { scope: "CATALOG_CHANGE_PREVIEW", policyVersion: "catalog-change-preview-1",
    canonicalizationVersion: "catalog-draft-canonical-json-1", proposalId: input.proposalId, proposalSha256: "1".repeat(64),
    rationale: input.rationale, proposalState: "PROPOSED", evaluatedAt: "2026-09-12T12:00:00Z", status: "REVIEW_REQUIRED",
    diffComputed: true, catalogVersionChanged: true, baselineVerified: false, sourceVerificationPerformed: false,
    approvalGranted: false, writesPerformed: false, evaluationReady: false, impactAnalysisPerformed: false,
    baseReview: review(input.base.catalogVersion), candidateReview: review(input.candidate.catalogVersion), blockers: [],
    affectedOptionIds: [input.base.options[0].id], optionChanges: [], factChanges: [{ optionId: input.base.options[0].id,
      path: "facts.SCIM", factKind: "CAPABILITY", changeType: "MODIFIED", aspects: ["CLAIM"], evidenceStatus: "UNREVIEWED",
      before: input.base.options[0].facts.SCIM, after: input.candidate.options[0].facts.SCIM }] };
  assert.equal(validate(report), true, validationMessage(validate));
  for (const field of ["baselineVerified", "sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady", "impactAnalysisPerformed"]) {
    assert.equal(validate({ ...report, [field]: true }), false, field);
  }
  for (const change of [{ factKind: "RESIDENCY" }, { evidenceStatus: "REVIEWED" }, { before: null }, { after: null }, { aspects: [] }, { aspects: ["PRESENCE"] }]) {
    const invalid = structuredClone(report); Object.assign(invalid.factChanges[0], change); assert.equal(validate(invalid), false, JSON.stringify(change));
  }
  for (const changeType of ["ADDED", "REMOVED"]) {
    const valid = structuredClone(report); valid.factChanges[0].changeType = changeType; valid.factChanges[0].aspects = ["PRESENCE"];
    valid.factChanges[0][changeType === "ADDED" ? "before" : "after"] = null;
    assert.equal(validate(valid), true, validationMessage(validate));
    valid.factChanges[0].aspects = ["CLAIM"]; assert.equal(validate(valid), false);
  }
  assert.equal(validate({ ...report, status: "NO_CONTENT_CHANGES" }), false);
  assert.equal(validate({ ...report, status: "BLOCKED", blockers: ["BASE_DIGEST_MISMATCH"] }), false);
  const blocked = { ...report, status: "BLOCKED", blockers: ["BASE_DIGEST_MISMATCH"], diffComputed: false,
    affectedOptionIds: [], optionChanges: [], factChanges: [] };
  assert.equal(validate(blocked), true, validationMessage(validate));
  assert.equal(validate({ ...blocked, blockers: [] }), false);
  assert.equal(validate({ ...blocked, diffComputed: true }), false);
  assert.equal(validate({ ...blocked, proposalState: "APPROVED" }), false);
  const unchanged = { ...blocked, status: "NO_CONTENT_CHANGES", diffComputed: true, blockers: [] };
  assert.equal(validate(unchanged), true);

  const snapshot = { proposalId: input.proposalId, version: 0, state: "PROPOSED", requestSchemaVersion: 1,
    proposalSha256: report.proposalSha256, recordedAt: report.evaluatedAt, request: input, preview: report };
  const validateSnapshot = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-snapshot.v1.schema.json");
  assert.equal(validateSnapshot(snapshot), true, validationMessage(validateSnapshot));
  for (const invalid of [{ state: "APPROVED" }, { version: -1 }, { version: 9007199254740992 }, { requestSchemaVersion: 2 },
    { preview: blocked }, { preview: unchanged }, { actorId: "forged" }]) assert.equal(validateSnapshot({ ...snapshot, ...invalid }), false);
  const validatePage = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-revision-page.v1.schema.json");
  assert.equal(validatePage({ items: [snapshot], nextAfterVersion: null }), true);
  assert.equal(validatePage({ items: [], nextAfterVersion: null }), true);
  assert.equal(validatePage({ items: [snapshot], nextAfterVersion: -1 }), false);
  assert.equal(validatePage({ items: Array(101).fill(snapshot), nextAfterVersion: 0 }), false);
});

test("catalog impact remains conditional, version-bound and explicit about incomplete coverage", async () => {
  const report = await readJson(path.join(fixturesRoot, "catalog-impact-preview.valid.json"));
  const golden = await readJson(path.join(fixturesRoot, "catalog-impact-probes.expected.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-preview.v1.schema.json");
  assert.equal(validate(report), true, validationMessage(validate));
  assert.equal(report.caseSetSha256, golden.caseSetSha256);
  assert.equal(report.caseSetVersion, golden.caseSetVersion);
  assert.equal(report.caseDefinitions.length, 24);
  assert.deepEqual(report.cases.filter(c => c.conditionalResultChanged).map(c => c.caseId), ["required-scim"]);
  const stored = { ...report, storedProposalVersion: 0, storedRequestDigestVerified: true };
  assert.equal(validate(stored), true, validationMessage(validate));
  for (const field of ["coverageComplete", "baselineVerified", "sourceVerificationPerformed", "approvalGranted",
    "writesPerformed", "evaluationReady", "recommendationReady"]) {
    assert.equal(validate({ ...report, [field]: true }), false, field);
  }
  for (const change of [{ storedProposalVersion: 0 }, { storedRequestDigestVerified: true }, { impactAnalysisPerformed: false },
    { hypotheticalEvaluationPerformed: false }, { analysisBasis: "VERIFIED_EVIDENCE" }, { caseSetSha256: "unversioned" },
    { caseDefinitions: report.caseDefinitions.slice(1) }, { status: "APPROVED" }, { score: 100 }]) {
    assert.equal(validate({ ...report, ...change }), false, JSON.stringify(change));
  }
  for (const value of [-1, 9007199254740992, 1.5]) assert.equal(validate({ ...stored, storedProposalVersion: value }), false);
  for (const change of [{ conditionalOutcome: "PASS" }, { conditionalOutcome: "FAIL" }, { freshness: null },
    { factPresent: false }, { optionPresent: false }]) {
    const invalid = structuredClone(report); Object.assign(invalid.cases[0].before, change);
    assert.equal(validate(invalid), false, JSON.stringify(change));
  }
  const absent = structuredClone(report);
  Object.assign(absent.cases[0].before, { optionPresent: false, factPresent: false, conditionalOutcome: "INDETERMINATE",
    reason: "OPTION_ABSENT", freshness: null, conditionsRecorded: false });
  assert.equal(validate(absent), true, validationMessage(validate));
  const uncovered = { ...report, cases: [], uncoveredChanges: [{ optionId: "example-managed-eu",
    factPath: "compatibility.membership.SINGLE_ORGANIZATION_PER_USER", reason: "NO_PROBE_FOR_FACT_PATH" }] };
  assert.equal(validate(uncovered), true, validationMessage(validate));
  const blocked = { ...report, status: "BLOCKED", impactAnalysisPerformed: false, hypotheticalEvaluationPerformed: false,
    cases: [], uncoveredChanges: [], changePreview: { ...report.changePreview, status: "BLOCKED", diffComputed: false,
      blockers: ["BASE_DIGEST_MISMATCH"], affectedOptionIds: [], optionChanges: [], factChanges: [] } };
  assert.equal(validate(blocked), true, validationMessage(validate));
  assert.equal(validate({ ...blocked, impactAnalysisPerformed: true }), false);
  assert.equal(validate({ ...blocked, cases: report.cases }), false);
  assert.equal(validate({ ...blocked, uncoveredChanges: uncovered.uncoveredChanges }), false);
  assert.equal(validate({ ...blocked, changePreview: report.changePreview }), false);
});

test("full-profile impact scenarios preserve all three seeds with explicit unknown newer fields", async () => {
  const definitions = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog/impact-scenarios.v1.json"));
  const seeds = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/seed/assessments.v1.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-scenario-impact.v1.schema.json#/$defs/definition");
  assert.deepEqual(definitions.map(d => d.id), seeds.map(s => s.key));
  for (const [i, definition] of definitions.entries()) {
    assert.equal(validate(definition), true, validationMessage(validate));
    const profile = structuredClone(definition.profile);
    assert.deepEqual(profile.security.dataResidencyDetails, { allowedCountries: [], dataCategories: [] });
    assert.deepEqual(profile.security.authenticationControls, { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" });
    assert.equal(profile.security.complianceScopeStatus, "UNKNOWN");
    assert.deepEqual(profile.operations.usagePlanning, { scopeDescription: "", assumptions: [], volumes: {} });
    delete profile.security.dataResidencyDetails; delete profile.security.authenticationControls;
    delete profile.security.complianceScopeStatus; delete profile.operations.usagePlanning;
    assert.deepEqual(profile, seeds[i].profile);
    const missing = structuredClone(definition); delete missing.profile.operations.usagePlanning;
    assert.equal(validate(missing), false);
    assert.equal(validate({ ...definition, profileSchemaVersion: 1 }), false);
  }
});

test("scenario impact wire shape forbids readiness claims and preserves storage and absence boundaries", async () => {
  const { caseDefinitions, cases, ...common } = await readJson(path.join(fixturesRoot, "catalog-impact-preview.valid.json"));
  const definitions = await readJson(path.join(contractsRoot, "../../services/core-api/src/main/resources/catalog/impact-scenarios.v1.json"));
  const golden = await readJson(path.join(fixturesRoot, "catalog-scenario-impact.expected.json"));
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-scenario-impact.v1.schema.json");
  const check = { checkId: "provisioning.scim|facts.SCIM", profilePath: "provisioning.scim", factPath: "facts.SCIM", usesFact: true,
    factPresent: true, conditionalOutcome: "WOULD_SATISFY", reason: "REQUIRED_CLAIM_AVAILABLE", freshness: "CURRENT", conditionsRecorded: true };
  const side = { optionPresent: true, conditionalStatus: "WOULD_SATISFY_CHECKED_REQUIREMENTS", checks: [check] };
  const report = { ...common, scope: "CATALOG_PROFILE_SCENARIO_IMPACT", policyVersion: "catalog-scenario-impact-1",
    profilePolicyVersion: "eligibility-preflight-4", caseSetVersion: golden.caseSetVersion, caseSetSha256: golden.caseSetSha256,
    deferredPaths: ["security.browserTokenExposureMinimization", "security.auditability", "security.assurance", "security.complianceTargets",
      "security.authenticationControls", "provisioning", "operations"], scenarioDefinitions: definitions,
    scenarios: [{ scenarioId: "b2b-saas", optionId: "example-managed-eu", scopeChanged: false, affectedFactPaths: ["facts.SCIM"],
      conditionalStatusChanged: false, changedCheckIds: [], before: side, after: side }] };
  assert.equal(validate(report), true, validationMessage(validate));
  assert.equal(validate({ ...report, storedProposalVersion: 0, storedRequestDigestVerified: true }), true);
  for (const field of ["coverageComplete", "baselineVerified", "sourceVerificationPerformed", "approvalGranted", "writesPerformed", "evaluationReady", "recommendationReady"]) {
    assert.equal(validate({ ...report, [field]: true }), false, field);
  }
  for (const change of [{ deferredPaths: [] }, { scenarioDefinitions: [] }, { storedRequestDigestVerified: true },
    { storedProposalVersion: 0 }, { impactAnalysisPerformed: false }, { hypotheticalEvaluationPerformed: false }, { recommendation: "winner" }]) {
    assert.equal(validate({ ...report, ...change }), false, JSON.stringify(change));
  }
  for (const change of [{ conditionalOutcome: "PASS" }, { conditionalOutcome: "FAIL" }, { usesFact: false }, { freshness: null }, { factPath: null }]) {
    const invalid = structuredClone(report); Object.assign(invalid.scenarios[0].before.checks[0], change);
    assert.equal(validate(invalid), false, JSON.stringify(change));
  }
  const absent = structuredClone(report);
  Object.assign(absent.scenarios[0].before, { optionPresent: false, conditionalStatus: "OPTION_ABSENT" });
  Object.assign(absent.scenarios[0].before.checks[0], { factPresent: false, conditionalOutcome: "INDETERMINATE", reason: "OPTION_ABSENT", freshness: null, conditionsRecorded: false });
  assert.equal(validate(absent), true, validationMessage(validate));
  absent.scenarios[0].before.conditionalStatus = "WOULD_SATISFY_CHECKED_REQUIREMENTS";
  assert.equal(validate(absent), false);
  const missingFact = structuredClone(report);
  Object.assign(missingFact.scenarios[0].before.checks[0], { factPresent: false, freshness: null, conditionsRecorded: false });
  assert.equal(validate(missingFact), false);
  const unusedFact = structuredClone(report);
  Object.assign(unusedFact.scenarios[0].before.checks[0], { usesFact: false, conditionalOutcome: "NOT_APPLIED", reason: "NO_REQUIREMENT" });
  assert.equal(validate(unusedFact), true, validationMessage(validate));
  const blocked = { ...report, status: "BLOCKED", impactAnalysisPerformed: false, hypotheticalEvaluationPerformed: false,
    scenarios: [], uncoveredChanges: [], changePreview: { ...report.changePreview, status: "BLOCKED", diffComputed: false,
      blockers: ["BASE_DIGEST_MISMATCH"], affectedOptionIds: [], optionChanges: [], factChanges: [] } };
  assert.equal(validate(blocked), true, validationMessage(validate));
  assert.equal(validate({ ...blocked, scenarios: report.scenarios }), false);
  assert.equal(validate({ ...blocked, hypotheticalEvaluationPerformed: true }), false);
  const stored = { reportId: "44444444-4444-4444-8444-444444444444", reportNumber: 1,
    proposalId: report.proposalId, proposalVersion: 0, reportSchemaVersion: 1,
    canonicalizationVersion: "catalog-draft-canonical-json-1", proposalSha256: report.proposalSha256,
    reportSha256: "1".repeat(64), recordedAt: report.evaluatedAt,
    report: { ...report, storedProposalVersion: 0, storedRequestDigestVerified: true } };
  const validateStored = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-report.v1.schema.json");
  assert.equal(validateStored(stored), true, validationMessage(validateStored));
  for (const invalid of [{ reportNumber: 0 }, { reportNumber: 9007199254740992 }, { proposalVersion: -1 },
    { reportSchemaVersion: 2 }, { canonicalizationVersion: "unversioned" }, { reportSha256: "bad" }, { report }, { approved: true }]) {
    assert.equal(validateStored({ ...stored, ...invalid }), false, JSON.stringify(invalid));
  }
  assert.equal(validateStored({ ...stored, report: { ...stored.report, approvalGranted: true } }), false);
  const validatePage = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-report-page.v1.schema.json");
  assert.equal(validatePage({ items: [stored], nextAfterReportNumber: 1 }), true);
  assert.equal(validatePage({ items: [], nextAfterReportNumber: null }), true);
  assert.equal(validatePage({ items: [stored], nextAfterReportNumber: -1 }), false);
  assert.equal(validatePage({ items: Array(101).fill(stored), nextAfterReportNumber: 1 }), false);
});

test("impact recording events do not claim human authorization or embed report contents", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-impact-report-event.v1.schema.json");
  const event = { id: "11111111-1111-4111-8111-111111111111", reportId: "44444444-4444-4444-8444-444444444444",
    proposalId: "33333333-3333-4333-8333-333333333333", proposalVersion: 0, reportSha256: "1".repeat(64),
    action: "catalog-impact.recorded", actorType: "SERVICE", actorId: "core-api-local-catalog",
    correlationId: "22222222-2222-4222-8222-222222222222", outcome: "SUCCEEDED", occurredAt: "2026-09-13T12:00:00Z" };
  assert.equal(validate(event), true, validationMessage(validate));
  for (const invalid of [{ actorType: "CURATOR" }, { actorId: "human" }, { action: "catalog-proposal.approved" },
    { report: {} }, { proposalVersion: -1 }, { reportSha256: "bad" }, { outcome: "APPROVED" }]) {
    assert.equal(validate({ ...event, ...invalid }), false, JSON.stringify(invalid));
  }
});

test("stored proposal events describe local writes without claiming authorized review", () => {
  const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-event.v1.schema.json");
  const event = { id: "11111111-1111-4111-8111-111111111111", proposalId: "22222222-2222-4222-8222-222222222222",
    version: 0, previousVersion: null, action: "catalog-proposal.created", actorType: "SERVICE", actorId: "core-api-local-catalog",
    correlationId: "33333333-3333-4333-8333-333333333333", outcome: "SUCCEEDED", proposalSha256: "0".repeat(64), occurredAt: "2026-09-13T12:00:00Z" };
  assert.equal(validate(event), true, validationMessage(validate));
  assert.equal(validate({ ...event, version: 1, previousVersion: 0, action: "catalog-proposal.revised" }), true);
  for (const invalid of [{ previousVersion: 0 }, { version: 1 }, { actorType: "CURATOR" }, { actorId: "forged" },
    { action: "catalog-proposal.approved" }, { rationale: "Raw input" }, { outcome: "FAILED" }, { version: 9007199254740992 }]) {
    assert.equal(validate({ ...event, ...invalid }), false);
  }
  const page = ajv.getSchema("https://authweave.dev/contracts/catalog-proposal-event-page.v1.schema.json");
  assert.equal(page({ items: [event], nextAfterVersion: 0 }), true);
  assert.equal(page({ items: [], nextAfterVersion: null }), true);
  assert.equal(page({ items: [{ ...event, actorType: "CURATOR" }], nextAfterVersion: null }), false);
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
