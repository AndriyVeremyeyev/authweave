import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "entra-external-id-basic-organization-context-draft-2026.10.08");
const observed = new Date("2026-10-08T16:45:49Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const sourceRoot = "https://learn.microsoft.com/en-us/entra/";
const sources = {
  "compatibility.applications.B2B_SAAS": "external-id/customers/overview-customers-ciam",
  "compatibility.applications.PARTNER_PORTAL": "external-id/customers/how-to-manage-admin-accounts",
  "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER": "external-id/customers/reference-group-app-roles-support",
  "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS": "external-id/tenant-configurations",
};
const paths = Object.keys(sources);
const unknownPaths = paths.filter((path) => path !== "compatibility.applications.B2B_SAAS");

test("Entra organization context separates a business-customer application proposal from three unknown boundaries", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Basic MAU");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(option.product, "Microsoft Entra External ID - external tenant");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), paths);
  assert.deepEqual(draft.options[0].facts, {});
  for (const fact of option.facts) {
    assert.equal(fact.support, fact.path === "compatibility.applications.B2B_SAAS" ? "SUPPORTED" : "UNKNOWN");
    assert.equal(Object.hasOwn(fact, "availability"), false);
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.equal(fact.evidence.sourceUrl, sourceRoot + sources[fact.path]);
  }
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Entra business-customer login does not imply a native organization model, workforce access or free operation", () => {
  const conditions = draft.options[0].compatibility.applications.B2B_SAAS.conditions.join(" ");
  assert.match(conditions, /standard external tenant.*customer sign-up\/sign-in user flows/);
  assert.match(conditions, /SUPPORTED is application-type context, not native customer-organization membership/);
  assert.match(conditions, /separate from a workforce tenant/);
  assert.match(conditions, /application-owned customer organization IDs, admissions and resource permissions/);
  assert.match(conditions, /not verified account entitlement.*zero-cost guarantee/);
  assert.match(conditions, /mutable and dated, not release-pinned/);
  assert.match(conditions, /does not inherit OIDC\/SAML, public-client, workforce-broker, SCIM/);
});

test("Entra administrative guest invitations cannot silently become customer partner admission", () => {
  const fact = draft.options[0].compatibility.applications.PARTNER_PORTAL;
  assert.equal(fact.support, "UNKNOWN");
  const conditions = fact.conditions.join(" ");
  assert.match(conditions, /Invite external user preview is for administration, not customer sign-in/);
  assert.match(conditions, /incompatible with customer user flows/);
  assert.match(conditions, /general table.*mentions invitations.*restrict this path/);
  assert.match(conditions, /UNKNOWN remains until an application-owned partner admission/);
  assert.match(conditions, /does not mean every possible custom portal or invitation implementation is unsupported/);
  assert.match(conditions, /not an Entra directory administrator or AuthWeave catalog-curator authority/);
  assert.match(conditions, /No directory roles, Graph grants, invitation emails/);
});

test("Entra directory tenancy and single-directory registration are not customer resource boundaries", () => {
  const fact = draft.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS;
  assert.equal(fact.support, "UNKNOWN");
  const conditions = fact.conditions.join(" ");
  assert.match(conditions, /external identity directory is not one SaaS customer organization/);
  assert.match(conditions, /single-directory accounts.*not evidence that application multi-tenancy is unsupported/);
  assert.match(conditions, /tid identifies the sign-in directory, not an application customer organization/);
  assert.match(conditions, /generic common-endpoint examples are not the selected CIAM authority/);
  assert.match(conditions, /Reject missing, foreign or unknown context/);
  assert.match(conditions, /failed switches without retaining old tenant permissions/);
  assert.match(conditions, /no live tenant isolation or database enforcement was verified/);
});

