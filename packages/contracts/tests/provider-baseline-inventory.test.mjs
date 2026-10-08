import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import {
  inspectBaselineDrafts, inspectBaselinePack, inspectScopedBaselineDraft,
  readBaselineDrafts, readScopedBaselineDrafts,
} from "../scripts/inspect-provider-baselines.mjs";

const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);
const schema = JSON.parse(await readFile(new URL("../schemas/provider-baseline-inventory.v2.schema.json", import.meta.url), "utf8"));
const validate = ajv.compile(schema);
const validateLegacy = ajv.compile(JSON.parse(await readFile(new URL("../schemas/provider-baseline-inventory.v1.schema.json", import.meta.url), "utf8")));
const at = new Date("2026-10-08T17:49:06Z");
const research = await readBaselineDrafts();
const scoped = await readScopedBaselineDrafts();
const pack = await inspectBaselinePack(at);
const inventory = pack.schemaPathInventory;
const flags = ["requirementCoverageEstablished", "sourceVerificationPerformed", "approvalGranted",
  "writesPerformed", "fullCoverageEstablished", "evaluationReady"];

// Independently enumerate the typed address vocabulary, not a customer's requirements.
const populations = ["EXTERNAL_CUSTOMERS", "PARTNERS", "CITIZENS", "EMPLOYEES", "CONTRACTORS", "INTERNAL_OPERATORS"];
const expectedVocabulary = [
  { family: "CAPABILITY", paths: ["OIDC", "SAML", "OAUTH2_APIS", "SOCIAL_LOGIN", "ENTERPRISE_SSO", "SCIM", "JIT", "GROUP_SYNC", "MFA"].map((value) => `facts.${value}`).sort() },
  { family: "COMPATIBILITY", paths: Object.entries({
    applications: ["B2B_SAAS", "PARTNER_PORTAL", "PUBLIC_SECTOR_PORTAL", "INTERNAL_WORKFORCE"],
    clients: ["BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"], populations,
    tenancy: ["MULTI_TENANT_ORGANIZATIONS", "SINGLE_ORGANIZATION", "NO_ORGANIZATION_BOUNDARY"],
    membership: ["SINGLE_ORGANIZATION_PER_USER", "MULTIPLE_ORGANIZATIONS_PER_USER", "NOT_APPLICABLE"],
  }).flatMap(([group, values]) => values.map((value) => `compatibility.${group}.${value}`)).sort() },
  { family: "RESIDENCY", paths: ["USER_PROFILES", "CREDENTIALS", "AUDIT_LOGS", "BACKUPS"].map((value) => `residency.${value}`).sort() },
  { family: "AUTHENTICATION_CONTROL", paths: ["BROWSER", "NATIVE_MOBILE"].flatMap((client) =>
    populations.flatMap((population) => ["PHISHING_RESISTANCE", "NON_EXPORTABLE_KEYS", "STEP_UP_AUTHENTICATION"]
      .map((control) => `authenticationControls.${client}.${population}.${control}`))).sort() },
];

