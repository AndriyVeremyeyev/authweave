import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  inspectBaselineDrafts, inspectBaselinePack, inspectScopedBaselineDraft,
  readBaselineDrafts, readScopedBaselineDraft, readScopedBaselineDrafts,
} from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readBaselineDrafts();
const scopedDraft = await readScopedBaselineDraft();
const scopedDrafts = await readScopedBaselineDrafts();
const zitadelDraft = scopedDrafts.find((draft) => draft.options[0].providerId === "zitadel");
const auth0Draft = scopedDrafts.find((draft) => draft.options[0].providerId === "auth0");
const workosDraft = scopedDrafts.find((draft) => draft.options[0].providerId === "workos");
const entraDraft = scopedDrafts.find((draft) => draft.options[0].providerId === "entra-external-id");
const upstreamDrafts = scopedDrafts.filter((draft) => draft.catalogVersion.startsWith("auth0-b2b-free-upstream-"));
const zitadelUpstreamDrafts = scopedDrafts.filter((draft) => draft.catalogVersion.startsWith("zitadel-cloud-free-upstream-"));
const keycloakUpstreamDrafts = scopedDrafts.filter((draft) => draft.catalogVersion.startsWith("keycloak-26.8.0-upstream-"));
const workosUpstreamDrafts = scopedDrafts.filter((draft) => draft.catalogVersion.startsWith("workos-directory-sync-staging-upstream-"));
const entraUpstreamDrafts = scopedDrafts.filter((draft) => draft.catalogVersion.startsWith("entra-external-id-basic-upstream-"));
const observedAt = new Date("2026-10-02T19:58:06Z");
const copy = () => structuredClone(drafts);

function assertUntrusted(report) {
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false, flag);
  }
  for (const option of report.options) for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
  }
}

test("five real-provider research drafts remain partial, unknown and unreviewed", () => {
  const report = inspectBaselineDrafts(drafts, observedAt);
  assertUntrusted(report);
  assert.equal(report.optionCount, 5);
  assert.equal(report.factCount, 15);
  assert.deepEqual(report.options.map((option) => option.providerId), ["auth0", "entra-external-id", "keycloak", "workos", "zitadel"]);
  for (const option of report.options) {
    assert.equal(option.omittedCapabilities.length, 6);
    assert.deepEqual(option.facts.map((fact) => fact.path), ["facts.GROUP_SYNC", "facts.OIDC", "facts.SCIM"]);
    assert.ok(option.deferredDimensions.includes("RESIDENCY"));
    assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
    assert.ok(option.facts.every((fact) => fact.availability === "UNKNOWN"));
  }
  assert.deepEqual(inspectBaselineDrafts(drafts.toReversed(), observedAt), report);
});

test("freshness has an inclusive 90-day boundary without refreshing observations or trust", () => {
  const before = JSON.stringify(drafts);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectBaselineDrafts(drafts, new Date(observedAt.getTime() + offset));
    assertUntrusted(report);
    assert.ok(report.options.every((option) => option.facts.every((fact) => fact.freshness === freshness)));
    assert.ok(report.options.every((option) => option.facts.every((fact) => fact.evidence.observedAt === "2026-10-02T19:58:06Z")));
  }
  assert.equal(JSON.stringify(drafts), before);
  assert.throws(() => inspectBaselineDrafts(drafts, new Date("invalid")), /Invalid inspection time/);
});

test("the pack preserves provisioning directions and provider-specific unknowns", () => {
  const entry = (provider, capability) => drafts.find((draft) => draft.options[0].providerId === provider).options[0].facts[capability];
  assert.match(entry("entra-external-id", "SCIM").conditions.join(" "), /outbound.*inbound/);
  assert.match(entry("auth0", "SCIM").conditions.join(" "), /Enterprise Connections/);
  assert.match(entry("workos", "SCIM").conditions.join(" "), /read-only/);
  assert.match(entry("zitadel", "GROUP_SYNC").evidence.summary, /excludes Group provisioning/);
  assert.match(entry("keycloak", "SCIM").evidence.summary, /unresolved, not unavailable/);
});

test("a release-scoped Keycloak draft proposes optional capabilities without review or runtime verification", () => {
  const at = new Date("2026-10-02T21:20:39Z");
  const before = JSON.stringify(scopedDraft);
  const report = inspectScopedBaselineDraft(scopedDraft, at);
  assertUntrusted(report);
  assert.equal(report.optionCount, 1);
  assert.equal(report.factCount, 4);
  const option = report.options[0];
  assert.equal(option.basis, "RELEASE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourceRelease, "26.8.0");
  assert.equal(option.sourceCommit, "4246609cf2024c85016d3fb1254c3d2533367c31");
  assert.ok(option.facts.every((fact) => fact.availability === "OPTIONAL" && fact.freshness === "CURRENT"));
  assert.equal(option.omittedCapabilities.length, 5);
  for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const later = inspectScopedBaselineDraft(scopedDraft, new Date(at.getTime() + offset));
    assertUntrusted(later);
    assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness));
  }
  assert.equal(JSON.stringify(scopedDraft), before);
  const claims = scopedDraft.options[0].facts;
  assert.match(claims.SCIM.conditions.join(" "), /inbound realm provisioning/);
  assert.match(claims.SCIM.conditions.join(" "), /confidential.*permissions.*audience/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /PATCH.*POST or PUT/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /Organization API/);
});

test("scoped inspection rejects release drift, moving URLs, changed scope and invented coverage", () => {
  const mutations = [
    (input) => { input.approvalGranted = true; },
    (input) => { input.catalogVersion = "keycloak-latest"; },
    (input) => { input.options[0].product = "Keycloak upstream latest"; },
    (input) => { input.options[0].plan = "Managed enterprise"; },
    (input) => { input.options[0].region = "EU"; },
    (input) => { input.options[0].configuration = "SCIM via a third-party extension"; },
    (input) => { input.options[0].deployment = "MANAGED"; },
    (input) => { input.options[0].facts.SCIM.availability = "MANDATORY"; },
    (input) => { input.options[0].facts.SCIM.conditions = []; },
    (input) => { input.options[0].facts.SCIM.conditions.push(input.options[0].facts.SCIM.conditions[0]); },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = input.options[0].facts.SCIM.evidence.sourceUrl.replace("4246609cf2024c85016d3fb1254c3d2533367c31", "main"); },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?reviewed=true"; },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = input.options[0].facts.OIDC.evidence.sourceUrl; },
    (input) => { input.options[0].facts.OIDC.evidence.sourceUrl = "https://www.keycloak.org/docs/latest/server_admin/index.html"; },
    (input) => { delete input.options[0].facts.SAML; },
    (input) => { input.options.push(structuredClone(input.options[0])); },
    (input) => { input.options[0].compatibility.clients.BROWSER = {
      support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.OIDC.evidence),
    }; },
  ];
  for (const mutate of mutations) {
    const input = structuredClone(scopedDraft); mutate(input);
    assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
  }
});

test("Free Cloud scope keeps SCIM uncertainty and native group absence separate from configurable login", () => {
  const at = new Date("2026-10-02T21:44:10Z");
  const before = JSON.stringify(zitadelDraft);
  const report = inspectScopedBaselineDraft(zitadelDraft, at);
  assertUntrusted(report);
  assert.equal(report.factCount, 4);
  const option = report.options[0];
  assert.equal(option.basis, "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Free");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.deepEqual(Object.fromEntries(option.facts.map((fact) => [fact.path, fact.availability])), {
    "facts.GROUP_SYNC": "UNAVAILABLE", "facts.OIDC": "OPTIONAL", "facts.SAML": "OPTIONAL", "facts.SCIM": "UNKNOWN",
  });
  assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
  assert.equal(option.omittedCapabilities.length, 5);
  const claims = zitadelDraft.options[0].facts;
  assert.match(claims.OIDC.conditions.join(" "), /https:\/\/zitadel.com\/pricing.*quotas.*no account entitlement.*zero-cost/);
  assert.match(claims.SCIM.conditions.join(" "), /Preview.*Free-plan access.*Cloud version/);
  assert.match(claims.SCIM.conditions.join(" "), /inbound.*not outbound/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /native inbound SCIM Group.*no external bridge/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /Do not generalize/);
  for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const later = inspectScopedBaselineDraft(zitadelDraft, new Date(at.getTime() + offset));
    assertUntrusted(later);
    assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
      && fact.evidence.observedAt === "2026-10-02T21:44:10Z"));
  }
  assert.equal(JSON.stringify(zitadelDraft), before);
});