test("Entra groups and app roles preserve unknown organization memberships and unselected Graph paths", () => {
  const conditions = draft.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.conditions.join(" ");
  assert.match(conditions, /UNKNOWN is not absence of all group or membership APIs/);
  assert.match(conditions, /roles are application-specific and groups are directory-scoped/);
  assert.match(conditions, /several security groups.*several business organizations/);
  assert.match(conditions, /Microsoft Graph, while the RBAC guide shows admin-center procedures/);
  assert.match(conditions, /no Graph bridge, permissions grant or verified group-claim completeness/);
  assert.match(conditions, /not email or idp-based cross-tenant identity linking/);
  assert.match(conditions, /app-specific sub values are not interchangeable/);
  assert.match(conditions, /cached JWT\/local-session enforcement separately/);
  assert.deepEqual(draft.options[0].compatibility.clients, {});
  assert.deepEqual(draft.options[0].compatibility.populations, {});
  assert.deepEqual(draft.options[0].residency, {});
  assert.deepEqual(draft.options[0].authenticationControls, {});
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 0, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 1, UNSUPPORTED: 0, UNKNOWN: 3 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 0, COMPATIBILITY: 4, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  assert.equal(inventory.options[0].recordedPathCount, 4);
  assert.equal(inventory.options[0].omittedPathCount, 64);
  assert.deepEqual(inventory.options[0].recordedUnknownPaths, unknownPaths);
  assert.deepEqual(inventory.options[0].families[1].recordedPaths, paths);
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("Entra inspection rejects scope, source, uncertainty promotion and authority conflation", () => {
  const mutations = [
    (value) => { value.catalogVersion = "entra-organizations-latest"; },
    (value) => { value.options[0].id = "entra-external-id-basic-standard-native"; },
    (value) => { value.options[0].providerId = "auth0"; },
    (value) => { value.options[0].product = "Microsoft Entra ID workforce"; },
    (value) => { value.options[0].plan = "Trial; all organization features free"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "CH; all storage verified"; },
    (value) => { value.options[0].configuration = "Workforce guests; any-directory app; cross-tenant sync"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.support = "UNKNOWN"; },
    (value) => { value.options[0].compatibility.applications.PARTNER_PORTAL.support = "SUPPORTED"; },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS.support = "UNSUPPORTED"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.support = "SUPPORTED"; },
    (value) => { delete value.options[0].compatibility.applications.PARTNER_PORTAL; },
    (value) => { value.options[0].compatibility.tenancy.SINGLE_ORGANIZATION = structuredClone(value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS); },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.applications.B2B_SAAS); },
    (value) => { value.options[0].compatibility.populations.EXTERNAL_CUSTOMERS = structuredClone(value.options[0].compatibility.applications.B2B_SAAS); },
    (value) => { value.options[0].compatibility.applications.PARTNER_PORTAL.evidence.sourceUrl = sourceRoot + "external-id/what-is-b2b"; },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS.evidence.sourceUrl = sourceRoot + "identity-platform/howto-convert-app-to-be-multi-tenant"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.evidence.sourceUrl = sourceRoot + "external-id/customers/overview-customers-ciam"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.applications.PARTNER_PORTAL; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.observedAt = "2026-02-30T16:45:49Z"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.SCIM = { availability: "OPTIONAL", conditions: ["Borrowed provisioning"], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EXTERNAL_CUSTOMERS: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].compatibility.applications.B2B_SAAS.evidence } } }; },
    (value) => { value.sourceRelease = "verified-cloud-release"; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const mutate of mutations) {
    const value = structuredClone(draft);
    mutate(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("Entra organization freshness and ordering never turn unknown boundaries into support", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, unknownPaths);
    assert.deepEqual(report.schemaPathInventory.proposedCompatibilityCounts, { SUPPORTED: 1, UNSUPPORTED: 0, UNKNOWN: 3 });
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T16:45:49Z"));
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

test("Entra organization context coexists with research, native protocols, public clients and workforce brokers", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "entra-external-id" && entry !== draft);
  const before = JSON.stringify(originals);
  const entra = (await inspectBaselinePack(observed)).options.filter((option) => option.providerId === "entra-external-id");
  assert.equal(entra.length, 7);
  assert.equal(entra.reduce((count, option) => count + option.facts.length, 0), 24);
  const native = entra.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").availability, "UNKNOWN");
  assert.equal(native.facts.find((fact) => fact.path === "facts.GROUP_SYNC").availability, "UNKNOWN");
  assert.equal(native.facts.find((fact) => fact.path === "facts.OIDC").evidence.observedAt, "2026-10-02T23:12:01Z");
  const publicClient = entra.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.deepEqual(publicClient.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.ok(entra.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(JSON.stringify(originals), before);
});