function assertPartitions(report) {
  const result = report.schemaPathInventory;
  assert.equal(validate(result), true, ajv.errorsText(validate.errors));
  assert.deepEqual(result.pathVocabulary, expectedVocabulary);
  assert.equal(result.evaluatedAt, report.evaluatedAt);
  assert.equal(result.optionCount, report.optionCount);
  assert.equal(result.optionPathCount, report.optionCount * 68);
  assert.equal(result.recordedPathCount, report.factCount);
  assert.equal(result.recordedPathCount + result.omittedPathCount, result.optionPathCount);
  assert.deepEqual(result.options.map((option) => option.optionId), report.options.map((option) => option.optionId));
  const availability = { OPTIONAL: 0, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 };
  const compatibility = { SUPPORTED: 0, UNSUPPORTED: 0, UNKNOWN: 0 };
  const familyCounts = { CAPABILITY: 0, COMPATIBILITY: 0, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 };
  const freshness = { CURRENT: 0, STALE: 0, FUTURE: 0 };
  for (const entry of result.options) {
    const source = report.options.find((option) => option.optionId === entry.optionId);
    const recorded = source.facts.map((fact) => fact.path).sort();
    assert.equal(entry.catalogVersion, source.catalogVersion);
    assert.equal(entry.recordedPathCount, recorded.length);
    assert.equal(entry.omittedPathCount, 68 - recorded.length);
    assert.deepEqual(entry.recordedUnknownPaths, source.facts.filter((fact) => fact.availability === "UNKNOWN" || fact.support === "UNKNOWN").map((fact) => fact.path).sort());
    assert.deepEqual(entry.families.map((family) => family.family), expectedVocabulary.map((family) => family.family));
    for (const family of entry.families) {
      const paths = expectedVocabulary.find((vocabulary) => vocabulary.family === family.family).paths;
      assert.deepEqual(family.recordedPaths, paths.filter((address) => recorded.includes(address)));
      assert.deepEqual(family.omittedPaths, paths.filter((address) => !recorded.includes(address)));
      assert.deepEqual([...family.recordedPaths, ...family.omittedPaths].sort(), paths);
      assert.equal(new Set([...family.recordedPaths, ...family.omittedPaths]).size, paths.length);
      familyCounts[family.family] += family.recordedPaths.length;
    }
    for (const fact of source.facts) {
      assert.equal(fact.evidenceStatus, "UNREVIEWED");
      if (fact.path.startsWith("facts.")) {
        assert.equal(Object.hasOwn(fact, "support"), false);
        availability[fact.availability] += 1;
      } else {
        assert.equal(Object.hasOwn(fact, "availability"), false);
        compatibility[fact.support] += 1;
      }
      freshness[fact.freshness] += 1;
    }
  }
  assert.deepEqual(result.proposedAvailabilityCounts, availability);
  assert.deepEqual(result.proposedCompatibilityCounts, compatibility);
  assert.deepEqual(result.recordedFamilyCounts, familyCounts);
  assert.equal(Object.values(availability).reduce((sum, count) => sum + count, 0), familyCounts.CAPABILITY);
  assert.equal(Object.values(compatibility).reduce((sum, count) => sum + count, 0), familyCounts.COMPATIBILITY);
  assert.equal(Object.values(familyCounts).reduce((sum, count) => sum + count, 0), report.factCount);
  assert.deepEqual(result.recordedFreshnessCounts, freshness);
  for (const flag of flags) assert.equal(result[flag], false, flag);
}

test("inventory v2 enumerates 68 schema addresses, not 68 required customer facts", () => {
  assert.equal(inventory.schemaVersion, 2);
  assert.equal(inventory.policyVersion, "provider-baseline-schema-path-inventory-2");
  assert.equal(inventory.schemaPathCountPerOption, 68);
  assert.deepEqual(inventory.pathVocabulary, expectedVocabulary);
  assert.deepEqual(expectedVocabulary.map((family) => family.paths.length), [9, 19, 4, 36]);
  assert.equal(new Set(expectedVocabulary.flatMap((family) => family.paths)).size, 68);
  assert.ok(!expectedVocabulary[3].paths.some((address) => address.includes("MACHINE_TO_MACHINE")));
  assert.deepEqual(inventory.unverifiedBoundaries, ["COMMERCIAL_SCOPE", "AUDITABILITY", "COST",
    "LIFECYCLE_ENFORCEMENT", "CONFIGURATION_VERIFICATION", "SOURCE_VERIFICATION", "CURATOR_APPROVAL"]);
});

test("the full pack partitions every option independently and replays all aggregate counts", () => {
  assertPartitions(pack);
  assert.equal(inventory.optionCount, 33);
  assert.equal(inventory.optionPathCount, 2244);
  assert.equal(inventory.recordedPathCount, 108);
  assert.equal(inventory.omittedPathCount, 2136);
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 39, MANDATORY: 0, UNAVAILABLE: 3, UNKNOWN: 33 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 29, UNSUPPORTED: 0, UNKNOWN: 4 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 75, COMPATIBILITY: 33, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  assert.deepEqual(inventory.recordedFreshnessCounts, { CURRENT: 108, STALE: 0, FUTURE: 0 });
});