test("Cloud inspection rejects paid-plan or lab conflation, fabricated support and source drift", () => {
  const mutations = [
    (input) => { input.approvalGranted = true; },
    (input) => { input.options[0].plan = "Pro"; },
    (input) => { input.options[0].product = "ZITADEL self-hosted 4.17.0"; },
    (input) => { input.options[0].deployment = "SELF_HOSTED"; },
    (input) => { input.options[0].providerId = "keycloak"; },
    (input) => { input.options[0].id = "zitadel-managed-research"; },
    (input) => { input.catalogVersion = scopedDraft.catalogVersion; },
    (input) => { input.options[0].region = "EU"; },
    (input) => { input.options[0].configuration = "SCIM groups via a custom bridge"; },
    (input) => { input.options[0].facts.SCIM.availability = "OPTIONAL"; },
    (input) => { input.options[0].facts.GROUP_SYNC.availability = "OPTIONAL"; },
    (input) => { input.options[0].facts.SCIM.conditions = []; },
    (input) => { input.options[0].facts.SCIM.conditions.push(input.options[0].facts.SCIM.conditions[0]); },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = "https://zitadel.com/pricing"; },
    (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl += "?reviewed=true"; },
    (input) => { input.options[0].facts.OIDC.evidence.sourceUrl = "https://owner@zitadel.com/docs"; },
    (input) => { input.options[0].facts.SCIM.evidence.observedAt = "2026-02-30T21:44:10Z"; },
    (input) => { delete input.options[0].facts.SAML; },
    (input) => { input.options.push(structuredClone(input.options[0])); },
    (input) => { input.options[0].residency.USER_PROFILES = {
      coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: structuredClone(input.options[0].facts.OIDC.evidence),
    }; },
  ];
  for (const mutate of mutations) {
    const input = structuredClone(zitadelDraft); mutate(input);
    assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
  }
});

test("B2B Free Auth0 separates downstream OIDC, upstream federation and inbound SCIM without group promotion", () => {
  const at = new Date("2026-10-02T22:07:48Z");
  const before = JSON.stringify(auth0Draft);
  const report = inspectScopedBaselineDraft(auth0Draft, at);
  assertUntrusted(report);
  assert.equal(report.optionCount, 1);
  assert.equal(report.factCount, 4);
  const option = report.options[0];
  assert.equal(option.basis, "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "B2B Free");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.deepEqual(Object.fromEntries(option.facts.map((fact) => [fact.path, fact.availability])), {
    "facts.ENTERPRISE_SSO": "OPTIONAL", "facts.GROUP_SYNC": "UNKNOWN", "facts.OIDC": "OPTIONAL", "facts.SCIM": "OPTIONAL",
  });
  assert.equal(option.omittedCapabilities.length, 5);
  assert.ok(option.omittedCapabilities.includes("SAML"));
  assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
  const claims = auth0Draft.options[0].facts;
  assert.match(claims.OIDC.conditions.join(" "), /downstream.*upstream/);
  assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /single included Enterprise Connection.*https:\/\/auth0.com\/pricing/);
  assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /not unlimited/);
  assert.match(claims.SCIM.conditions.join(" "), /ID token sub to SCIM externalId/);
  assert.match(claims.SCIM.conditions.join(" "), /inbound.*not native outbound/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /does not distinguish group-specific entitlement/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /user members, not nested groups/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /UNKNOWN must not be read as unsupported/);
  for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const later = inspectScopedBaselineDraft(auth0Draft, new Date(at.getTime() + offset));
    assertUntrusted(later);
    assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
      && fact.evidence.observedAt === "2026-10-02T22:07:48Z"));
  }
  assert.equal(JSON.stringify(auth0Draft), before);
});

test("Auth0 scoped inspection rejects trial/tier/deployment drift, cross-direction sources and fabricated group coverage", () => {
  const mutations = [
    (input) => { input.approvalGranted = true; },
    (input) => { input.options[0].plan = "B2C Essentials"; },
    (input) => { input.options[0].plan = "Enterprise trial"; },
    (input) => { input.options[0].product = "Auth0 Private Cloud"; },
    (input) => { input.options[0].deployment = "SELF_HOSTED"; },
    (input) => { input.options[0].region = "EU"; },
    (input) => { input.options[0].configuration = "Unlimited OIDC connections and outbound SCIM bridge"; },
    (input) => { input.options[0].providerId = "zitadel"; },
    (input) => { input.options[0].id = "auth0-managed-research"; },
    (input) => { input.catalogVersion = zitadelDraft.catalogVersion; },
    (input) => { input.options[0].facts.GROUP_SYNC.availability = "OPTIONAL"; },
    (input) => { input.options[0].facts.GROUP_SYNC.availability = "UNAVAILABLE"; },
    (input) => { input.options[0].facts.SCIM.availability = "MANDATORY"; },
    (input) => { input.options[0].facts.SCIM.conditions = []; },
    (input) => { input.options[0].facts.SCIM.conditions.push(input.options[0].facts.SCIM.conditions[0]); },
    (input) => { input.options[0].facts.OIDC.evidence.sourceUrl = input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl; },
    (input) => { input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl = input.options[0].facts.OIDC.evidence.sourceUrl; },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = "https://auth0.com/pricing"; },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?reviewed=true"; },
    (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl = "https://auth0.com.attacker.invalid/docs"; },
    (input) => { input.options[0].facts.SCIM.evidence.observedAt = "2026-02-30T22:07:48Z"; },
    (input) => { delete input.options[0].facts.ENTERPRISE_SSO; },
    (input) => { input.options.push(structuredClone(input.options[0])); },
    (input) => { input.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER = {
      support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.OIDC.evidence),
    }; },
  ];
  for (const mutate of mutations) {
    const input = structuredClone(auth0Draft); mutate(input);
    assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
  }
});

test("staging Directory Sync proposes a SCIM/event bridge, not login or free production enforcement", () => {
  const at = new Date("2026-10-02T22:46:13Z");
  const before = JSON.stringify(workosDraft);
  const report = inspectScopedBaselineDraft(workosDraft, at);
  assertUntrusted(report);
  assert.equal(report.optionCount, 1);
  assert.equal(report.factCount, 2);
  const option = report.options[0];
  assert.equal(option.basis, "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Staging");
  assert.equal(option.product, "WorkOS Directory Sync");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.deepEqual(Object.fromEntries(option.facts.map((fact) => [fact.path, fact.availability])), {
    "facts.GROUP_SYNC": "OPTIONAL", "facts.SCIM": "OPTIONAL",
  });
  assert.equal(option.omittedCapabilities.length, 7);
  assert.ok(["OIDC", "SAML", "ENTERPRISE_SSO"].every((capability) => option.omittedCapabilities.includes(capability)));
  assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
  const claims = workosDraft.options[0].facts;
  assert.match(claims.SCIM.conditions.join(" "), /Staging.*not billed.*production.*per-connection/);
  assert.match(claims.SCIM.conditions.join(" "), /not free production Directory Sync/);
  assert.match(claims.SCIM.conditions.join(" "), /read-only.*not a native SCIM endpoint in the SaaS or write-back/);
  assert.match(claims.SCIM.conditions.join(" "), /persist an Events API cursor.*replay.*reconcile/);
  assert.match(claims.SCIM.conditions.join(" "), /not necessarily deleted from the SaaS/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /does not emit individual dsync.group.user_removed/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /without assuming every member's account must be deleted/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /updated_at does not change.*deprecated Directory User groups field/);
  for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const later = inspectScopedBaselineDraft(workosDraft, new Date(at.getTime() + offset));
    assertUntrusted(later);
    assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
      && fact.evidence.observedAt === "2026-10-02T22:46:13Z"));
  }
  assert.equal(JSON.stringify(workosDraft), before);
});

