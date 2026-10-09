import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "zitadel-cloud-free-organization-context-draft-2026.10.08");
const observed = new Date("2026-10-08T14:20:41Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const paths = ["compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
  "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS"];

test("ZITADEL organization context records four unreviewed compatibility proposals, not capabilities", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Free");
  assert.equal(option.product, "ZITADEL Cloud");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), paths);
  assert.deepEqual(draft.options[0].facts, {});
  for (const fact of option.facts) {
    assert.equal(fact.support, "SUPPORTED");
    assert.equal(Object.hasOwn(fact, "availability"), false);
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.equal(new URL(fact.evidence.sourceUrl).hostname, "zitadel.com");
  }
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("ZITADEL grants distinguish home identity, selected tenant access and manager roles", () => {
  const compatibility = draft.options[0].compatibility;
  const membership = compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.conditions.join(" ");
  assert.match(membership, /one home organization.*same identity access/);
  assert.match(membership, /Project grants delegate role management; individual role assignments authorize users/);
  assert.match(membership, /do not merge identities by email or infer native user migration/);
  assert.match(membership, /cached claims and existing local sessions need separate enforcement tests/);
  const tenancy = compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS.conditions.join(" ");
  assert.match(tenancy, /IAM data separation does not prove isolation.*database, APIs or sessions/);
  assert.match(tenancy, /routing alone does not authorize.*selected application tenant/);
  assert.match(tenancy, /do not trust a browser-selected organization ID, email domain or branding/);
  const b2b = compatibility.applications.B2B_SAAS.conditions.join(" ");
  assert.match(b2b, /Business roles require application-side authorization; IAM manager roles are separate/);
  assert.match(b2b, /unlimited organizations within usage quotas.*administrator, API and account limits/);
  assert.match(b2b, /not unlimited usage.*future zero-cost guarantee/);
});

test("ZITADEL partner portal examples do not become public-client, provisioning or security evidence", () => {
  const option = draft.options[0];
  const portal = option.compatibility.applications.PARTNER_PORTAL.conditions.join(" ");
  assert.match(portal, /business admin role must not silently become ORG_OWNER, PROJECT_OWNER or a catalog-curator/);
  assert.match(portal, /server-held client secret and a service-account PAT.*not a public SPA\/mobile compatibility proof/);
  assert.match(portal, /broad grants and development settings are not secure production defaults/);
  assert.deepEqual(option.compatibility.clients, {});
  assert.deepEqual(option.compatibility.populations, {});
  assert.deepEqual(option.residency, {});
  assert.deepEqual(option.authenticationControls, {});
  assert.equal(inspect().options[0].omittedCapabilities.length, 9);
});

test("ZITADEL organization inventory has no capability availability or inherited client contexts", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 0, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 4, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 0, COMPATIBILITY: 4, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  const entry = inventory.options[0];
  assert.equal(entry.recordedPathCount, 4);
  assert.equal(entry.omittedPathCount, 64);
  assert.deepEqual(entry.recordedUnknownPaths, []);
  assert.deepEqual(entry.families[0].recordedPaths, []);
  assert.deepEqual(entry.families[1].recordedPaths, paths);
  for (const path of ["compatibility.clients.BROWSER", "compatibility.populations.EXTERNAL_CUSTOMERS",
    "compatibility.tenancy.SINGLE_ORGANIZATION", "compatibility.membership.SINGLE_ORGANIZATION_PER_USER"]) {
    assert.ok(entry.families[1].omittedPaths.includes(path));
  }
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("ZITADEL inspection rejects single-tenant, multi-home-account, plan/source drift and authority conflation", () => {
  const mutations = [
    (value) => { value.catalogVersion = "zitadel-organization-latest"; },
    (value) => { value.options[0].id = "zitadel-cloud-free-native"; },
    (value) => { value.options[0].providerId = "auth0"; },
    (value) => { value.options[0].product = "ZITADEL self-hosted"; },
    (value) => { value.options[0].plan = "Pro; unlimited verified administration"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "CH; all storage verified"; },
    (value) => { value.options[0].configuration = "Multiple home identity records; implicit email/domain authorization"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.support = "UNKNOWN"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.support = "UNSUPPORTED"; },
    (value) => { delete value.options[0].compatibility.applications.PARTNER_PORTAL; },
    (value) => { value.options[0].compatibility.tenancy.SINGLE_ORGANIZATION = structuredClone(value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS); },
    (value) => { value.options[0].compatibility.membership.SINGLE_ORGANIZATION_PER_USER = structuredClone(value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER); },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.applications.PARTNER_PORTAL); },
    (value) => { value.options[0].compatibility.populations.PARTNERS = structuredClone(value.options[0].compatibility.applications.PARTNER_PORTAL); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.sourceUrl = "https://zitadel.com/pricing"; },
    (value) => { value.options[0].compatibility.applications.PARTNER_PORTAL.evidence.sourceUrl = "https://zitadel.com/docs/guides/integrate/login/oidc/login-users"; },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS.evidence.sourceUrl = "https://zitadel.com/docs/guides/manage/console/users-overview"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.evidence.sourceUrl = "https://zitadel.com/docs/guides/manage/console/organizations-overview"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.applications.PARTNER_PORTAL; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.observedAt = "2026-02-30T14:20:41Z"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.OIDC = { availability: "OPTIONAL", conditions: ["Inherited login"], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EXTERNAL_CUSTOMERS: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence } } }; },
    (value) => { value.sourceRelease = "verified-cloud-release"; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("ZITADEL organization freshness and ordering preserve evidence without approval", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T14:20:41Z"));
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

test("ZITADEL organization context coexists with unchanged protocol, public-client and upstream scopes", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "zitadel" && entry !== draft);
  const before = JSON.stringify(originals);
  const report = await inspectBaselinePack(observed);
  const zitadel = report.options.filter((option) => option.providerId === "zitadel");
  assert.equal(zitadel.length, 9);
  assert.equal(zitadel.reduce((count, option) => count + option.facts.length, 0), 32);
  const client = zitadel.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.deepEqual(client.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  const native = originals.find((entry) => entry.catalogVersion === "zitadel-cloud-free-native-draft-2026.10.02");
  assert.equal(native.options[0].facts.SCIM.availability, "UNKNOWN");
  assert.equal(native.options[0].facts.GROUP_SYNC.availability, "UNAVAILABLE");
  assert.equal(native.options[0].facts.OIDC.evidence.observedAt, "2026-10-02T21:44:10Z");
  assert.ok(zitadel.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(JSON.stringify(originals), before);
});