test("recorded UNKNOWN, omitted and proposed unavailable paths remain distinct", () => {
  const option = (id) => inventory.options.find((entry) => entry.optionId === id);
  const researchEntry = option("keycloak-self-hosted-research");
  assert.deepEqual(researchEntry.recordedUnknownPaths, ["facts.GROUP_SYNC", "facts.OIDC", "facts.SCIM"]);
  assert.ok(researchEntry.families[0].omittedPaths.includes("facts.SAML"));
  const native = option("keycloak-26.8.0-native-self-hosted");
  assert.deepEqual(native.recordedUnknownPaths, []);
  assert.ok(native.families[0].recordedPaths.includes("facts.SAML"));
  const zitadel = option("zitadel-cloud-free-native");
  assert.deepEqual(zitadel.recordedUnknownPaths, ["facts.SCIM"]);
  assert.ok(zitadel.families[0].recordedPaths.includes("facts.GROUP_SYNC"));
  const workos = option("workos-directory-sync-staging-scim-events");
  assert.ok(workos.families[0].omittedPaths.includes("facts.OIDC"));
  const connect = option("workos-connect-staging-public-oidc-clients");
  assert.deepEqual(connect.recordedUnknownPaths, ["compatibility.clients.BROWSER"]);
  assert.ok(connect.families[1].recordedPaths.includes("compatibility.clients.BROWSER"));
  assert.ok(connect.families[1].omittedPaths.includes("compatibility.clients.MACHINE_TO_MACHINE"));
  const entraOrganizations = option("entra-external-id-basic-organization-context");
  assert.deepEqual(entraOrganizations.recordedUnknownPaths, [
    "compatibility.applications.PARTNER_PORTAL",
    "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER",
    "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS",
  ]);
  assert.deepEqual(entraOrganizations.families[0].recordedPaths, []);
  for (const entry of inventory.options) for (const family of entry.families.slice(1)) {
    const basis = pack.options.find((option) => option.optionId === entry.optionId).basis;
    const expectedContext = basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT"
      ? ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE"]
      : basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT"
        ? ["compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
          "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS"]
        : basis === "MACHINE_SCOPED_DOCUMENTATION_DRAFT" ? ["compatibility.clients.MACHINE_TO_MACHINE"] : [];
    assert.deepEqual(family.recordedPaths, family.family === "COMPATIBILITY" ? expectedContext : []);
  }
});

test("single scoped and research inspections use the same inventory contract without merging", () => {
  assertPartitions(inspectBaselineDrafts(research, at));
  for (const draft of scoped) {
    const report = inspectScopedBaselineDraft(draft, at);
    assertPartitions(report);
    assert.deepEqual(report.schemaPathInventory.options[0],
      inventory.options.find((option) => option.optionId === draft.options[0].id));
  }
});

test("freshness counts replay mixed, future and stale observations without readiness promotion", async () => {
  const mixed = await inspectBaselinePack(new Date("2026-10-02T19:58:06Z"));
  assertPartitions(mixed);
  assert.deepEqual(mixed.schemaPathInventory.recordedFreshnessCounts, { CURRENT: 15, STALE: 0, FUTURE: 93 });
  for (const [instant, expected] of [
    ["2026-10-01T00:00:00Z", { CURRENT: 0, STALE: 0, FUTURE: 108 }],
    ["2027-01-20T00:00:00Z", { CURRENT: 0, STALE: 108, FUTURE: 0 }],
  ]) {
    const report = await inspectBaselinePack(new Date(instant));
    assertPartitions(report);
    assert.deepEqual(report.schemaPathInventory.recordedFreshnessCounts, expected);
    assert.deepEqual(report.schemaPathInventory.proposedAvailabilityCounts, inventory.proposedAvailabilityCounts);
    assert.deepEqual(report.schemaPathInventory.options, inventory.options);
  }
});