test("WorkOS inspection rejects production/AuthKit conflation, connector drift and invented login or coverage", () => {
  const mutations = [
    (input) => { input.approvalGranted = true; },
    (input) => { input.options[0].plan = "Production pay-as-you-go"; },
    (input) => { input.options[0].plan = "AuthKit Free up to 1M users"; },
    (input) => { input.options[0].product = "WorkOS AuthKit Connect"; },
    (input) => { input.options[0].deployment = "SELF_HOSTED"; },
    (input) => { input.options[0].region = "EU"; },
    (input) => { input.options[0].configuration = "Native SaaS SCIM endpoint with upstream write-back"; },
    (input) => { input.options[0].configuration = "Google Workspace pull connector with OAuth authentication"; },
    (input) => { input.options[0].providerId = "auth0"; },
    (input) => { input.options[0].id = "workos-managed-research"; },
    (input) => { input.catalogVersion = auth0Draft.catalogVersion; },
    (input) => { input.options[0].facts.SCIM.availability = "MANDATORY"; },
    (input) => { input.options[0].facts.GROUP_SYNC.availability = "UNAVAILABLE"; },
    (input) => { input.options[0].facts.SCIM.conditions = []; },
    (input) => { input.options[0].facts.SCIM.conditions.push(input.options[0].facts.SCIM.conditions[0]); },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = "https://workos.com/pricing"; },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = "https://workos.com/docs/integrations/google-workspace"; },
    (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl = input.options[0].facts.SCIM.evidence.sourceUrl; },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?reviewed=true"; },
    (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl = "https://workos.com.attacker.invalid/docs"; },
    (input) => { input.options[0].facts.SCIM.evidence.observedAt = "2026-02-30T22:46:13Z"; },
    (input) => { input.options[0].facts.OIDC = structuredClone(input.options[0].facts.SCIM); },
    (input) => { delete input.options[0].facts.GROUP_SYNC; },
    (input) => { input.options.push(structuredClone(input.options[0])); },
    (input) => { input.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER = {
      support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.SCIM.evidence),
    }; },
    (input) => { input.options[0].residency.USER_PROFILES = {
      coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: structuredClone(input.options[0].facts.SCIM.evidence),
    }; },
  ];
  for (const mutate of mutations) {
    const input = structuredClone(workosDraft); mutate(input);
    assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
  }
});

test("Basic external-tenant login does not inherit paid inbound SCIM or Graph group lifecycle", () => {
  const at = new Date("2026-10-02T23:12:01Z");
  const before = JSON.stringify(entraDraft);
  const report = inspectScopedBaselineDraft(entraDraft, at);
  assertUntrusted(report);
  assert.equal(report.optionCount, 1);
  assert.equal(report.factCount, 4);
  const option = report.options[0];
  assert.equal(option.basis, "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Basic MAU");
  assert.equal(option.product, "Microsoft Entra External ID - external tenant");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.deepEqual(Object.fromEntries(option.facts.map((fact) => [fact.path, fact.availability])), {
    "facts.GROUP_SYNC": "UNKNOWN", "facts.OIDC": "OPTIONAL", "facts.SAML": "OPTIONAL", "facts.SCIM": "UNKNOWN",
  });
  assert.equal(option.omittedCapabilities.length, 5);
  assert.ok(option.omittedCapabilities.includes("ENTERPRISE_SSO"));
  assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
  const claims = entraDraft.options[0].facts;
  assert.match(claims.OIDC.conditions.join(" "), /50,000 MAUs.*not a zero-cost guarantee/);
  assert.match(claims.OIDC.conditions.join(" "), /single-tenant downstream.*ciamlogin.com.*PKCE/);
  assert.match(claims.OIDC.conditions.join(" "), /not workforce.*legacy Azure AD B2C.*upstream/);
  assert.match(claims.SAML.conditions.join(" "), /current administrator.*actual application/);
  assert.match(claims.SAML.conditions.join(" "), /downstream.*does not establish upstream/);
  assert.match(claims.SCIM.conditions.join(" "), /inbound.*not outbound/);
  assert.match(claims.SCIM.conditions.join(" "), /P1.*paid add-on.*excluded.*applicability.*not established/);
  assert.match(claims.SCIM.conditions.join(" "), /Standard-mode outbound SCIM.*HSC mode, not all external tenants/);
  assert.match(claims.SCIM.conditions.join(" "), /UNKNOWN is not UNAVAILABLE/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /Graph.*not native inbound SCIM Group/);
  for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const later = inspectScopedBaselineDraft(entraDraft, new Date(at.getTime() + offset));
    assertUntrusted(later);
    assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
      && fact.evidence.observedAt === "2026-10-02T23:12:01Z"));
  }
  assert.equal(JSON.stringify(entraDraft), before);
});

test("Entra inspection rejects workforce/B2C/trial conflation, paid API promotion and source drift", () => {
  const mutations = [
    (input) => { input.approvalGranted = true; },
    (input) => { input.options[0].plan = "P1 plus SCIM Provisioning API add-on"; },
    (input) => { input.options[0].plan = "External ID trial"; },
    (input) => { input.options[0].product = "Microsoft Entra workforce tenant"; },
    (input) => { input.options[0].product = "Azure AD B2C"; },
    (input) => { input.options[0].deployment = "SELF_HOSTED"; },
    (input) => { input.options[0].region = "EU"; },
    (input) => { input.options[0].configuration = "HSC mode with outbound SCIM and a Graph bridge"; },
    (input) => { input.options[0].providerId = "auth0"; },
    (input) => { input.options[0].id = "entra-external-id-managed-research"; },
    (input) => { input.catalogVersion = auth0Draft.catalogVersion; },
    (input) => { input.options[0].facts.SCIM.availability = "OPTIONAL"; },
    (input) => { input.options[0].facts.SCIM.availability = "UNAVAILABLE"; },
    (input) => { input.options[0].facts.GROUP_SYNC.availability = "OPTIONAL"; },
    (input) => { input.options[0].facts.GROUP_SYNC.availability = "UNAVAILABLE"; },
    (input) => { input.options[0].facts.SCIM.conditions = []; },
    (input) => { input.options[0].facts.SCIM.conditions.push(input.options[0].facts.SCIM.conditions[0]); },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = input.options[0].facts.OIDC.evidence.sourceUrl; },
    (input) => { input.options[0].facts.SAML.evidence.sourceUrl = input.options[0].facts.OIDC.evidence.sourceUrl; },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?reviewed=true"; },
    (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl = "https://learn.microsoft.com.attacker.invalid/docs"; },
    (input) => { input.options[0].facts.SCIM.evidence.observedAt = "2026-02-30T23:12:01Z"; },
    (input) => { input.options[0].facts.ENTERPRISE_SSO = structuredClone(input.options[0].facts.OIDC); },
    (input) => { delete input.options[0].facts.SAML; },
    (input) => { input.options.push(structuredClone(input.options[0])); },
    (input) => { input.options[0].residency.USER_PROFILES = {
      coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: structuredClone(input.options[0].facts.OIDC.evidence),
    }; },
  ];
  for (const mutate of mutations) {
    const input = structuredClone(entraDraft); mutate(input);
    assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
  }
});

test("upstream workforce pairs keep SSO configurable but provisioning and group entitlements unresolved", () => {
  const at = new Date("2026-10-02T23:25:54Z");
  assert.equal(upstreamDrafts.length, 2);
  for (const draft of upstreamDrafts) {
    const before = JSON.stringify(draft);
    const report = inspectScopedBaselineDraft(draft, at);
    assertUntrusted(report);
    assert.equal(report.optionCount, 1);
    assert.equal(report.factCount, 3);
    const option = report.options[0];
    const isOkta = option.upstreamProviderId === "okta-workforce";
    assert.equal(option.basis, "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
    assert.equal(option.sourcePlan, "B2B Free");
    assert.equal(option.upstreamProviderId, isOkta ? "okta-workforce" : "entra-id-workforce");
    assert.equal(option.product, "Auth0 Public Cloud");
    assert.equal(option.plan, "B2B Free; upstream workforce entitlement unverified");
    assert.equal(Object.hasOwn(option, "sourceRelease"), false);
    assert.equal(Object.hasOwn(option, "sourceCommit"), false);
    assert.deepEqual(Object.fromEntries(option.facts.map((fact) => [fact.path, fact.availability])), {
      "facts.ENTERPRISE_SSO": "OPTIONAL", "facts.GROUP_SYNC": "UNKNOWN", "facts.SCIM": "UNKNOWN",
    });
    assert.equal(option.omittedCapabilities.length, 6);
    assert.ok(["OIDC", "SAML"].every((capability) => option.omittedCapabilities.includes(capability)));
    assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
    const claims = draft.options[0].facts;
    if (isOkta) {
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /Okta connections separately from generic Enterprise/);
      assert.match(claims.SCIM.conditions.join(" "), /separate OIDC and SCIM.*Federation Broker Mode.*externalId/);
      assert.match(claims.SCIM.conditions.join(" "), /without password provisioning.*PUT/);
      assert.match(claims.GROUP_SYNC.conditions.join(" "), /assignment is not Group Push/);
    } else {
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /single-tenant.*workforce/);
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /v2 Identity API.*legacy v1 Graph/);
      assert.match(claims.SCIM.conditions.join(" "), /oid.*Common Endpoint disabled.*externalId.*objectId.*legacy pairwise sub/);
      assert.match(claims.SCIM.conditions.join(" "), /Assignment Required.*separate non-gallery/);
      assert.match(claims.GROUP_SYNC.conditions.join(" "), /OIDC group claims.*do not establish/);
    }
    assert.match(claims.SCIM.conditions.join(" "), /upstream.*entitlement.*unverified/);
    assert.match(claims.GROUP_SYNC.conditions.join(" "), /not nested groups/);
    assert.match(claims.GROUP_SYNC.conditions.join(" "), /no downstream bridge/);
    for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
      const later = inspectScopedBaselineDraft(draft, new Date(at.getTime() + offset));
      assertUntrusted(later);
      assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
        && fact.evidence.observedAt === "2026-10-02T23:25:54Z"));
    }
    assert.equal(JSON.stringify(draft), before);
  }
});

