import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "zitadel-cloud-free-browser-authentication-controls-draft-2026.10.08");
const observed = new Date("2026-10-08T20:18:19Z");
const prefix = "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.";
const controls = draft.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS;
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const controlPaths = Object.keys(controls).map((name) => prefix + name).sort();

test("Cloud authentication scope keeps Free proposals typed, unreviewed and distinct from local runtime", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Free");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), [...controlPaths, "facts.MFA"]);
  const auth = option.facts.filter((fact) => fact.path.startsWith(prefix));
  assert.deepEqual(auth.map((fact) => [fact.availability, fact.enforcement]), [
    ["UNKNOWN", "UNKNOWN"], ["SUPPORTED", "UNKNOWN"], ["UNKNOWN", "UNKNOWN"],
  ]);
  assert.ok(auth.every((fact) => !Object.hasOwn(fact, "support")));
  assert.equal(option.facts.find((fact) => fact.path === "facts.MFA").availability, "OPTIONAL");
  assert.ok(option.facts.every((fact) => fact.evidenceStatus === "UNREVIEWED" && fact.freshness === "CURRENT"
    && new URL(fact.evidence.sourceUrl).hostname === "zitadel.com"));
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Cloud MFA preserves policy context, weaker alternatives and unverified account costs", () => {
  const conditions = draft.options[0].facts.MFA.conditions.join(" ");
  assert.match(conditions, /local browser customers/);
  assert.match(conditions, /Force MFA and local-only enforcement/);
  assert.match(conditions, /U2F-with-PIN/);
  assert.match(conditions, /TOTP, email and SMS are weaker alternatives/);
  assert.match(conditions, /organization settings can override instance defaults/);
  assert.match(conditions, /API examples are not observed account settings/);
  assert.match(conditions, /https:\/\/zitadel.com\/pricing/);
  assert.match(conditions, /100 daily active users/);
  assert.match(conditions, /Account entitlement, quotas, deployed version, email\/SMS delivery costs and future zero-cost operation are unverified/);
  assert.match(conditions, /No subscription, account or provider calls occurred/);
});

