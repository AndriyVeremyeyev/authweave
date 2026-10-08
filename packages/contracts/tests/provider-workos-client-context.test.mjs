import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "workos-connect-staging-public-oidc-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T13:50:08Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("WorkOS Connect public clients retain staging scope without production entitlement or review", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Staging");
  assert.equal(option.product, "WorkOS AuthKit Connect");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(report.factCount, 3);
  assert.deepEqual(option.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.deepEqual(option.facts.map((fact) => fact.support ?? fact.availability), ["UNKNOWN", "SUPPORTED", "OPTIONAL"]);
  for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.equal(new URL(fact.evidence.sourceUrl).hostname, "workos.com");
  }
  const conditions = draft.options[0].facts.OIDC.conditions.join(" ");
  assert.match(conditions, /first-party Public Connect OAuth.*staging environment/);
  assert.match(conditions, /\/oauth2.*not primary AuthKit \/user_management/);
  assert.match(conditions, /documentation example.*not an observed environment or S256-only/);
  assert.match(conditions, /Reconcile the public-client exchange contract before source approval/);
  assert.match(conditions, /free staging is not production Connect entitlement/);
  assert.match(conditions, /mutable and dated, not release-pinned/);
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("WorkOS mobile PKCE does not establish SPA CORS, AuthKit primary login or security controls", () => {
  const option = draft.options[0];
  assert.equal(option.compatibility.clients.BROWSER.support, "UNKNOWN");
  const browser = option.compatibility.clients.BROWSER.conditions.join(" ");
  assert.match(browser, /Connect-specific token-endpoint CORS.*not established/);
  assert.match(browser, /Public registration and mobile PKCE.*do not establish a pure SPA/);
  assert.match(browser, /Do not substitute primary AuthKit React\/CORS.*local emulator/);
  assert.match(browser, /UNKNOWN.*not UNSUPPORTED or an omitted fact/);
  const mobile = option.compatibility.clients.NATIVE_MOBILE.conditions.join(" ");
  assert.match(mobile, /Public Connect.*not a default confidential client/);
  assert.match(mobile, /external user-agent under RFC 8252.*OS callback handling/);
  assert.match(mobile, /PKCE is not proof of callback ownership/);
  assert.match(mobile, /primary AuthKit session tokens and Directory Sync/);
  assert.match(mobile, /org_id\/scopes do not establish organization\/membership/);
  assert.deepEqual(option.authenticationControls, {});
  assert.deepEqual(option.residency, {});
  assert.equal(Object.hasOwn(option.facts, "OAUTH2_APIS"), false);
});

test("WorkOS inventory counts explicit browser UNKNOWN separately from omitted and capability claims", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 1, UNSUPPORTED: 0, UNKNOWN: 1 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 2, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  const entry = inventory.options[0];
  assert.equal(entry.recordedPathCount, 3);
  assert.equal(entry.omittedPathCount, 65);
  assert.deepEqual(entry.recordedUnknownPaths, ["compatibility.clients.BROWSER"]);
  assert.ok(entry.families[1].recordedPaths.includes("compatibility.clients.BROWSER"));
  assert.ok(!entry.families[1].omittedPaths.includes("compatibility.clients.BROWSER"));
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.clients.MACHINE_TO_MACHINE"));
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS"));
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("WorkOS inspection rejects Directory Sync/AuthKit/production conflation and unsupported authority", () => {
  const mutations = [
    (value) => { value.catalogVersion = "workos-public-latest"; },
    (value) => { value.options[0].id = "workos-directory-sync-staging-scim-events"; },
    (value) => { value.options[0].providerId = "zitadel"; },
    (value) => { value.options[0].product = "WorkOS Directory Sync"; },
    (value) => { value.options[0].product = "WorkOS AuthKit"; },
    (value) => { value.options[0].plan = "Production; AuthKit Free 1M MAU"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "US; all storage verified"; },
    (value) => { value.options[0].configuration = "Standalone external login; third-party consent; confidential BFF"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.support = "SUPPORTED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.support = "UNSUPPORTED"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.support = "UNKNOWN"; },
    (value) => { delete value.options[0].compatibility.clients.BROWSER; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE = structuredClone(value.options[0].compatibility.clients.NATIVE_MOBILE); },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS = structuredClone(value.options[0].compatibility.clients.NATIVE_MOBILE); },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.sourceUrl = "https://workos.com/docs/reference/authkit/create-cors-origin"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.evidence.sourceUrl = "https://workos.com/docs/authkit/react"; },
    (value) => { value.options[0].facts.OIDC.evidence.sourceUrl = "https://workos.com/docs/reference/authkit/authentication"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.clients.BROWSER; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OIDC.evidence.observedAt = "2026-02-30T13:50:08Z"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.availability = "UNKNOWN"; },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OIDC); },
    (value) => { value.options[0].facts.GROUP_SYNC = structuredClone(value.options[0].facts.OIDC); },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: value.options[0].facts.OIDC.evidence }; },
    (value) => { value.options[0].authenticationControls.NATIVE_MOBILE = { EXTERNAL_CUSTOMERS: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].facts.OIDC.evidence } } }; },
    (value) => { value.sourceRelease = "verified-cloud-release"; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("WorkOS client freshness retains an inclusive 90-day boundary without rewriting observations", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 3);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T13:50:08Z"));
    assert.equal(report.evaluationReady, false);
    assert.equal(report.approvalGranted, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("WorkOS Connect coexists with unchanged research and three Directory Sync scopes without inheritance", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "workos" && entry !== draft);
  const before = JSON.stringify(originals);
  const report = await inspectBaselinePack(observed);
  const workos = report.options.filter((option) => option.providerId === "workos");
  assert.equal(workos.length, 5);
  assert.equal(workos.reduce((count, option) => count + option.facts.length, 0), 12);
  for (const option of workos.filter((option) => option.basis !== "CLIENT_SCOPED_DOCUMENTATION_DRAFT")) {
    assert.ok(option.facts.every((fact) => fact.path.startsWith("facts.")));
    assert.deepEqual(report.schemaPathInventory.options.find((entry) => entry.optionId === option.optionId).families[1].recordedPaths, []);
  }
  const directory = originals.find((entry) => entry.catalogVersion === "workos-directory-sync-staging-draft-2026.10.02");
  assert.equal(directory.options[0].facts.SCIM.availability, "OPTIONAL");
  assert.equal(directory.options[0].facts.GROUP_SYNC.availability, "OPTIONAL");
  assert.equal(Object.hasOwn(directory.options[0].facts, "OIDC"), false);
  assert.deepEqual(inspect().options[0].omittedCapabilities, ["ENTERPRISE_SSO", "GROUP_SYNC", "JIT", "MFA", "OAUTH2_APIS", "SAML", "SCIM", "SOCIAL_LOGIN"]);
  const otherClients = report.options.filter((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT" && option.providerId !== "workos");
  assert.equal(otherClients.length, 4);
  assert.equal(otherClients.flatMap((option) => option.facts).filter((fact) => fact.support === "SUPPORTED").length, 8);
  assert.equal(JSON.stringify(originals), before);
});

test("WorkOS mixed client context is deterministic across object and condition ordering", () => {
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.compatibility.clients = Object.fromEntries(Object.entries(option.compatibility.clients).reverse());
  option.compatibility = Object.fromEntries(Object.entries(option.compatibility).reverse());
  for (const fact of [option.facts.OIDC, ...Object.values(option.compatibility.clients)]) fact.conditions.reverse();
  const before = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), before);
});