test("upstream inspection rejects mixed connector sources, identity/configuration drift and provisioning promotion", () => {
  for (const draft of upstreamDrafts) {
    const other = upstreamDrafts.find((candidate) => candidate !== draft);
    const mutations = [
      (input) => { input.approvalGranted = true; },
      (input) => { input.upstreamProviderId = "verified-workforce"; },
      (input) => { input.options[0].upstreamProviderId = "verified-workforce"; },
      (input) => { input.options[0].configuration = other.options[0].configuration; },
      (input) => { input.options[0].configuration = "Generic OIDC sub mapping with outbound SaaS SCIM"; },
      (input) => { input.options[0].configuration = "OIN Express or common endpoint multitenant login"; },
      (input) => { input.options[0].plan = "B2B Enterprise; upstream provisioning verified"; },
      (input) => { input.options[0].product = "Microsoft Entra External ID - external tenant"; },
      (input) => { input.options[0].providerId = "entra-external-id"; },
      (input) => { input.options[0].id = other.options[0].id; },
      (input) => { input.options[0].region = "EU"; },
      (input) => { input.options[0].deployment = "SELF_HOSTED"; },
      (input) => { input.catalogVersion = other.catalogVersion; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = other.options[0].facts.SCIM.evidence.sourceUrl; },
      (input) => { input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl = auth0Draft.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?reviewed=true"; },
      (input) => { input.options[0].facts.SCIM.evidence.observedAt = "2026-02-30T23:25:54Z"; },
      (input) => { input.options[0].facts.SCIM.availability = "OPTIONAL"; },
      (input) => { input.options[0].facts.SCIM.availability = "UNAVAILABLE"; },
      (input) => { input.options[0].facts.GROUP_SYNC.availability = "OPTIONAL"; },
      (input) => { input.options[0].facts.SCIM.conditions = []; },
      (input) => { input.options[0].facts.SCIM.conditions.push(input.options[0].facts.SCIM.conditions[0]); },
      (input) => { input.options[0].facts.OIDC = structuredClone(auth0Draft.options[0].facts.OIDC); },
      (input) => { delete input.options[0].facts.ENTERPRISE_SSO; },
      (input) => { input.options.push(structuredClone(input.options[0])); },
      (input) => { input.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER = {
        support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.SCIM.evidence),
      }; },
    ];
    for (const mutate of mutations) {
      const input = structuredClone(draft); mutate(input);
      assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
    }
  }
});

test("ZITADEL workforce pairs separate org-scoped login-time JIT from unverified SCIM and native group absence", () => {
  const at = new Date("2026-10-03T00:27:18Z");
  assert.equal(zitadelUpstreamDrafts.length, 2);
  assert.equal(Object.hasOwn(zitadelDraft.options[0].facts, "JIT"), false);
  for (const draft of zitadelUpstreamDrafts) {
    const before = JSON.stringify(draft);
    const report = inspectScopedBaselineDraft(draft, at);
    assertUntrusted(report);
    assert.equal(report.optionCount, 1);
    assert.equal(report.factCount, 4);
    const option = report.options[0];
    assert.equal(option.basis, "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
    assert.equal(option.sourcePlan, "Free");
    assert.ok(["okta-workforce", "entra-id-workforce"].includes(option.upstreamProviderId));
    assert.equal(option.product, "ZITADEL Cloud");
    assert.equal(option.plan, "Free; upstream workforce entitlement unverified");
    assert.equal(Object.hasOwn(option, "sourceRelease"), false);
    assert.equal(Object.hasOwn(option, "sourceCommit"), false);
    assert.deepEqual(Object.fromEntries(option.facts.map((fact) => [fact.path, fact.availability])), {
      "facts.ENTERPRISE_SSO": "OPTIONAL", "facts.GROUP_SYNC": "UNAVAILABLE", "facts.JIT": "OPTIONAL", "facts.SCIM": "UNKNOWN",
    });
    assert.equal(option.omittedCapabilities.length, 5);
    assert.ok(["OIDC", "SAML"].every((capability) => option.omittedCapabilities.includes(capability)));
    assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
    const claims = draft.options[0].facts;
    assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /organization targeting/);
    assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /upstream.*entitlements.*unverified.*not a zero-cost guarantee/);
    assert.match(claims.JIT.conditions.join(" "), /automatic creation and automatic update.*at login, not.*background/);
    assert.match(claims.JIT.conditions.join(" "), /Account linking and email trust.*not SCIM deactivation/);
    assert.match(claims.SCIM.conditions.join(" "), /Preview.*Free-plan access.*Cloud version.*upstream/);
    assert.match(claims.SCIM.conditions.join(" "), /UNKNOWN is not UNAVAILABLE/);
    assert.match(claims.GROUP_SYNC.conditions.join(" "), /native inbound SCIM Group.*User-only.*no external bridge/);
    assert.match(claims.GROUP_SYNC.conditions.join(" "), /Do not generalize.*not synchronized SaaS group membership/);
    if (option.upstreamProviderId === "okta-workforce") {
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /generic OIDC.*Okta Web app/);
      assert.match(claims.SCIM.conditions.join(" "), /existing SAML.*does not validate.*generic OIDC pairing/);
    } else {
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /Microsoft provider template.*fixed workforce Tenant ID/);
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /Common, Organizations and Consumers.*outside.*single-tenant/);
      assert.match(claims.SCIM.conditions.join(" "), /do not inherit Auth0-specific oid\/objectId\/externalId mapping/);
    }
    for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
      const later = inspectScopedBaselineDraft(draft, new Date(at.getTime() + offset));
      assertUntrusted(later);
      assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
        && fact.evidence.observedAt === "2026-10-03T00:27:18Z"));
    }
    assert.equal(JSON.stringify(draft), before);
  }
});

test("ZITADEL pair inspection rejects connector drift, inherited mappings and JIT/SCIM/group promotion", () => {
  for (const draft of zitadelUpstreamDrafts) {
    const other = zitadelUpstreamDrafts.find((candidate) => candidate !== draft);
    const mutations = [
      (input) => { input.approvalGranted = true; },
      (input) => { input.upstreamProviderId = "verified-workforce"; },
      (input) => { input.options[0].upstreamProviderId = "verified-workforce"; },
      (input) => { input.options[0].configuration = other.options[0].configuration; },
      (input) => { input.options[0].configuration = "Instance-wide default, automatic email linking and outbound SaaS provisioning"; },
      (input) => { input.options[0].configuration = "Auth0 Entra oid/objectId/externalId mapping"; },
      (input) => { input.options[0].plan = "Pro; verified upstream entitlement"; },
      (input) => { input.options[0].product = "ZITADEL self-hosted"; },
      (input) => { input.options[0].providerId = "auth0"; },
      (input) => { input.options[0].id = other.options[0].id; },
      (input) => { input.options[0].region = "EU"; },
      (input) => { input.options[0].deployment = "SELF_HOSTED"; },
      (input) => { input.catalogVersion = other.catalogVersion; },
      (input) => { input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl = other.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = other.options[0].facts.SCIM.evidence.sourceUrl; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = auth0Draft.options[0].facts.SCIM.evidence.sourceUrl; },
      (input) => { input.options[0].facts.JIT.evidence.sourceUrl = input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?reviewed=true"; },
      (input) => { input.options[0].facts.SCIM.evidence.observedAt = "2026-02-30T00:27:18Z"; },
      (input) => { input.options[0].facts.SCIM.availability = "OPTIONAL"; },
      (input) => { input.options[0].facts.SCIM.availability = "UNAVAILABLE"; },
      (input) => { input.options[0].facts.JIT.availability = "MANDATORY"; },
      (input) => { input.options[0].facts.GROUP_SYNC.availability = "UNKNOWN"; },
      (input) => { input.options[0].facts.GROUP_SYNC.availability = "OPTIONAL"; },
      (input) => { input.options[0].facts.SCIM.conditions = []; },
      (input) => { input.options[0].facts.JIT.conditions.push(input.options[0].facts.JIT.conditions[0]); },
      (input) => { input.options[0].facts.OIDC = structuredClone(zitadelDraft.options[0].facts.OIDC); },
      (input) => { delete input.options[0].facts.JIT; },
      (input) => { input.options.push(structuredClone(input.options[0])); },
      (input) => { input.options[0].compatibility.membership.MULTIPLE_ORGANIZATIONS_PER_USER = {
        support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.JIT.evidence),
      }; },
    ];
    for (const mutate of mutations) {
      const input = structuredClone(draft); mutate(input);
      assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
    }
  }
});

