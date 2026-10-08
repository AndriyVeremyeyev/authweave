import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "auth0-b2b-free-browser-authentication-controls-draft-2026.10.08");
const observed = new Date("2026-10-08T20:50:27Z");
const prefix = "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.";
const controls = draft.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS;
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const controlPaths = Object.keys(controls).map((name) => prefix + name).sort();
const unknownPaths = [...controlPaths, "facts.MFA"];

test("Auth0 Free authentication proposals stay typed, unreviewed and separate from runtime", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "B2B Free");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), unknownPaths);
  const auth = option.facts.filter((fact) => fact.path.startsWith(prefix));
  assert.deepEqual(auth.map((fact) => [fact.availability, fact.enforcement]), [
    ["UNKNOWN", "UNKNOWN"], ["SUPPORTED", "UNKNOWN"], ["UNKNOWN", "UNKNOWN"],
  ]);
  assert.ok(auth.every((fact) => !Object.hasOwn(fact, "support")));
  assert.equal(option.facts.find((fact) => fact.path === "facts.MFA").availability, "UNKNOWN");
  assert.ok(option.facts.every((fact) => fact.evidenceStatus === "UNREVIEWED" && fact.freshness === "CURRENT"
    && new URL(fact.evidence.sourceUrl).hostname === "auth0.com"));
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Auth0 Free passkey inclusion does not borrow paid configurable MFA entitlement", () => {
  const conditions = draft.options[0].facts.MFA.conditions.join(" ");
  assert.match(conditions, /local browser customers.*Auth0 Database Connection.*Universal Login/);
  assert.match(conditions, /Free pricing includes passkeys but excludes Pro MFA factors/);
  assert.match(conditions, /Generic MFA factors are subscription-dependent/);
  assert.match(conditions, /biometric\/PIN prompt.*independent-factor properties.*effective tenant policy/);
  assert.match(conditions, /MFA UNKNOWN, not provider-wide UNAVAILABLE; no paid upgrade is proposed/);
  assert.match(conditions, /brokered workforce, native\/mobile, M2M, trial, paid or custom-database/);
  assert.match(conditions, /future zero-cost guarantee/);
  assert.match(conditions, /No tenant, subscription, account, credential, enrollment, provider call or policy mutation occurred/);
});

