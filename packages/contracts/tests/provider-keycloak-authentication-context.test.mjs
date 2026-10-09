import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "keycloak-26.8.0-browser-authentication-controls-draft-2026.10.08");
const observed = new Date("2026-10-08T19:33:03Z");
const commit = "4246609cf2024c85016d3fb1254c3d2533367c31";
const root = `https://github.com/keycloak/keycloak/blob/${commit}/docs/documentation/server_admin/topics/authentication/`;
const prefix = "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.";
const controls = draft.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS;
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const unknownPaths = [prefix + "NON_EXPORTABLE_KEYS", prefix + "PHISHING_RESISTANCE"];

test("Keycloak authentication scope preserves capability versus client/population availability and enforcement types", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourceRelease, "26.8.0");
  assert.equal(option.sourceCommit, commit);
  assert.equal(Object.hasOwn(option, "sourcePlan"), false);
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), [...Object.keys(controls).map((name) => prefix + name).sort(), "facts.MFA"]);
  const auth = option.facts.filter((fact) => fact.path.startsWith(prefix));
  assert.deepEqual(auth.map((fact) => [fact.availability, fact.enforcement]), [
    ["UNKNOWN", "UNKNOWN"], ["SUPPORTED", "UNKNOWN"], ["SUPPORTED", "SUPPORTED"],
  ]);
  assert.ok(auth.every((fact) => !Object.hasOwn(fact, "support")));
  assert.equal(option.facts.find((fact) => fact.path === "facts.MFA").availability, "OPTIONAL");
  assert.ok(option.facts.every((fact) => fact.evidenceStatus === "UNREVIEWED" && fact.freshness === "CURRENT"
    && fact.evidence.sourceUrl.startsWith(root)));
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Keycloak WebAuthn does not turn optional default flows into verified phishing-resistant enforcement", () => {
  const mfa = draft.options[0].facts.MFA.conditions.join(" ");
  const phishing = controls.PHISHING_RESISTANCE.conditions.join(" ");
  assert.match(mfa, /Conditional 2FA only challenges users with a registered credential/);
  assert.match(mfa, /Require the WebAuthn execution and 2FA subflow, disable OTP alternatives/);
  assert.match(phishing, /conditional mechanism-level inference from RP-bound WebAuthn/);
  assert.match(phishing, /Required WebAuthn with OTP disabled and a Required 2FA subflow/);
  assert.match(phishing, /initial enrollment, password reset, recovery, alternate login, existing SSO cookie/);
  assert.match(phishing, /Enforcement remains UNKNOWN/);
  assert.match(phishing, /required mediation is not the same as an enforced authentication flow/);
  assert.match(phishing, /ZITADEL\/BFF and synthetic evaluator are unchanged/);
});

test("Keycloak passkey support does not certify non-exportable keys or inherit device assurance", () => {
  const conditions = controls.NON_EXPORTABLE_KEYS.conditions.join(" ");
  assert.match(conditions, /both synced and device-bound passkeys/);
  assert.match(conditions, /Neither the passkey label nor successful WebAuthn login establishes a non-exportable/);
  assert.match(conditions, /attachment, discoverable credentials and user verification are not proof/);
  assert.match(conditions, /Trusted attestation, Acceptable AAGUIDs, trust anchors/);
  assert.match(conditions, /Default attestation None cannot be treated as a trustworthy/);
  assert.match(conditions, /existing credentials, recovery or cross-device\/synced paths/);
  assert.match(conditions, /UNKNOWN, not provider-wide UNSUPPORTED/);
  assert.match(conditions, /No security key purchase, account, hardware enrollment or device test/);
});