test("Keycloak workforce scopes pin broker documentation without certifying a provisioning pair", () => {
  const at = new Date("2026-10-08T04:57:09Z");
  const beforeNative = JSON.stringify(scopedDraft);
  assert.equal(keycloakUpstreamDrafts.length, 2);
  for (const draft of keycloakUpstreamDrafts) {
    const before = JSON.stringify(draft);
    const report = inspectScopedBaselineDraft(draft, at);
    assertUntrusted(report);
    assert.equal(report.optionCount, 1);
    assert.equal(report.factCount, 4);
    const option = report.options[0];
    assert.equal(option.basis, "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
    assert.equal(option.sourceRelease, "26.8.0");
    assert.equal(option.sourceCommit, "4246609cf2024c85016d3fb1254c3d2533367c31");
    assert.equal(Object.hasOwn(option, "sourcePlan"), false);
    assert.equal(option.deployment, "SELF_HOSTED");
    assert.equal(option.product, "Keycloak upstream 26.8.0");
    assert.deepEqual(Object.fromEntries(option.facts.map((fact) => [fact.path, fact.availability])), {
      "facts.ENTERPRISE_SSO": "OPTIONAL", "facts.GROUP_SYNC": "UNKNOWN", "facts.JIT": "OPTIONAL", "facts.SCIM": "UNKNOWN",
    });
    assert.equal(option.omittedCapabilities.length, 5);
    assert.ok(["OIDC", "SAML"].every((capability) => option.omittedCapabilities.includes(capability)));
    assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
    const claims = draft.options[0].facts;
    assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /Authorization Code.*exact issuer.*signature validation.*not vendor-certified or runtime-tested/);
    assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /Only the Keycloak source is release-pinned.*upstream documentation is mutable/);
    assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /entitlements.*unverified.*not a zero-cost guarantee/);
    assert.match(claims.JIT.conditions.join(" "), /Create User If Unique.*at login, not through background provisioning.*independently of realm self-registration/);
    assert.match(claims.JIT.conditions.join(" "), /proof of control.*do not enable unverified automatic email-based linking/);
    assert.match(claims.SCIM.conditions.join(" "), /identity correlation.*pair-specific.*UNKNOWN is not UNAVAILABLE/);
    assert.match(claims.SCIM.conditions.join(" "), /Do not inherit native SCIM OPTIONAL.*Auth0-specific/);
    assert.match(claims.SCIM.conditions.join(" "), /Inbound provisioning into Keycloak is not outbound SaaS provisioning/);
    assert.match(claims.GROUP_SYNC.conditions.join(" "), /at login according to sync mode.*do not establish background group lifecycle/);
    assert.match(claims.GROUP_SYNC.conditions.join(" "), /Native SCIM Group resources.*not proof of this pair.*nested-group.*No external bridge/);
    if (option.upstreamProviderId === "okta-workforce") {
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /Okta Web app.*developer.okta.com\/docs\/guides\/create-an-app-integration.*fixed org authorization server.*not an inherited custom\/default issuer/);
      assert.match(claims.GROUP_SYNC.conditions.join(" "), /assignment.*Group Push/);
    } else {
      assert.equal(option.upstreamProviderId, "entra-id-workforce");
      assert.match(claims.ENTERPRISE_SSO.conditions.join(" "), /Entra Web app.*fixed workforce Tenant ID.*tenant-specific v2 discovery.*learn.microsoft.com.*Common, Organizations, Consumers, External ID and B2C are outside/);
      assert.match(claims.SCIM.conditions.join(" "), /oid\/objectId\/externalId/);
    }
    for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
      const later = inspectScopedBaselineDraft(draft, new Date(at.getTime() + offset));
      assertUntrusted(later);
      assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
        && fact.evidence.observedAt === "2026-10-08T04:57:09Z"));
    }
    assert.equal(JSON.stringify(draft), before);
  }
  assert.equal(JSON.stringify(scopedDraft), beforeNative);
  assert.ok(inspectScopedBaselineDraft(scopedDraft, at).options[0].facts.every((fact) => fact.availability === "OPTIONAL"));
  assert.equal(Object.hasOwn(scopedDraft.options[0].facts, "JIT"), false);
});

test("Keycloak pair inspection rejects release/scope drift and inherited provisioning or trust claims", () => {
  for (const draft of keycloakUpstreamDrafts) {
    const other = keycloakUpstreamDrafts.find((candidate) => candidate !== draft);
    const mutations = [
      (input) => { input.approvalGranted = true; },
      (input) => { input.sourceRelease = "26.8.0"; },
      (input) => { input.options[0].upstreamProviderId = "verified-workforce"; },
      (input) => { input.catalogVersion = other.catalogVersion; },
      (input) => { input.options[0].id = other.options[0].id; },
      (input) => { input.options[0].configuration = other.options[0].configuration; },
      (input) => { input.options[0].configuration = "Automatic email linking and verified outbound SaaS SCIM"; },
      (input) => { input.options[0].providerId = "zitadel"; },
      (input) => { input.options[0].product = "Keycloak upstream latest"; },
      (input) => { input.options[0].deployment = "MANAGED"; },
      (input) => { input.options[0].plan = "Free; all upstream entitlements verified"; },
      (input) => { input.options[0].region = "EU"; },
      (input) => { input.options[0].facts.ENTERPRISE_SSO.availability = "MANDATORY"; },
      (input) => { input.options[0].facts.JIT.availability = "MANDATORY"; },
      (input) => { input.options[0].facts.SCIM.availability = "OPTIONAL"; },
      (input) => { input.options[0].facts.SCIM.availability = "UNAVAILABLE"; },
      (input) => { input.options[0].facts.GROUP_SYNC.availability = "OPTIONAL"; },
      (input) => { input.options[0].facts.GROUP_SYNC.availability = "UNAVAILABLE"; },
      (input) => { input.options[0].facts.SCIM = structuredClone(scopedDraft.options[0].facts.SCIM); },
      (input) => { input.options[0].facts.JIT.evidence.sourceUrl = input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = auth0Draft.options[0].facts.SCIM.evidence.sourceUrl; },
      (input) => { input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl = input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl.replace("4246609cf2024c85016d3fb1254c3d2533367c31", "main"); },
      (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl += "?approved=true"; },
      (input) => { input.options[0].facts.JIT.evidence.observedAt = "2026-02-30T04:57:09Z"; },
      (input) => { input.options[0].facts.JIT.conditions = []; },
      (input) => { input.options[0].facts.JIT.conditions.push(input.options[0].facts.JIT.conditions[0]); },
      (input) => { input.options[0].facts.OIDC = structuredClone(scopedDraft.options[0].facts.OIDC); },
      (input) => { delete input.options[0].facts.JIT; },
      (input) => { input.options.push(structuredClone(input.options[0])); },
      (input) => { input.options[0].residency.USER_PROFILES = {
        coverage: "COMPLETE", storageCountries: ["DE"], conditions: [], evidence: structuredClone(input.options[0].facts.JIT.evidence),
      }; },
      (input) => { input.options[0].compatibility.applications.B2B_SAAS = {
        support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.JIT.evidence),
      }; },
      (input) => { input.options[0].authenticationControls.BROWSER = { EMPLOYEES: { PHISHING_RESISTANCE: {
        availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.JIT.evidence),
      } } }; },
    ];
    for (const mutate of mutations) {
      const input = structuredClone(draft); mutate(input);
      assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
    }
  }
});