test("the inclusive freshness boundary does not rewrite evidence or leak mutable inventory arrays", () => {
  const original = JSON.stringify(research);
  const observed = Date.parse("2026-10-02T19:58:06Z");
  for (const [offset, expected] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const result = inspectBaselineDrafts(research, new Date(observed + offset)).schemaPathInventory;
    assert.equal(result.recordedFreshnessCounts[expected], 15);
    assert.equal(result.proposedAvailabilityCounts.UNKNOWN, 15);
  }
  const report = inspectBaselineDrafts(research, at);
  report.schemaPathInventory.pathVocabulary[0].paths.length = 0;
  report.schemaPathInventory.options[0].families[0].omittedPaths.length = 0;
  report.schemaPathInventory.options[0].recordedUnknownPaths.length = 0;
  assertPartitions(inspectBaselineDrafts(research, at));
  assert.equal(JSON.stringify(research), original);
});

test("inventory ordering is deterministic independently of input option and condition ordering", () => {
  const reordered = structuredClone(research).reverse();
  for (const draft of reordered) {
    draft.options[0].facts = Object.fromEntries(Object.entries(draft.options[0].facts).reverse());
    for (const fact of Object.values(draft.options[0].facts)) fact.conditions.reverse();
  }
  assert.deepEqual(inspectBaselineDrafts(reordered, at), inspectBaselineDrafts(research, at));
});

test("the closed inventory schema rejects authority claims, unknown properties and malformed paths", () => {
  const reject = (change) => {
    const invalid = structuredClone(inventory);
    change(invalid);
    assert.equal(validate(invalid), false, "Invalid inventory must be rejected");
  };
  for (const flag of flags) reject((value) => { value[flag] = true; });
  for (const key of ["schemaVersion", "scope", "policyVersion", "catalogSchemaId", "schemaPathCountPerOption"]) {
    reject((value) => { value[key] = "unexpected"; });
  }
  for (const key of Object.keys(inventory)) reject((value) => { delete value[key]; });
  for (const select of [(value) => value, (value) => value.options[0], (value) => value.options[0].families[0],
    (value) => value.pathVocabulary[0], (value) => value.proposedAvailabilityCounts, (value) => value.recordedFreshnessCounts,
    (value) => value.proposedCompatibilityCounts, (value) => value.recordedFamilyCounts]) {
    reject((value) => { select(value).approved = true; });
  }
  reject((value) => { value.omittedPathCount = -1; });
  reject((value) => { value.recordedPathCount = 1.5; });
  reject((value) => { value.options[0].recordedPathCount = 69; });
  reject((value) => {
    const family = value.options.flatMap((option) => option.families).find((entry) => entry.recordedPaths.length > 0);
    family.recordedPaths.push(family.recordedPaths[0]);
  });
  reject((value) => { value.pathVocabulary[0].paths[0] = "https://example.invalid/fact"; });
  reject((value) => { value.unverifiedBoundaries.pop(); });
  reject((value) => { value.evaluatedAt = "not-a-date"; });
});

test("inventory v1 remains a distinct capability-only legacy contract, not a silent v2 reinterpretation", () => {
  const legacy = structuredClone(inventory);
  legacy.schemaVersion = 1;
  legacy.policyVersion = "provider-baseline-schema-path-inventory-1";
  legacy.options = legacy.options.filter((entry) =>
    !["CLIENT_SCOPED_DOCUMENTATION_DRAFT", "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT", "MACHINE_SCOPED_DOCUMENTATION_DRAFT"].includes(
      pack.options.find((option) => option.optionId === entry.optionId).basis));
  legacy.optionCount = 20;
  legacy.optionPathCount = 1360;
  legacy.recordedPathCount = 67;
  legacy.omittedPathCount = 1293;
  legacy.proposedAvailabilityCounts.OPTIONAL = 31;
  legacy.recordedFreshnessCounts.CURRENT = 67;
  delete legacy.proposedCompatibilityCounts;
  delete legacy.recordedFamilyCounts;
  assert.equal(validateLegacy(legacy), true, ajv.errorsText(validateLegacy.errors));
  assert.equal(validateLegacy(inventory), false);
  assert.equal(validate(legacy), false);
});