test("Keycloak OIDC step-up keeps essential ACR, LoA reuse and application-operation checks explicit", () => {
  const conditions = controls.STEP_UP_AUTHENTICATION.conditions.join(" ");
  assert.match(conditions, /coherent ascending.*explicit ACR-to-LoA mapping/);
  assert.match(conditions, /OTP example is not evidence of phishing-resistant WebAuthn step-up/);
  assert.match(conditions, /essential acr through the claims parameter.*acr_values is non-essential/);
  assert.match(conditions, /unachievable essential level returns an error/);
  assert.match(conditions, /returned acr.*before the sensitive operation.*parameters can be altered/);
  assert.match(conditions, /0 limits that level to the current authentication/);
  assert.match(conditions, /expired implicit level can yield acr=0/);
  assert.match(conditions, /acr client scope\/mapper.*missing, low or unknown acr fail closed/);
  assert.match(conditions, /LoA numbers are not NIST AAL certifications/);
  assert.match(conditions, /does not gate application business operations automatically/);
});

test("authentication inventory counts control records separately and records unknown enforcement even with supported availability", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 0, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 0, RESIDENCY: 0, AUTHENTICATION_CONTROL: 3 });
  const option = inventory.options[0];
  assert.equal(option.recordedPathCount, 4);
  assert.equal(option.omittedPathCount, 64);
  assert.deepEqual(option.recordedUnknownPaths, unknownPaths);
  assert.deepEqual(option.families[3].recordedPaths, Object.keys(controls).map((name) => prefix + name).sort());
  assert.ok(option.families[3].omittedPaths.includes("authenticationControls.NATIVE_MOBILE.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE"));
  assert.ok(option.families[3].omittedPaths.includes("authenticationControls.BROWSER.EMPLOYEES.PHISHING_RESISTANCE"));
  assert.deepEqual(Object.values(draft.options[0].compatibility), [{}, {}, {}, {}, {}]);
  assert.deepEqual(draft.options[0].residency, {});
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("authentication inspection rejects scope, population, status, family, empty-container, release and trust substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "keycloak-26.8.0-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].plan = "Free managed customer passkeys"; },
    (value) => { value.options[0].deployment = "MANAGED"; },
    (value) => { value.options[0].product = "Red Hat build of Keycloak"; },
    (value) => { value.options[0].region = "DE; all storage verified"; },
    (value) => { value.options[0].configuration = "Brokered workforce and native login; hardware certified"; },
    (value) => { value.options[0].facts.MFA.availability = "MANDATORY"; },
    (value) => { value.options[0].facts.MFA.evidence.sourceUrl = root + "passkeys.adoc"; },
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
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.enforcement = "UNKNOWN"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.evidence.sourceUrl = root.replace(commit, "main") + "webauthn.adoc"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.evidence.sourceUrl = root.replace(commit, "26.8.0") + "webauthn.adoc"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.conditions = []; },
    (value) => { const fact = value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.observedAt = "2026-02-30T19:33:03Z"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.support = "SUPPORTED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER = { support: "SUPPORTED", conditions: ["Not inherited"], evidence: structuredClone(value.options[0].facts.MFA.evidence) }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["DE"], conditions: [], evidence: value.options[0].facts.MFA.evidence }; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("authentication freshness and canonical ordering do not refresh observations or promote assurance", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, unknownPaths);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T19:33:03Z"));
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

test("authentication context coexists with eight other Keycloak scopes and cannot leak into a public-client scope", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "keycloak" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 7);
  assert.ok(originals.every((entry) => Object.keys(entry.options[0].authenticationControls).length === 0));
  const keycloak = (await inspectBaselinePack(observed)).options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 9);
  assert.equal(keycloak.reduce((sum, option) => sum + option.facts.length, 0), 37);
  assert.ok(keycloak.filter((option) => option.basis !== "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => !option.facts.some((fact) => fact.path.startsWith("authenticationControls.") || fact.path === "facts.MFA")));
  const native = keycloak.find((option) => option.basis === "RELEASE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T21:20:39Z");
  const borrowed = structuredClone(originals.find((entry) => entry.catalogVersion === "keycloak-26.8.0-public-oidc-clients-draft-2026.10.08"));
  borrowed.options[0].authenticationControls = structuredClone(draft.options[0].authenticationControls);
  assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
  assert.equal(JSON.stringify(originals), before);
});