test("WorkOS workforce directories propose conditional staging ingestion without login or enforcement", () => {
  const at = new Date("2026-10-08T05:14:26Z");
  assert.equal(workosUpstreamDrafts.length, 2);
  for (const draft of workosUpstreamDrafts) {
    const before = JSON.stringify(draft);
    const report = inspectScopedBaselineDraft(draft, at);
    assertUntrusted(report);
    assert.equal(report.optionCount, 1);
    assert.equal(report.factCount, 2);
    const option = report.options[0];
    assert.equal(option.basis, "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
    assert.equal(option.sourcePlan, "Staging");
    assert.equal(option.product, "WorkOS Directory Sync");
    assert.equal(option.deployment, "MANAGED");
    assert.equal(Object.hasOwn(option, "sourceRelease"), false);
    assert.equal(Object.hasOwn(option, "sourceCommit"), false);
    assert.ok(option.facts.every((fact) => fact.availability === "OPTIONAL" && fact.freshness === "CURRENT"));
    assert.equal(option.omittedCapabilities.length, 7);
    assert.ok(["OIDC", "SAML", "ENTERPRISE_SSO", "JIT"].every((capability) => option.omittedCapabilities.includes(capability)));
    const claims = draft.options[0].facts;
    const scim = claims.SCIM.conditions.join(" ");
    const groups = claims.GROUP_SYNC.conditions.join(" ");
    assert.match(scim, /organization-bound.*staging.*bearer.token/);
    assert.match(scim, /OAuth client credentials are outside.*not declared unsupported/);
    assert.match(scim, /staging connections are not billed.*production Directory Sync.*charged per connection.*Upstream provisioning entitlement remains unverified/);
    assert.match(scim, /Events API bridge.*persisted cursor.*replay handling.*state reconciliation/);
    assert.match(scim, /read-only.*not a native SaaS SCIM endpoint or write-back.*authorization and sessions remain application-owned/);
    assert.match(groups, /dsync.group.deleted.*without expecting.*dsync.group.user_removed.*without deleting unrelated/);
    assert.match(groups, /not nested groups.*production entitlement or measured revocation latency/);
    if (option.upstreamProviderId === "okta-workforce") {
      assert.match(scim, /suspension alone does not deactivate/);
      assert.match(groups, /Push Groups separately.*assignment alone is not Group Push/);
      assert.match(groups, /Membership removal may be missing.*reconciliation.*re-push recovery/);
      assert.match(groups, /display names populate WorkOS idp_id.*not.*immutable globally unique/);
    } else {
      assert.equal(option.upstreamProviderId, "entra-id-workforce");
      assert.match(scim, /map objectId to externalId.*only assigned users\/groups/);
      assert.match(scim, /not Entra External ID, B2C or a Graph pull connector/);
      assert.match(scim, /Scheduled provisioning and on-demand actions are distinct.*neither proves immediate/);
      assert.match(groups, /Group externalId.*WorkOS idp_id.*unlike Okta/);
      assert.match(groups, /soft deletion.*not restore memberships automatically.*Restart Provisioning/);
    }
    for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
      const later = inspectScopedBaselineDraft(draft, new Date(at.getTime() + offset));
      assertUntrusted(later);
      assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
        && fact.evidence.observedAt === "2026-10-08T05:14:26Z"));
    }
    assert.equal(JSON.stringify(draft), before);
  }
  assert.equal(workosDraft.options[0].facts.SCIM.evidence.observedAt, "2026-10-02T22:46:13Z");
  assert.equal(Object.hasOwn(workosDraft.options[0].facts, "JIT"), false);
});

test("WorkOS pair inspection rejects connector, authentication, environment and authority substitutions", () => {
  for (const draft of workosUpstreamDrafts) {
    const other = workosUpstreamDrafts.find((candidate) => candidate !== draft);
    const mutations = [
      (input) => { input.approvalGranted = true; },
      (input) => { input.sourcePlan = "Production"; },
      (input) => { input.options[0].upstreamProviderId = "verified-workforce"; },
      (input) => { input.catalogVersion = other.catalogVersion; },
      (input) => { input.options[0].id = other.options[0].id; },
      (input) => { input.options[0].configuration = other.options[0].configuration; },
      (input) => { input.options[0].configuration = "OAuth client credentials; native SaaS SCIM with automatic session revocation"; },
      (input) => { input.options[0].configuration = workosDraft.options[0].configuration; },
      (input) => { input.options[0].product = "WorkOS AuthKit"; },
      (input) => { input.options[0].plan = "Production; free up to 1M users"; },
      (input) => { input.options[0].deployment = "SELF_HOSTED"; },
      (input) => { input.options[0].providerId = "entra-external-id"; },
      (input) => { input.options[0].region = "EU"; },
      (input) => { input.options[0].facts.SCIM.availability = "MANDATORY"; },
      (input) => { input.options[0].facts.GROUP_SYNC.availability = "UNAVAILABLE"; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = other.options[0].facts.SCIM.evidence.sourceUrl; },
      (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl = workosDraft.options[0].facts.GROUP_SYNC.evidence.sourceUrl; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = "https://workos.com/pricing"; },
      (input) => { input.options[0].facts.GROUP_SYNC.evidence.sourceUrl += "?approved=true"; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = "https://workos.com.attacker.invalid/docs"; },
      (input) => { input.options[0].facts.SCIM.evidence.evidenceStatus = "REVIEWED"; },
      (input) => { input.options[0].facts.SCIM.evidence.observedAt = "2026-02-30T05:14:26Z"; },
      (input) => { input.options[0].facts.SCIM.conditions = []; },
      (input) => { input.options[0].facts.GROUP_SYNC.conditions.push(input.options[0].facts.GROUP_SYNC.conditions[0]); },
      (input) => { input.options[0].facts.OIDC = structuredClone(auth0Draft.options[0].facts.OIDC); },
      (input) => { delete input.options[0].facts.GROUP_SYNC; },
      (input) => { input.options.push(structuredClone(input.options[0])); },
      (input) => { input.options[0].compatibility.applications.B2B_SAAS = {
        support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.SCIM.evidence),
      }; },
      (input) => { input.options[0].residency.USER_PROFILES = {
        coverage: "COMPLETE", storageCountries: ["DE"], conditions: [], evidence: structuredClone(input.options[0].facts.SCIM.evidence),
      }; },
    ];
    for (const mutate of mutations) {
      const input = structuredClone(draft); mutate(input);
      assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
    }
  }
});