test("Auth0 passkey phishing resistance preserves password fallback and enrollment exceptions", () => {
  const conditions = controls.PHISHING_RESISTANCE.conditions.join(" ");
  assert.match(conditions, /conditional mechanism-level inference from domain-bound WebAuthn/);
  assert.match(conditions, /Universal Login, Identifier First.*disable Classic custom login pages/);
  assert.match(conditions, /Custom database without import is Early Access and outside this scope/);
  assert.match(conditions, /must still have passwords enabled/);
  assert.match(conditions, /Organization invitation signup initially uses a password; progressive enrollment can be delayed/);
  assert.match(conditions, /Enrollment, password reset, recovery, credential removal, existing SSO and administrator paths/);
  assert.match(conditions, /Journey enforcement remains UNKNOWN/);
  assert.match(conditions, /AuthWeave's ZITADEL\/BFF and synthetic evaluator are unchanged/);
});

test("Auth0 synced passkeys and generic MFA Actions do not certify key protection or stronger Free step-up", () => {
  const keys = controls.NON_EXPORTABLE_KEYS.conditions.join(" ");
  assert.match(keys, /syncing credentials across devices/);
  assert.match(keys, /trusted attestation.*key-protection policy are not verified/);
  assert.match(keys, /Synced, cross-device, existing credential and recovery paths/);
  assert.match(keys, /UNKNOWN, not provider-wide UNSUPPORTED/);
  assert.match(keys, /subscription-dependent WebAuthn MFA security-key examples as Free entitlement/);
  const step = controls.STEP_UP_AUTHENTICATION.conditions.join(" ");
  assert.match(step, /Actions requesting MFA.*ID-token amr claim/);
  assert.match(step, /generic guide does not verify Free-plan factor entitlement/);
  assert.match(step, /api\.multifactor\.enable\('any'\).*allowRememberBrowser/);
  assert.match(step, /do not prove a selected stronger factor.*essential-ACR/);
  assert.match(step, /signed token issuer\/audience\/expiry.*required factor and freshness/);
  assert.match(step, /Absent or insufficient evidence must deny.*stronger authentication, not repeating the same login/);
  assert.match(step, /amr can be absent after silent authentication or refresh/);
  assert.match(step, /remember-browser bypass.*fresh stronger authentication/);
  assert.match(step, /enforcement stays UNKNOWN/);
});

test("Auth0 authentication inventory retains unknown MFA and enforcement without widening capability counts", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 0, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 1 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 0, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 0, RESIDENCY: 0, AUTHENTICATION_CONTROL: 3 });
  assert.equal(inventory.options[0].recordedPathCount, 4);
  assert.equal(inventory.options[0].omittedPathCount, 64);
  assert.deepEqual(inventory.options[0].recordedUnknownPaths, unknownPaths);
  assert.deepEqual(inventory.options[0].families[3].recordedPaths, controlPaths);
  assert.ok(inventory.options[0].families[3].omittedPaths.includes("authenticationControls.NATIVE_MOBILE.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE"));
  assert.ok(inventory.options[0].families[3].omittedPaths.includes("authenticationControls.BROWSER.EMPLOYEES.PHISHING_RESISTANCE"));
  assert.deepEqual(Object.values(draft.options[0].compatibility), [{}, {}, {}, {}, {}]);
  assert.deepEqual(draft.options[0].residency, {});
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("Auth0 authentication inspection rejects entitlement, population, source and trust substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "auth0-b2b-free-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].plan = "Professional; Pro MFA"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].product = "Auth0 trial tenant"; },
    (value) => { value.options[0].region = "US; all storage verified"; },
    (value) => { value.options[0].configuration = "Paid MFA, native clients and certified hardware"; },
    (value) => { value.options[0].facts.MFA.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.MFA.evidence.sourceUrl = "https://auth0.com/docs/secure/multi-factor-authentication"; },
    (value) => { value.options[0].facts.OIDC = structuredClone(value.options[0].facts.MFA); },
    (value) => { value.options[0].authenticationControls.NATIVE_MOBILE = structuredClone(value.options[0].authenticationControls.BROWSER); },
    (value) => { value.options[0].authenticationControls.BROWSER.EMPLOYEES = structuredClone(value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS); },
    (value) => { value.options[0].authenticationControls.NATIVE_MOBILE = {}; },
    (value) => { value.options[0].authenticationControls.BROWSER.EMPLOYEES = {}; },
    (value) => { value.options[0].authenticationControls.MACHINE_TO_MACHINE = {}; },
    (value) => { delete value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.enforcement = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.availability = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.enforcement = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.enforcement = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.availability = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.evidence.sourceUrl = "https://auth0.com/docs/authenticate/database-connections/passkeys"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.sourceUrl = "https://example.com/passkeys"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.conditions = []; },
    (value) => { const fact = value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.observedAt = "2026-02-30T20:50:27Z"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.support = "SUPPORTED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER = { support: "SUPPORTED", conditions: ["Not inherited"], evidence: structuredClone(value.options[0].facts.MFA.evidence) }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: value.options[0].facts.MFA.evidence }; },
    (value) => { value.sourceRelease = "26.8.0"; },
    (value) => { value.approvalGranted = true; },
  ];
  mutations.forEach((change, index) => {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed), "mutation " + index);
  });
});

test("Auth0 authentication freshness and ordering preserve all four unknown paths", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, unknownPaths);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T20:50:27Z"));
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  const reordered = structuredClone(draft);
  const values = reordered.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS;
  reordered.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS = Object.fromEntries(Object.entries(values).reverse());
  for (const fact of [reordered.options[0].facts.MFA, ...Object.values(values)]) fact.conditions.reverse();
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(draft), before);
});

test("Auth0 authentication coexists with seven older scopes without borrowing Keycloak step-up", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "auth0" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 6);
  assert.ok(originals.every((entry) => Object.keys(entry.options[0].authenticationControls).length === 0));
  const report = await inspectBaselinePack(observed);
  const auth0 = report.options.filter((option) => option.providerId === "auth0");
  assert.equal(auth0.length, 8);
  assert.equal(auth0.reduce((sum, option) => sum + option.facts.length, 0), 26);
  assert.ok(auth0.filter((option) => option.basis !== "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => !option.facts.some((fact) => fact.path.startsWith("authenticationControls.") || fact.path === "facts.MFA")));
  const native = auth0.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T22:07:48Z");
  const keycloak = report.options.find((option) => option.providerId === "keycloak" && option.basis === "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(keycloak.facts.find((fact) => fact.path === prefix + "STEP_UP_AUTHENTICATION").enforcement, "SUPPORTED");
  assert.equal(inspect().options[0].facts.find((fact) => fact.path === prefix + "STEP_UP_AUTHENTICATION").enforcement, "UNKNOWN");
  const borrowed = structuredClone(originals.find((entry) => entry.catalogVersion === "auth0-b2b-free-public-oidc-clients-draft-2026.10.08"));
  borrowed.options[0].authenticationControls = structuredClone(draft.options[0].authenticationControls);
  assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
  assert.equal(JSON.stringify(originals), before);
});
