import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "keycloak-26.8.0-organization-context-draft-2026.10.08");
const observed = new Date("2026-10-08T16:26:23Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const commit = "4246609cf2024c85016d3fb1254c3d2533367c31";
const sourceRoot = `https://github.com/keycloak/keycloak/blob/${commit}/docs/documentation/server_admin/topics/organizations/`;
const sources = {
  "compatibility.applications.B2B_SAAS": "intro.adoc",
  "compatibility.applications.PARTNER_PORTAL": "managing-members.adoc",
  "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER": "managing-members.adoc",
  "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS": "mapping-organization-claims.adoc",
};
const paths = Object.keys(sources);

test("Keycloak organization context pins four typed proposals to the selected upstream release", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourceRelease, "26.8.0");
  assert.equal(option.sourceCommit, commit);
  assert.equal(Object.hasOwn(option, "sourcePlan"), false);
  assert.equal(option.product, "Keycloak upstream 26.8.0");
  assert.equal(option.deployment, "SELF_HOSTED");
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), paths);
  assert.deepEqual(draft.options[0].facts, {});
  for (const fact of option.facts) {
    assert.equal(fact.support, "SUPPORTED");
    assert.equal(Object.hasOwn(fact, "availability"), false);
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.equal(fact.evidence.sourceUrl, sourceRoot + sources[fact.path]);
  }
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Keycloak realm organizations do not become separate issuers, app isolation or hosting entitlement", () => {
  const conditions = draft.options[0].compatibility.applications.B2B_SAAS.conditions.join(" ");
  assert.match(conditions, /one realm with Organizations enabled/);
  assert.match(conditions, /not a realm-per-customer architecture, independent issuers/);
  assert.match(conditions, /identity-first routing or an email domain is not authorization/);
  assert.match(conditions, /hosting capacity, operating cost or commercial support/);
  assert.match(conditions, /SCIM, group synchronization, controls and residency are not inherited/);
});

test("Keycloak partner invitations retain acceptance, audit and guide versus API caveats", () => {
  const conditions = draft.options[0].compatibility.applications.PARTNER_PORTAL.conditions.join(" ");
  assert.match(conditions, /Existing users confirm joining; a new account must use the invited email address/);
  assert.match(conditions, /pending or expired invitation is not membership/);
  assert.match(conditions, /Accepted invitations are deleted.*not a durable acceptance audit/);
  assert.match(conditions, /absent URL returns to the Account Console/);
  assert.match(conditions, /API examples use \/orgs, while release RealmAdminResource routes organizations/);
  assert.match(conditions, /not unrestricted Keycloak realm administration or AuthWeave catalog-curator authority/);
  assert.match(conditions, /no emails, invitations, administrative writes/);
});

test("Keycloak organization scope sets cannot silently become one authorized resource tenant", () => {
  const conditions = draft.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS.conditions.join(" ");
  assert.match(conditions, /Organization id and attributes are omitted by default/);
  assert.match(conditions, /explicitly enable Add organization id/);
  assert.match(conditions, /organization:\* includes all memberships/);
  assert.match(conditions, /mixed scope formats are rejected/);
  assert.match(conditions, /multi-organization claim is not a single active tenant/);
  assert.match(conditions, /Reject missing, foreign or unknown organization context/);
  assert.match(conditions, /failed switches without retaining old tenant permissions/);
  assert.match(conditions, /claims do not prove database isolation/);
});