test("External ID workforce candidates separate browser federation and first sign-up from directory lifecycle", () => {
  const at = new Date("2026-10-08T05:32:34Z");
  assert.equal(entraUpstreamDrafts.length, 2);
  const beforeNative = JSON.stringify(entraDraft);
  for (const draft of entraUpstreamDrafts) {
    const before = JSON.stringify(draft);
    const report = inspectScopedBaselineDraft(draft, at);
    assertUntrusted(report);
    assert.equal(report.optionCount, 1);
    assert.equal(report.factCount, 4);
    const option = report.options[0];
    assert.equal(option.basis, "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
    assert.equal(option.sourcePlan, "Basic MAU");
    assert.equal(option.product, "Microsoft Entra External ID - external tenant");
    assert.equal(option.deployment, "MANAGED");
    assert.equal(Object.hasOwn(option, "sourceRelease"), false);
    assert.equal(Object.hasOwn(option, "sourceCommit"), false);
    assert.deepEqual(option.facts.map((fact) => [fact.path, fact.availability]), [
      ["facts.ENTERPRISE_SSO", "OPTIONAL"], ["facts.GROUP_SYNC", "UNKNOWN"],
      ["facts.JIT", "OPTIONAL"], ["facts.SCIM", "UNKNOWN"],
    ]);
    assert.equal(option.omittedCapabilities.length, 5);
    assert.ok(["OIDC", "SAML", "MFA"].every((capability) => option.omittedCapabilities.includes(capability)));
    const claims = draft.options[0].facts;
    const sso = claims.ENTERPRISE_SSO.conditions.join(" ");
    const jit = claims.JIT.conditions.join(" ");
    const scim = claims.SCIM.conditions.join(" ");
    const groups = claims.GROUP_SYNC.conditions.join(" ");
    assert.match(sso, /browser-delegated.*standard-mode external tenant/);
    assert.match(sso, /code.*client_secret_post/);
    assert.match(sso, /client_secret_basic.*private_key_jwt/);
    assert.match(sso, /user flow/);
    assert.match(sso, /zero-cost guarantee/);
    assert.match(jit, /issuer-bound sub.*claim\/user-flow attribute mappings/);
    assert.match(jit, /truthful email_verified.*not a constant true|truthful email_verified.*no constant true/);
    assert.match(jit, /email-only identity merge/);
    assert.match(scim, /P1.*Azure-linked paid add-on.*excluded here.*applicability/);
    assert.match(scim, /UNKNOWN is not UNAVAILABLE.*separate SaaS access\/session enforcement/);
    assert.match(groups, /Graph membership edits.*application roles.*do not establish/);
    assert.match(groups, /OIDC group claims and JIT are not a SCIM Group lifecycle/);
    if (option.upstreamProviderId === "okta-workforce") {
      assert.match(sso, /fixed Okta org issuer.*not the \/oauth2\/default.*access tokens.*not for.*SaaS API/);
      assert.match(sso, /default client_secret_basic is incompatible/);
      assert.match(sso, /inferred protocol-compatible.*not a vendor-certified or tested Okta connector/);
      assert.match(jit, /repeat-login updates have not been tested/);
      assert.match(groups, /Okta Group Push/);
    } else {
      assert.equal(option.upstreamProviderId, "entra-id-workforce");
      assert.match(sso, /organizations\/v2.0 discovery.*explicit tenant-ID\/v2.0 issuer/);
      assert.match(sso, /not common, consumers, a multi-tenant issuer or domain_hint/);
      assert.match(sso, /MFA is not automatically trusted.*may prompt again/);
      assert.match(jit, /Graph user-creation alternative is not selected/);
      assert.match(jit, /Do not borrow Auth0's oid choice, WorkOS objectId\/externalId/);
      assert.match(jit, /workforce guide requires email.*generic guide allows.*optional-email/);
    }
    for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
      const later = inspectScopedBaselineDraft(draft, new Date(at.getTime() + offset));
      assertUntrusted(later);
      assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness
        && fact.evidence.observedAt === "2026-10-08T05:32:34Z"));
    }
    assert.equal(JSON.stringify(draft), before);
  }
  assert.equal(JSON.stringify(entraDraft), beforeNative);
  assert.equal(entraDraft.options[0].facts.OIDC.evidence.observedAt, "2026-10-02T23:12:01Z");
  assert.equal(Object.hasOwn(entraDraft.options[0].facts, "JIT"), false);
});

test("External ID pair inspection rejects tenant, client-auth, identity, fact and authority substitutions", () => {
  for (const draft of entraUpstreamDrafts) {
    const other = entraUpstreamDrafts.find((candidate) => candidate !== draft);
    const mutations = [
      (input) => { input.approvalGranted = true; },
      (input) => { input.sourcePlan = "P1"; },
      (input) => { input.options[0].upstreamProviderId = "verified-workforce"; },
      (input) => { input.catalogVersion = other.catalogVersion; },
      (input) => { input.options[0].id = other.options[0].id; },
      (input) => { input.options[0].configuration = other.options[0].configuration; },
      (input) => { input.options[0].configuration = "Workforce B2B guest redemption with automatic SCIM lifecycle"; },
      (input) => { input.options[0].configuration = input.options[0].configuration.replace("client_secret_post", "client_secret_basic"); },
      (input) => { input.options[0].configuration = entraDraft.options[0].configuration; },
      (input) => { input.options[0].product = "Azure AD B2C"; },
      (input) => { input.options[0].plan = "P1; SCIM paid add-on verified"; },
      (input) => { input.options[0].deployment = "SELF_HOSTED"; },
      (input) => { input.options[0].providerId = "auth0"; },
      (input) => { input.options[0].region = "EU"; },
      (input) => { input.options[0].facts.SCIM.availability = "OPTIONAL"; },
      (input) => { input.options[0].facts.GROUP_SYNC.availability = "UNAVAILABLE"; },
      (input) => { input.options[0].facts.ENTERPRISE_SSO.availability = "MANDATORY"; },
      (input) => { input.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl = other.options[0].facts.ENTERPRISE_SSO.evidence.sourceUrl; },
      (input) => { input.options[0].facts.JIT.evidence.sourceUrl = other.options[0].facts.JIT.evidence.sourceUrl; },
      (input) => { input.options[0].facts.SCIM = structuredClone(auth0Draft.options[0].facts.SCIM); },
      (input) => { input.options[0].facts.SCIM = structuredClone(workosDraft.options[0].facts.SCIM); },
      (input) => { input.options[0].facts.GROUP_SYNC = structuredClone(zitadelDraft.options[0].facts.GROUP_SYNC); },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = "https://learn.microsoft.com.attacker.invalid/docs"; },
      (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?approved=true"; },
      (input) => { input.options[0].facts.JIT.evidence.evidenceStatus = "REVIEWED"; },
      (input) => { input.options[0].facts.JIT.evidence.observedAt = "2026-02-30T05:32:34Z"; },
      (input) => { input.options[0].facts.JIT.conditions = []; },
      (input) => { input.options[0].facts.GROUP_SYNC.conditions.push(input.options[0].facts.GROUP_SYNC.conditions[0]); },
      (input) => { input.options[0].facts.OIDC = structuredClone(entraDraft.options[0].facts.OIDC); },
      (input) => { delete input.options[0].facts.JIT; },
      (input) => { input.options.push(structuredClone(input.options[0])); },
      (input) => { input.options[0].compatibility.applications.B2B_SAAS = {
        support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.JIT.evidence),
      }; },
      (input) => { input.options[0].authenticationControls.MFA_ENFORCEMENT = {
        enforcement: "ENFORCED", conditions: [], evidence: structuredClone(input.options[0].facts.JIT.evidence),
      }; },
    ];
    for (const mutate of mutations) {
      const input = structuredClone(draft); mutate(input);
      assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
    }
  }
});

