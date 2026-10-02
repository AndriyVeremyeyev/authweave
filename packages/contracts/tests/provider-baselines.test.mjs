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

test("combined inspection keeps research and scoped options distinct without promoting either", async () => {
  const at = new Date("2026-10-02T23:25:54Z");
  const report = await inspectBaselinePack(at);
  assertUntrusted(report);
  assert.equal(report.scope, "PROVIDER_BASELINE_PACK_INSPECTION");
  assert.equal(report.optionCount, 12);
  assert.equal(report.factCount, 39);
  assert.equal(report.researchOptionCount, 5);
  assert.equal(report.scopedDraftOptionCount, 7);
  const keycloak = report.options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 2);
  assert.notEqual(keycloak[0].optionId, keycloak[1].optionId);
  assert.equal(keycloak.filter((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").length, 1);
  assert.ok(keycloak.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").facts.every((fact) => fact.availability === "UNKNOWN"));
  const zitadel = report.options.filter((option) => option.providerId === "zitadel");
  assert.equal(zitadel.length, 2);
  assert.equal(zitadel.filter((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").length, 1);
  assert.ok(zitadel.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.equal(zitadel.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT").sourcePlan, "Free");
  const auth0 = report.options.filter((option) => option.providerId === "auth0");
  assert.equal(auth0.length, 4);
  assert.equal(new Set(auth0.map((option) => option.optionId)).size, 4);
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
  assert.equal(workos.length, 2);
  assert.notEqual(workos[0].optionId, workos[1].optionId);
  const workosResearch = workos.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE");
  assert.ok(workosResearch.facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.equal(workosResearch.facts.find((fact) => fact.path === "facts.OIDC").availability, "UNKNOWN");
  const directory = workos.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(directory.sourcePlan, "Staging");
  assert.equal(directory.facts.length, 2);
  assert.equal(directory.facts.some((fact) => fact.path === "facts.OIDC"), false);
  const entra = report.options.filter((option) => option.providerId === "entra-external-id");
  assert.equal(entra.length, 2);
  assert.notEqual(entra[0].optionId, entra[1].optionId);
  const entraResearch = entra.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE");
  assert.ok(entraResearch.facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.equal(entraResearch.facts.some((fact) => fact.path === "facts.SAML"), false);
  const external = entra.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(external.sourcePlan, "Basic MAU");
  assert.equal(external.facts.find((fact) => fact.path === "facts.SCIM").availability, "UNKNOWN");
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
  assert.equal(JSON.parse(run.stdout).factCount, 39);
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