test("Keycloak shared identities keep managed deletion and disabled-organization lifecycle distinct", () => {
  const conditions = draft.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.conditions.join(" ");
  assert.match(conditions, /one realm user identifier.*one or more organizations/);
  assert.match(conditions, /Select unmanaged memberships/);
  assert.match(conditions, /removing a managed membership or organization deletes the managed realm account/);
  assert.match(conditions, /Changing unmanaged to managed changes lifecycle ownership/);
  assert.match(conditions, /not a harmless retry/);
  assert.match(conditions, /Disabled organizations do not necessarily disable unmanaged realm users/);
  assert.match(conditions, /LDAP users with import mode disabled cannot join organizations/);
  assert.match(conditions, /not SCIM or external group synchronization/);
  assert.deepEqual(draft.options[0].compatibility.clients, {});
  assert.deepEqual(draft.options[0].compatibility.populations, {});
  assert.deepEqual(draft.options[0].residency, {});
  assert.deepEqual(draft.options[0].authenticationControls, {});
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 0, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 4, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 0, COMPATIBILITY: 4, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  assert.equal(inventory.options[0].recordedPathCount, 4);
  assert.equal(inventory.options[0].omittedPathCount, 64);
  assert.deepEqual(inventory.options[0].recordedUnknownPaths, []);
  assert.deepEqual(inventory.options[0].families[1].recordedPaths, paths);
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("Keycloak organization inspection rejects release, source, scope and authority drift", () => {
  const mutations = [
    (value) => { value.catalogVersion = "keycloak-latest-organizations"; },
    (value) => { value.options[0].id = "keycloak-26.8.0-native-self-hosted"; },
    (value) => { value.options[0].providerId = "zitadel"; },
    (value) => { value.options[0].product = "Red Hat build of Keycloak 26.8.0"; },
    (value) => { value.options[0].plan = "Free managed hosting"; },
    (value) => { value.options[0].deployment = "MANAGED"; },
    (value) => { value.options[0].region = "CH; all storage verified"; },
    (value) => { value.options[0].configuration = "Realm per customer; managed memberships; native SCIM"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.support = "UNKNOWN"; },
    (value) => { delete value.options[0].compatibility.applications.PARTNER_PORTAL; },
    (value) => { value.options[0].compatibility.tenancy.SINGLE_ORGANIZATION = structuredClone(value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS); },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.applications.B2B_SAAS); },
    (value) => { value.options[0].compatibility.populations.EXTERNAL_CUSTOMERS = structuredClone(value.options[0].compatibility.applications.B2B_SAAS); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.sourceUrl = sourceRoot.replace(commit, "main") + "intro.adoc"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.sourceUrl = sourceRoot.replace(commit, "26.8.0") + "intro.adoc"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.evidence.sourceUrl = sourceRoot + "mapping-organization-claims.adoc"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.applications.PARTNER_PORTAL; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.observedAt = "2026-02-30T16:26:23Z"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.SCIM = { availability: "OPTIONAL", conditions: ["Borrowed provisioning"], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EXTERNAL_CUSTOMERS: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence } } }; },
    (value) => { value.sourceCommit = commit; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const mutate of mutations) {
    const value = structuredClone(draft);
    mutate(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("Keycloak organization evidence dates and ordered output never imply review or rewrite sources", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T16:26:23Z"));
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  const reordered = structuredClone(draft);
  for (const group of Object.values(reordered.options[0].compatibility)) {
    for (const fact of Object.values(group)) fact.conditions.reverse();
  }
  reordered.options[0].compatibility.applications = Object.fromEntries(Object.entries(reordered.options[0].compatibility.applications).reverse());
  reordered.options[0].compatibility = Object.fromEntries(Object.entries(reordered.options[0].compatibility).reverse());
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(draft), before);
});

test("Keycloak organization context coexists with research, native SCIM, public clients and workforce brokers", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "keycloak" && entry !== draft);
  const before = JSON.stringify(originals);
  const keycloak = (await inspectBaselinePack(observed)).options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 9);
  assert.equal(keycloak.reduce((count, option) => count + option.facts.length, 0), 37);
  const native = keycloak.find((option) => option.basis === "RELEASE_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(native.facts.filter(fact => fact.path.startsWith("facts.")).every(fact => fact.availability === "OPTIONAL"));
  assert.deepEqual(native.facts.filter(fact => fact.path.startsWith("compatibility.")).map(fact => fact.path),
    ["compatibility.applications.B2B_SAAS", "compatibility.clients.BROWSER", "compatibility.membership.SINGLE_ORGANIZATION_PER_USER", "compatibility.populations.EXTERNAL_CUSTOMERS", "compatibility.tenancy.SINGLE_ORGANIZATION"]);
  assert.ok(!native.facts.some(fact => ["compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS", "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER"].includes(fact.path)));
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T21:20:39Z");
  const publicClient = keycloak.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.deepEqual(publicClient.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.ok(keycloak.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(JSON.stringify(originals), before);
});