test("combined inspection keeps research and scoped options distinct without promoting either", async () => {
  const at = new Date("2026-10-08T17:49:06Z");
  const report = await inspectBaselinePack(at);
  assertUntrusted(report);
  assert.equal(report.scope, "PROVIDER_BASELINE_PACK_INSPECTION");
  assert.equal(report.optionCount, 33);
  assert.equal(report.factCount, 108);
  assert.equal(report.researchOptionCount, 5);
  assert.equal(report.scopedDraftOptionCount, 28);
  const keycloak = report.options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 7);
  assert.equal(new Set(keycloak.map((option) => option.optionId)).size, 7);
  assert.equal(keycloak.filter((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").length, 1);
  assert.ok(keycloak.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").facts.every((fact) => fact.availability === "UNKNOWN"));
  const keycloakPairs = keycloak.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
  assert.deepEqual(keycloakPairs.map((option) => option.upstreamProviderId).sort(), ["entra-id-workforce", "okta-workforce"]);
  assert.ok(keycloakPairs.every((option) => option.sourceRelease === "26.8.0"
    && option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.ok(keycloak.find((option) => option.basis === "RELEASE_SCOPED_DOCUMENTATION_DRAFT")
    .facts.every((fact) => fact.availability === "OPTIONAL"));
  const keycloakOrganizations = keycloak.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(keycloakOrganizations.sourceRelease, "26.8.0");
  assert.equal(keycloakOrganizations.sourceCommit, "4246609cf2024c85016d3fb1254c3d2533367c31");
  assert.equal(Object.hasOwn(keycloakOrganizations, "sourcePlan"), false);
  assert.equal(keycloakOrganizations.facts.length, 4);
  assert.ok(keycloakOrganizations.facts.every((fact) => fact.path.startsWith("compatibility.") && fact.support === "SUPPORTED"));
  assert.ok(report.options.every((option) => option.facts.every((fact) => fact.freshness === "CURRENT")));
  const zitadel = report.options.filter((option) => option.providerId === "zitadel");
  assert.equal(zitadel.length, 7);
  assert.equal(new Set(zitadel.map((option) => option.optionId)).size, 7);
  assert.equal(zitadel.filter((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").length, 1);
  assert.ok(zitadel.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.equal(zitadel.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT").sourcePlan, "Free");
  const zitadelPairs = zitadel.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(zitadelPairs.length, 2);
  assert.deepEqual(zitadelPairs.map((option) => option.upstreamProviderId).sort(), ["entra-id-workforce", "okta-workforce"]);
  assert.ok(zitadelPairs.every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(zitadel.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT")
    .facts.some((fact) => fact.path === "facts.JIT"), false);
  const organizations = zitadel.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(organizations.sourcePlan, "Free");
  assert.equal(organizations.facts.length, 4);
  assert.ok(organizations.facts.every((fact) => fact.path.startsWith("compatibility.") && fact.support === "SUPPORTED"));
  assert.equal(organizations.facts.some((fact) => fact.path.startsWith("facts.")), false);
  const auth0 = report.options.filter((option) => option.providerId === "auth0");
  assert.equal(auth0.length, 7);
  assert.equal(new Set(auth0.map((option) => option.optionId)).size, 7);
  const auth0Organizations = auth0.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(auth0Organizations.sourcePlan, "B2B Free");
  assert.equal(auth0Organizations.facts.length, 4);
  assert.ok(auth0Organizations.facts.every((fact) => fact.path.startsWith("compatibility.") && fact.support === "SUPPORTED"));
  assert.equal(auth0Organizations.facts.some((fact) => fact.path.startsWith("facts.")), false);
  const research = auth0.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE");
  assert.ok(research.facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.equal(research.facts.some((fact) => fact.path === "facts.ENTERPRISE_SSO"), false);
  assert.equal(auth0.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT").sourcePlan, "B2B Free");
  const pairs = auth0.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(pairs.length, 2);
  assert.deepEqual(pairs.map((option) => option.upstreamProviderId).sort(), ["entra-id-workforce", "okta-workforce"]);
  assert.ok(pairs.every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(auth0.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT")
    .facts.find((fact) => fact.path === "facts.SCIM").availability, "OPTIONAL");
  const workos = report.options.filter((option) => option.providerId === "workos");
  assert.equal(workos.length, 6);
  assert.equal(new Set(workos.map((option) => option.optionId)).size, 6);
  const workosOrganizations = workos.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(workosOrganizations.sourcePlan, "Staging");
  assert.equal(workosOrganizations.product, "WorkOS AuthKit");
  assert.equal(workosOrganizations.facts.length, 4);
  assert.ok(workosOrganizations.facts.every((fact) => fact.path.startsWith("compatibility.") && fact.support === "SUPPORTED"));
  assert.equal(workosOrganizations.facts.some((fact) => fact.path.startsWith("facts.")), false);
  const workosResearch = workos.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE");
  assert.ok(workosResearch.facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.equal(workosResearch.facts.find((fact) => fact.path === "facts.OIDC").availability, "UNKNOWN");
  const directory = workos.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(directory.sourcePlan, "Staging");
  assert.equal(directory.facts.length, 2);
  assert.equal(directory.facts.some((fact) => fact.path === "facts.OIDC"), false);
  const connect = workos.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(connect.sourcePlan, "Staging");
  assert.equal(connect.product, "WorkOS AuthKit Connect");
  assert.equal(connect.facts.length, 3);
  assert.equal(connect.facts.find((fact) => fact.path === "compatibility.clients.BROWSER").support, "UNKNOWN");
  assert.equal(connect.facts.some((fact) => fact.path === "facts.SCIM"), false);
  const workosPairs = workos.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
  assert.deepEqual(workosPairs.map((option) => option.upstreamProviderId).sort(), ["entra-id-workforce", "okta-workforce"]);
  assert.ok(workosPairs.every((option) => option.sourcePlan === "Staging" && option.facts.length === 2
    && option.facts.every((fact) => fact.availability === "OPTIONAL")));
  const entra = report.options.filter((option) => option.providerId === "entra-external-id");
  assert.equal(entra.length, 6);
  assert.equal(new Set(entra.map((option) => option.optionId)).size, 6);
  const entraOrganizations = entra.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(entraOrganizations.sourcePlan, "Basic MAU");
  assert.equal(entraOrganizations.facts.length, 4);
  assert.ok(entraOrganizations.facts.every((fact) => fact.path.startsWith("compatibility.")));
  assert.equal(entraOrganizations.facts.find((fact) => fact.path === "compatibility.applications.B2B_SAAS").support, "SUPPORTED");
  assert.ok(entraOrganizations.facts.filter((fact) => fact.path !== "compatibility.applications.B2B_SAAS")
    .every((fact) => fact.support === "UNKNOWN"));
  const entraResearch = entra.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE");
  assert.ok(entraResearch.facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.equal(entraResearch.facts.some((fact) => fact.path === "facts.SAML"), false);
  const external = entra.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(external.sourcePlan, "Basic MAU");
  assert.equal(external.facts.find((fact) => fact.path === "facts.SCIM").availability, "UNKNOWN");
  const entraPairs = entra.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
  assert.deepEqual(entraPairs.map((option) => option.upstreamProviderId).sort(), ["entra-id-workforce", "okta-workforce"]);
  assert.ok(entraPairs.every((option) => option.sourcePlan === "Basic MAU"
    && option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"
    && option.facts.some((fact) => fact.path === "facts.JIT" && fact.availability === "OPTIONAL")
    && !option.facts.some((fact) => fact.path === "facts.OIDC")));
  // Ten pair-specific scopes exist, but this is inventory, not complete compatibility coverage.
  for (const providerId of ["auth0", "entra-external-id", "keycloak", "workos", "zitadel"]) {
    const pairOptions = report.options.filter((option) => option.providerId === providerId
      && option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT");
    assert.deepEqual(pairOptions.map((option) => option.upstreamProviderId).sort(), ["entra-id-workforce", "okta-workforce"]);
  }
  assert.deepEqual(await inspectBaselinePack(at), report);
});

test("research inspection rejects malformed, promoted, duplicated and cross-provider inputs", () => {
  const mutations = [
    (input) => { input[0].kind = "SYNTHETIC"; },
    (input) => { input[0].approvalGranted = true; },
    (input) => { input[0].options[0].facts.SCIM.availability = "OPTIONAL"; },
    (input) => { input[0].options[0].facts.SCIM.conditions = []; },
    (input) => { input[0].options[0].facts.SCIM.conditions.push(input[0].options[0].facts.SCIM.conditions[0]); },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://learn.microsoft.com.attacker.invalid/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://auth0.com/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://user@learn.microsoft.com/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://learn.microsoft.com:8443/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.observedAt = "2026-02-30T19:58:06Z"; },
    (input) => { input[0].options[0].deployment = "SELF_HOSTED"; },
    (input) => { input[0].options[0].residency.USER_PROFILES = {
      coverage: "COMPLETE", storageCountries: ["DE"], conditions: [],
      evidence: structuredClone(input[0].options[0].facts.OIDC.evidence),
    }; },
    (input) => { input[0].options[0].compatibility.applications.B2B_SAAS = {
      support: "SUPPORTED", conditions: [], evidence: structuredClone(input[0].options[0].facts.OIDC.evidence),
    }; },
    (input) => { input[0].options.push(structuredClone(input[0].options[0])); },
    (input) => { input[0].options[0].providerId = "other-vendor"; },
    (input) => { input[1] = structuredClone(input[0]); },
    (input) => { input[0].catalogVersion = "other-version"; },
    (input) => { delete input[0].options[0].facts.SCIM; },
    (input) => { input.pop(); },
    (input) => { input.push(structuredClone(input[0])); },
  ];
  for (const mutate of mutations) {
    const input = copy(); mutate(input);
    assert.throws(() => inspectBaselineDrafts(input, observedAt));
  }
});

test("CLI reads only fixed local inputs and accepts no arbitrary source argument", () => {
  const script = new URL("../scripts/inspect-provider-baselines.mjs", import.meta.url);
  const run = spawnSync(process.execPath, [script.pathname], { encoding: "utf8" });
  assert.equal(run.status, 0, run.stderr);
  assertUntrusted(JSON.parse(run.stdout));
  assert.equal(JSON.parse(run.stdout).factCount, 108);
  const rejected = spawnSync(process.execPath, [script.pathname, "https://attacker.invalid/catalog"], { encoding: "utf8" });
  assert.equal(rejected.status, 1);
  assert.equal(rejected.stdout, "");
  assert.match(rejected.stderr, /accepts no file paths, URLs or options/);
});

test("active catalog remains the separate synthetic fixture", async () => {
  const catalog = JSON.parse(await readFile(new URL(
    "../../../services/core-api/src/main/resources/catalog/synthetic.v4.json", import.meta.url,
  ), "utf8"));
  assert.equal(catalog.kind, "SYNTHETIC");
  assert.ok(catalog.options.every((option) => !drafts.some((draft) => draft.options[0].id === option.id)));
  assert.ok(catalog.options.every((option) => scopedDrafts.every((draft) => option.id !== draft.options[0].id)));
});
