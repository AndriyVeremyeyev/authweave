import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "entra-external-id-basic-public-oidc-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T07:14:50Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("Entra public OIDC clients retain external-tenant scope without Basic entitlement or review", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Basic MAU");
  assert.equal(option.product, "Microsoft Entra External ID - external tenant");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(report.factCount, 3);
  assert.deepEqual(option.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.deepEqual(option.facts.map((fact) => fact.support ?? fact.availability), ["SUPPORTED", "SUPPORTED", "OPTIONAL"]);
  for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.equal(new URL(fact.evidence.sourceUrl).hostname, "learn.microsoft.com");
  }
  const conditions = draft.options[0].facts.OIDC.conditions.join(" ");
  assert.match(conditions, /this external directory only.*ciamlogin.com.*not workforce/);
  assert.match(conditions, /one intended sign-up\/sign-in user flow/);
  assert.match(conditions, /not a claim of S256-only server enforcement.*plain PKCE/);
  assert.match(conditions, /not a trial entitlement or zero-cost guarantee/);
  assert.match(conditions, /mutable and dated, not release-pinned/);
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Entra SPA CORS and browser-delegated mobile prerequisites do not become security or API claims", () => {
  const option = draft.options[0];
  assert.match(option.compatibility.clients.BROWSER.conditions.join(" "), /platform type spa.*CORS.*label alone is not equivalent/);
  const mobile = option.compatibility.clients.NATIVE_MOBILE.conditions.join(" ");
  assert.match(mobile, /external user-agent under RFC 8252.*native authentication is a separate approach/);
  assert.match(mobile, /Android package name and signing hash or iOS bundle ID/);
  assert.match(mobile, /PKCE is not proof of callback ownership/);
  assert.match(mobile, /multi-tenant account types and trial.*Reconcile those prerequisites/);
  assert.match(mobile, /quickstart enables it.*not required for browser-delegated.*Do not equate/);
  assert.match(mobile, /API authorization are not established/);
  assert.deepEqual(option.authenticationControls, {});
  assert.deepEqual(option.residency, {});
  assert.equal(Object.hasOwn(option.facts, "OAUTH2_APIS"), false);
  assert.ok(inspect().options[0].facts.every((fact) => fact.evidenceStatus === "UNREVIEWED"));
});

test("Entra inventory separates public-client compatibility from optional OIDC and omitted scopes", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 2, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 2, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  const entry = inventory.options[0];
  assert.equal(entry.recordedPathCount, 3);
  assert.equal(entry.omittedPathCount, 65);
  assert.deepEqual(entry.recordedUnknownPaths, []);
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.clients.MACHINE_TO_MACHINE"));
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS"));
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("Entra client inspection rejects workforce/B2C/trial conflation, source substitution and fabricated authority", () => {
  const mutations = [
    (value) => { value.catalogVersion = "entra-external-id-public-latest"; },
    (value) => { value.options[0].id = "entra-external-id-basic-standard-native"; },
    (value) => { value.options[0].providerId = "entra-id-workforce"; },
    (value) => { value.options[0].product = "Microsoft Entra workforce tenant"; },
    (value) => { value.options[0].product = "Azure AD B2C"; },
    (value) => { value.options[0].plan = "30-day premium trial"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "EU; all storage verified"; },
    (value) => { value.options[0].configuration = "HSC native authentication; confidential BFF with secret"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.support = "UNKNOWN"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.support = "UNSUPPORTED"; },
    (value) => { delete value.options[0].compatibility.clients.NATIVE_MOBILE; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.sourceUrl = value.options[0].facts.OIDC.evidence.sourceUrl; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.evidence.sourceUrl = "https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-native-authentication-single-page-app-sdk-sign-in"; },
    (value) => { value.options[0].facts.OIDC.evidence.sourceUrl = "https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-client-creds-grant-flow"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.clients.BROWSER; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OIDC.evidence.observedAt = "2026-02-30T07:14:50Z"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OIDC); },
    (value) => { value.options[0].facts.SAML = structuredClone(value.options[0].facts.OIDC); },
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

test("Entra client freshness has an inclusive 90-day boundary without refreshing observations", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 3);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T07:14:50Z"));
    assert.equal(report.evaluationReady, false);
    assert.equal(report.approvalGranted, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("Entra public clients coexist with unchanged research/native/workforce entries without inheriting provisioning", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "entra-external-id" && entry !== draft);
  const before = JSON.stringify(originals);
  const report = await inspectBaselinePack(observed);
  const entra = report.options.filter((option) => option.providerId === "entra-external-id");
  assert.equal(entra.length, 7);
  for (const option of entra.filter((option) => !["CLIENT_SCOPED_DOCUMENTATION_DRAFT",
    "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT", "MACHINE_SCOPED_DOCUMENTATION_DRAFT"].includes(option.basis))) {
    assert.ok(option.facts.every((fact) => fact.path.startsWith("facts.")));
    assert.deepEqual(report.schemaPathInventory.options.find((entry) => entry.optionId === option.optionId).families[1].recordedPaths, []);
  }
  const organizations = entra.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(organizations.facts.every((fact) => fact.path.startsWith("compatibility.")
    && !fact.path.startsWith("compatibility.clients.")));
  assert.deepEqual(organizations.omittedCapabilities, ["ENTERPRISE_SSO", "GROUP_SYNC", "JIT", "MFA",
    "OAUTH2_APIS", "OIDC", "SAML", "SCIM", "SOCIAL_LOGIN"]);
  assert.deepEqual(inspect().options[0].omittedCapabilities, ["ENTERPRISE_SSO", "GROUP_SYNC", "JIT", "MFA", "OAUTH2_APIS", "SAML", "SCIM", "SOCIAL_LOGIN"]);
  const native = originals.find((entry) => entry.catalogVersion === "entra-external-id-basic-draft-2026.10.02");
  assert.equal(native.options[0].facts.OIDC.evidence.observedAt, "2026-10-02T23:12:01Z");
  assert.equal(native.options[0].facts.SCIM.availability, "UNKNOWN");
  assert.equal(native.options[0].facts.SAML.availability, "OPTIONAL");
  assert.equal(JSON.stringify(originals), before);
});

test("Entra context inspection is deterministic across object and condition ordering", () => {
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.compatibility.clients = Object.fromEntries(Object.entries(option.compatibility.clients).reverse());
  option.compatibility = Object.fromEntries(Object.entries(option.compatibility).reverse());
  for (const fact of [option.facts.OIDC, ...Object.values(option.compatibility.clients)]) fact.conditions.reverse();
  const before = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), before);
});
