import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "workos-authkit-staging-organization-context-draft-2026.10.08");
const observed = new Date("2026-10-08T16:06:12Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const paths = ["compatibility.applications.B2B_SAAS", "compatibility.applications.PARTNER_PORTAL",
  "compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER", "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS"];

test("WorkOS organization context records four unreviewed compatibility proposals, not capabilities", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Staging");
  assert.equal(option.product, "WorkOS AuthKit");
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
    assert.equal(new URL(fact.evidence.sourceUrl).hostname, "workos.com");
  }
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("WorkOS active memberships do not become identity linking, complete audit reads or verified lifecycle enforcement", () => {
  const membership = draft.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER;
  const conditions = membership.conditions.join(" ");
  assert.match(conditions, /stable environment-scoped WorkOS user_id/);
  assert.match(conditions, /zero, one or many memberships/);
  assert.match(conditions, /Do not use email alone.*across connections or staging\/production/);
  assert.match(conditions, /pending invitations and inactive memberships are not active access/);
  assert.match(conditions, /Listing defaults to active memberships.*statuses and pagination/);
  assert.match(conditions, /Create can reactivate an inactive membership; reactivate retains prior roles/);
  assert.match(conditions, /cached JWTs and application sessions need separate enforcement tests/);
  assert.equal(membership.evidence.sourceUrl, "https://workos.com/docs/reference/authkit/organization-membership");
  const b2b = draft.options[0].compatibility.applications.B2B_SAAS.conditions.join(" ");
  assert.match(b2b, /staging is testing-only, not customer-facing production/);
  assert.match(b2b, /environment-specific.*production requires separate setup and billing review/);
  assert.match(b2b, /not free production SSO or Directory Sync/);
  assert.match(b2b, /does not inherit Connect OIDC\/SPA claims/);
});

test("WorkOS partner invitation and session details preserve admission, management and key-source boundaries", () => {
  const option = draft.options[0];
  const portal = option.compatibility.applications.PARTNER_PORTAL.conditions.join(" ");
  assert.match(portal, /application-wide invitation.*is not a partner membership/);
  assert.match(portal, /corporate-domain invitations can be accepted by another address on the same domain/);
  assert.match(portal, /Do not assume exact-recipient approval/);
  assert.match(portal, /not WorkOS workspace administration, an unrestricted API key or AuthWeave catalog-curator authority/);
  const tenancy = option.compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS.conditions.join(" ");
  assert.match(tenancy, /reject missing, unknown or mismatched organization context/);
  assert.match(tenancy, /handle failures without retaining the previous tenant's permissions/);
  assert.match(tenancy, /claims do not prove database isolation/);
  assert.match(tenancy, /HTTP JWKS example and issuer spelling.*HTTPS\/client-specific reference/);
  assert.match(tenancy, /Do not borrow Connect keys or token semantics/);
  assert.deepEqual(option.compatibility.clients, {});
  assert.deepEqual(option.compatibility.populations, {});
  assert.deepEqual(option.residency, {});
  assert.deepEqual(option.authenticationControls, {});
  assert.equal(inspect().options[0].omittedCapabilities.length, 9);
});

test("WorkOS organization inventory has no capability availability or inherited client contexts", () => {
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

test("WorkOS inspection rejects scope, implicit-admission, plan/source drift and authority conflation", () => {
  const mutations = [
    (value) => { value.catalogVersion = "workos-organization-latest"; },
    (value) => { value.options[0].id = "workos-directory-sync-staging-scim-events"; },
    (value) => { value.options[0].providerId = "zitadel"; },
    (value) => { value.options[0].product = "WorkOS AuthKit Connect"; },
    (value) => { value.options[0].plan = "Production; all enterprise connections free"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "CH; all storage verified"; },
    (value) => { value.options[0].configuration = "Connect OAuth app; implicit domain admission; Directory Sync"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.support = "UNKNOWN"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.support = "UNSUPPORTED"; },
    (value) => { delete value.options[0].compatibility.applications.PARTNER_PORTAL; },
    (value) => { value.options[0].compatibility.tenancy.SINGLE_ORGANIZATION = structuredClone(value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS); },
    (value) => { value.options[0].compatibility.membership.SINGLE_ORGANIZATION_PER_USER = structuredClone(value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER); },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.applications.PARTNER_PORTAL); },
    (value) => { value.options[0].compatibility.populations.PARTNERS = structuredClone(value.options[0].compatibility.applications.PARTNER_PORTAL); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.sourceUrl = "https://workos.com/pricing"; },
    (value) => { value.options[0].compatibility.applications.PARTNER_PORTAL.evidence.sourceUrl = "https://workos.com/docs/authkit/sessions"; },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS.evidence.sourceUrl = "https://workos.com/docs/authkit/invitations"; },
    (value) => { value.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER.evidence.sourceUrl = "https://workos.com/docs/authkit/users-organizations"; },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.applications.PARTNER_PORTAL; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS.evidence.observedAt = "2026-02-30T16:06:12Z"; },
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

test("WorkOS organization freshness and ordering preserve evidence without approval", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T16:06:12Z"));
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

test("WorkOS primary organization context coexists with research, Connect and Directory Sync without borrowing facts", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "workos" && entry !== draft);
  const before = JSON.stringify(originals);
  const report = await inspectBaselinePack(observed);
  const workos = report.options.filter((option) => option.providerId === "workos");
  assert.equal(workos.length, 6);
  assert.equal(workos.reduce((count, option) => count + option.facts.length, 0), 16);
  const connect = workos.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(connect.product, "WorkOS AuthKit Connect");
  assert.deepEqual(connect.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.equal(connect.facts.find((fact) => fact.path === "compatibility.clients.BROWSER").support, "UNKNOWN");
  const directory = originals.find((entry) => entry.catalogVersion === "workos-directory-sync-staging-draft-2026.10.02");
  assert.equal(directory.options[0].facts.SCIM.availability, "OPTIONAL");
  assert.equal(directory.options[0].facts.GROUP_SYNC.availability, "OPTIONAL");
  assert.equal(Object.hasOwn(directory.options[0].facts, "OIDC"), false);
  assert.ok(workos.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => option.facts.every((fact) => fact.path.startsWith("facts.") && fact.availability === "OPTIONAL")));
  assert.equal(JSON.stringify(originals), before);
});
