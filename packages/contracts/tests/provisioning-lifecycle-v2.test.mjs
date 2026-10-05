import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const requestSchema = JSON.parse(await readFile(new URL("../schemas/provisioning-lifecycle-request.v2.schema.json", import.meta.url), "utf8"));
const previewSchema = JSON.parse(await readFile(new URL("../schemas/provisioning-lifecycle-preview.v2.schema.json", import.meta.url), "utf8"));
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv); ajv.addSchema(requestSchema); ajv.addSchema(previewSchema);
const request = ajv.getSchema(requestSchema.$id), analysis = ajv.getSchema(`${previewSchema.$id}#/properties/analysis`);
const common = ["TENANT_AND_SUBJECT_CORRELATION", "ATTRIBUTE_OWNERSHIP_AND_MAPPING", "ACCOUNT_DISABLE_AND_LOGIN_BLOCK",
  "APPLICATION_SESSION_INVALIDATION", "TOKEN_REVOCATION_OR_BOUNDED_EXPIRY", "FAILURE_RECOVERY_AND_RECONCILIATION"];
const patterns = { SCIM_PUSH: [...common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS"], JIT_LOGIN: [...common, "JIT_TRUSTED_LOGIN_AND_LINKING"],
  SCIM_AND_JIT: [...common, "SCIM_CLIENT_SERVER_DIRECTION", "SCIM_USER_OPERATIONS", "JIT_TRUSTED_LOGIN_AND_LINKING", "SCIM_JIT_COLLISION_POLICY"] };
const groupCommon = ["GROUP_SOURCE_AND_MEMBERSHIP_MAPPING", "GROUP_CHANGE_DELIVERY_AND_RECONCILIATION", "GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT", "GROUP_REMOVAL_AND_ACCESS_RECHECK"];
const groups = { UNKNOWN: [], NONE: [], SCIM_GROUPS: [...groupCommon, "SCIM_GROUP_OPERATIONS"], APPLICATION_BRIDGE: [...groupCommon, "APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY"] };
const idsFor = (p, g) => [...patterns[p], ...groups[g]];

test("V2 accepts twelve explicitly scoped designs without borrowing pattern, group or legacy conditions", () => {
  const all = [...new Set([...Object.values(patterns).flat(), ...Object.values(groups).flat()])];
  for (const patternId of Object.keys(patterns)) for (const groupStrategy of Object.keys(groups)) {
    const value = { expectedVersion: 0, patternId, groupStrategy, declarations: {} };
    assert.equal(request(value), true, ajv.errorsText(request.errors));
    assert.equal(request({ ...value, expectedVersion: 9007199254740991 }), true);
    for (const declaration of ["SATISFIED", "NOT_SATISFIED", "UNKNOWN"]) {
      assert.equal(request({ ...value, declarations: Object.fromEntries(idsFor(patternId, groupStrategy).map(id => [id, declaration])) }), true);
    }
    for (const id of all.filter(id => !idsFor(patternId, groupStrategy).includes(id))) {
      assert.equal(request({ ...value, declarations: { [id]: "SATISFIED" } }), false, `${patternId}/${groupStrategy} rejects ${id}`);
    }
    for (const invalid of [{ ...value, expectedVersion: -1 }, { ...value, expectedVersion: "0" }, { ...value, expectedVersion: 0.5 },
      { ...value, expectedVersion: true }, { ...value, expectedVersion: 9007199254740992 }, { ...value, groupStrategy: null },
      { ...value, groupStrategy: "LOGIN_CLAIMS" }, { ...value, declarations: null }, { ...value, declarations: { [common[0]]: null } },
      { ...value, declarations: { [common[0]]: "VERIFIED" } }, { ...value, declarations: { OFFBOARDING_AND_ACCESS_REVOCATION: "SATISFIED" } },
      { ...value, requirements: { scim: "NOT_REQUIRED" } }, { ...value, accessRevocationVerified: true }, { ...value, evaluatedAt: "2026-10-05T00:00:00Z" }]) {
      assert.equal(request(invalid), false);
    }
    for (const key of ["expectedVersion", "patternId", "groupStrategy", "declarations"]) { const missing = { ...value }; delete missing[key]; assert.equal(request(missing), false); }
  }
});

test("V2 analysis contracts require exact scoped check order and hard-failure precedence", () => {
  for (const patternId of Object.keys(patterns)) for (const groupStrategy of Object.keys(groups)) {
    const ids = idsFor(patternId, groupStrategy), scimGroupConflict = patternId === "JIT_LOGIN" && groupStrategy === "SCIM_GROUPS";
    const designReason = groupStrategy === "UNKNOWN" ? "GROUP_STRATEGY_UNKNOWN" : groupStrategy === "NONE" ? "NO_GROUP_TRANSPORT_PLANNED"
      : scimGroupConflict ? "SCIM_GROUPS_REQUIRE_SCIM_PATTERN" : "GROUP_TRANSPORT_PLANNED";
    const value = { patternId, groupStrategy, requirements: { scim: "NOT_REQUIRED", justInTimeProvisioning: "NOT_REQUIRED", groupSynchronization: "NOT_REQUIRED" },
      declarations: Object.fromEntries(ids.map(id => [id, "SATISFIED"])),
      requirementChecks: ["scim", "justInTimeProvisioning", "groupSynchronization"].map(key => ({ profilePath: `provisioning.${key}`, criticality: "NOT_REQUIRED", outcome: "NOT_APPLIED", reasonCode: "NO_REQUIREMENT" })),
      designChecks: [{ boundary: "groupTransport", outcome: groupStrategy === "UNKNOWN" ? "UNKNOWN" : groupStrategy === "NONE" ? "NOT_APPLIED"
        : scimGroupConflict ? "CONDITIONALLY_NOT_SATISFIED" : "CONDITIONALLY_SATISFIED", reasonCode: designReason }],
      conditionChecks: ids.map(conditionId => ({ conditionId, outcome: "CONDITIONALLY_SATISFIED", reasonCode: "DECLARED_CONDITION_SATISFIED" })),
      status: scimGroupConflict ? "CONDITIONALLY_DOES_NOT_MATCH" : groupStrategy === "UNKNOWN" ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES" };
    assert.equal(analysis(value), true, ajv.errorsText(analysis.errors));
    for (const mutate of [v => { v.conditionChecks.pop(); }, v => { v.conditionChecks[1] = v.conditionChecks[0]; }, v => { v.conditionChecks.reverse(); },
      v => { v.requirementChecks.reverse(); }, v => { v.designChecks = []; }, v => { v.designChecks[0].boundary = "providerCompatibility"; },
      v => { v.conditionChecks[0].outcome = "UNKNOWN"; }, v => { v.providerId = "fictional"; }]) {
      const invalid = structuredClone(value); mutate(invalid); assert.equal(analysis(invalid), false);
    }
    const unknown = structuredClone(value); unknown.conditionChecks[0] = { conditionId: ids[0], outcome: "UNKNOWN", reasonCode: "CONDITION_UNKNOWN" };
    unknown.status = scimGroupConflict ? "CONDITIONALLY_DOES_NOT_MATCH" : "NEEDS_INFORMATION";
    assert.equal(analysis(unknown), true, ajv.errorsText(analysis.errors));
    const failure = structuredClone(unknown); failure.conditionChecks[1] = { conditionId: ids[1], outcome: "CONDITIONALLY_NOT_SATISFIED", reasonCode: "DECLARED_CONDITION_NOT_SATISFIED" };
    failure.status = "CONDITIONALLY_DOES_NOT_MATCH"; assert.equal(analysis(failure), true, ajv.errorsText(analysis.errors));
    failure.status = "NEEDS_INFORMATION"; assert.equal(analysis(failure), false);
    failure.status = "CONDITIONALLY_MATCHES"; assert.equal(analysis(failure), false);
  }
});

test("V2 design preview leaves observed groups, revocation, recommendation and publication unverified", () => {
  for (const key of ["configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified", "accessRevocationVerified", "writesPerformed", "publicationReady", "recommendationReady"]) {
    assert.deepEqual(previewSchema.properties[key], { const: false });
  }
  assert.equal(previewSchema.properties.policyVersion.const, "provisioning-lifecycle-design-2");
  assert.equal(previewSchema.additionalProperties, false);
  assert.equal(previewSchema.properties.groupStrategies.minItems, 4); assert.equal(previewSchema.properties.conditionDefinitions.minItems, 16);
  assert.equal(previewSchema.properties.deferredBoundaries.const.length, 5);
  assert.equal(previewSchema.properties.offboardingReferences.const[1], "https://www.rfc-editor.org/rfc/rfc7009.html#section-3");
});