test("Cloud passkey phishing resistance is not compulsory journey protection or automatic Login V2 verification", () => {
  const conditions = controls.PHISHING_RESISTANCE.conditions.join(" ");
  assert.match(conditions, /conditional mechanism-level inference.*FIDO2\/WebAuthn/);
  assert.match(conditions, /Password fallback can remain available/);
  assert.match(conditions, /enrollment, password reset, recovery, credential removal, existing SSO and administrator paths/);
  assert.match(conditions, /journey enforcement remains UNKNOWN/);
  assert.match(conditions, /initial Login V2 setup limitations.*login-ui\/login-app.*passkey\/U2F setup/);
  assert.match(conditions, /Record this source discrepancy.*do not infer a deployed Cloud version/);
  assert.match(conditions, /management-console domains/);
  assert.match(conditions, /AuthWeave's ZITADEL\/BFF and synthetic evaluator are unchanged/);
});

test("Cloud key non-exportability remains unknown and reauthentication does not certify stronger-factor step-up", () => {
  const keys = controls.NON_EXPORTABLE_KEYS.conditions.join(" ");
  assert.match(keys, /platform or cross-platform.*iCloud Keychain/);
  assert.match(keys, /attestation none and required user verification.*not verified Cloud configuration/);
  assert.match(keys, /Synced, cross-device, existing credential and recovery paths/);
  assert.match(keys, /custom-UI API is context only/);
  assert.match(keys, /UNKNOWN, not provider-wide UNSUPPORTED/);
  assert.match(keys, /No custom login implementation, security-key purchase, hardware enrollment/);
  const step = controls.STEP_UP_AUTHENTICATION.conditions.join(" ");
  assert.match(step, /max_age and prompt=login/);
  assert.match(step, /step-up means requesting and verifying stronger authentication, not repeating the same login/);
  assert.match(step, /parameters alone do not establish that capability; availability remains UNKNOWN/);
  assert.match(step, /token refresh.*cached SSO session is not proof/);
  assert.match(step, /signed token issuer\/audience\/nonce.*auth_time.*factor freshness/);
  assert.match(step, /absent or insufficient evidence must deny/);
  assert.match(step, /does not establish an essential-ACR higher-assurance contract/);
  assert.match(step, /Enforcement stays UNKNOWN/);
  assert.match(step, /Do not inherit Keycloak LoA semantics, NIST AAL/);
});

test("Cloud authentication inventory keeps capability counts separate and all unknown enforcement paths visible", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 0, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 0, RESIDENCY: 0, AUTHENTICATION_CONTROL: 3 });
  assert.equal(inventory.options[0].recordedPathCount, 4);
  assert.equal(inventory.options[0].omittedPathCount, 64);
  assert.deepEqual(inventory.options[0].recordedUnknownPaths, controlPaths);
  assert.deepEqual(inventory.options[0].families[3].recordedPaths, controlPaths);
  assert.ok(inventory.options[0].families[3].omittedPaths.includes("authenticationControls.NATIVE_MOBILE.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE"));
  assert.ok(inventory.options[0].families[3].omittedPaths.includes("authenticationControls.BROWSER.EMPLOYEES.PHISHING_RESISTANCE"));
  assert.deepEqual(Object.values(draft.options[0].compatibility), [{}, {}, {}, {}, {}]);
  assert.deepEqual(draft.options[0].residency, {});
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("Cloud authentication inspection rejects scope, population, source, empty-container and trust substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "zitadel-cloud-free-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].plan = "Pro; paid custom domain"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].product = "ZITADEL local identity lab"; },
    (value) => { value.options[0].region = "CH; all storage verified"; },
    (value) => { value.options[0].configuration = "External workforce, native login and certified hardware"; },
    (value) => { value.options[0].facts.MFA.availability = "MANDATORY"; },
    (value) => { value.options[0].facts.MFA.evidence.sourceUrl = "https://zitadel.com/pricing"; },
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
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.evidence.sourceUrl = "https://zitadel.com/docs/guides/integrate/login-ui/login-app"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.sourceUrl = "https://example.com/passkey"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.conditions = []; },
    (value) => { const fact = value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.observedAt = "2026-02-30T20:18:19Z"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.support = "SUPPORTED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER = { support: "SUPPORTED", conditions: ["Not inherited"], evidence: structuredClone(value.options[0].facts.MFA.evidence) }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: value.options[0].facts.MFA.evidence }; },
    (value) => { value.sourceRelease = "3.4.0"; },
    (value) => { value.approvalGranted = true; },
  ];
  mutations.forEach((change, index) => {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed), `mutation ${index}`);
  });
});

test("Cloud authentication freshness and canonical ordering preserve observations and unknown enforcement", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, controlPaths);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T20:18:19Z"));
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

test("Cloud authentication coexists with seven older ZITADEL scopes and never borrows Keycloak enforcement", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "zitadel" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 6);
  assert.ok(originals.every((entry) => Object.keys(entry.options[0].authenticationControls).length === 0));
  const report = await inspectBaselinePack(observed);
  const zitadel = report.options.filter((option) => option.providerId === "zitadel");
  assert.equal(zitadel.length, 8);
  assert.equal(zitadel.reduce((sum, option) => sum + option.facts.length, 0), 28);
  assert.ok(zitadel.filter((option) => option.basis !== "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => !option.facts.some((fact) => fact.path.startsWith("authenticationControls.") || fact.path === "facts.MFA")));
  const native = zitadel.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T21:44:10Z");
  const keycloak = report.options.find((option) => option.providerId === "keycloak" && option.basis === "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(keycloak.facts.find((fact) => fact.path === prefix + "STEP_UP_AUTHENTICATION").enforcement, "SUPPORTED");
  assert.equal(inspect().options[0].facts.find((fact) => fact.path === prefix + "STEP_UP_AUTHENTICATION").enforcement, "UNKNOWN");
  const borrowed = structuredClone(originals.find((entry) => entry.catalogVersion === "zitadel-cloud-free-public-oidc-clients-draft-2026.10.08"));
  borrowed.options[0].authenticationControls = structuredClone(draft.options[0].authenticationControls);
  assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
  assert.equal(JSON.stringify(originals), before);
});
