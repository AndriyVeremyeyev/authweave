import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "workos-authkit-staging-browser-authentication-controls-draft-2026.10.10");
const observed = new Date("2026-10-08T21:09:22Z");
const prefix = "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.";
const controls = draft.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS;
const controlPaths = Object.keys(controls).map((name) => prefix + name).sort();
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("WorkOS primary hosted authentication proposals remain staging-only, typed and unreviewed", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.product, "WorkOS AuthKit");
  assert.equal(option.sourcePlan, "Staging");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(report.factCount, 8);
  assert.deepEqual(option.facts.filter(fact => !fact.path.startsWith("residency.")).map((fact) => fact.path), [...controlPaths, "facts.MFA"]);
  assert.deepEqual(option.facts.filter((fact) => fact.path.startsWith(prefix)).map((fact) => [fact.availability, fact.enforcement]), [
    ["UNKNOWN", "UNKNOWN"], ["SUPPORTED", "UNKNOWN"], ["UNKNOWN", "UNKNOWN"],
  ]);
  assert.equal(option.facts.find((fact) => fact.path === "facts.MFA").availability, "OPTIONAL");
  assert.ok(option.facts.filter(fact => !fact.path.startsWith("residency.")).every((fact) => fact.evidenceStatus === "UNREVIEWED" && fact.freshness === "CURRENT"
    && new URL(fact.evidence.sourceUrl).hostname === "workos.com"));
  assert.ok(option.facts.filter((fact) => fact.path.startsWith(prefix)).every((fact) => !Object.hasOwn(fact, "support")));
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("WorkOS hosted TOTP keeps SSO exceptions, organization context and no-cost testing boundaries", () => {
  const conditions = draft.options[0].facts.MFA.conditions.join(" ");
  assert.match(conditions, /local non-SSO browser customers.*primary hosted AuthKit in staging/);
  assert.match(conditions, /not AuthKit Connect, Directory Sync, native\/mobile, brokered workforce or the standalone SMS MFA API/);
  assert.match(conditions, /authenticator-app TOTP.*not an observed mandatory policy/);
  assert.match(conditions, /TOTP is not equivalent to phishing resistance/);
  assert.match(conditions, /SSO users are exempt.*selected organization for non-SSO members/);
  assert.match(conditions, /environment\/organization settings and user context remain unverified/);
  assert.match(conditions, /Staging is offered for no-cost testing/);
  assert.match(conditions, /production billing, enterprise connections and paid custom domains are not included/);
  assert.match(conditions, /No account, subscription, credential, enrollment, environment or policy was created/);
});

test("WorkOS domain-bound passkeys are not mandatory enrollment or complete customer-journey protection", () => {
  const conditions = controls.PHISHING_RESISTANCE.conditions.join(" ");
  assert.match(conditions, /conditional mechanism-level inference from domain-bound WebAuthn/);
  assert.match(conditions, /HTTPS WorkOS staging RP\/domain/);
  assert.match(conditions, /Progressive enrollment is off by default and can be skipped, including future reminders/);
  assert.match(conditions, /user verification instead of a separate TOTP.*not a passkey-only policy/);
  assert.match(conditions, /Password, Magic Auth and TOTP alternatives/);
  assert.match(conditions, /recovery, administrator credential removal and existing SSO\/session paths/);
  assert.match(conditions, /enforcement remains UNKNOWN/);
  assert.match(conditions, /custom domains are production-only.*Domain migration can invalidate credentials/);
  assert.match(conditions, /AuthWeave's ZITADEL\/BFF and synthetic evaluator are unchanged/);
});

test("WorkOS account-management APIs and recent sign-in do not prove hardware protection or stronger step-up", () => {
  const keys = controls.NON_EXPORTABLE_KEYS.conditions.join(" ");
  assert.match(keys, /Widgets API.*registration options.*does not establish a hardware-only attestation policy/);
  assert.match(keys, /Synced, cross-device, existing credential and recovery paths/);
  assert.match(keys, /UNKNOWN, not provider-wide UNSUPPORTED/);
  assert.match(keys, /Hosted login and authenticated Widgets account management are distinct surfaces/);
  assert.match(keys, /elevatedAccessToken.*token name and opaque registration options do not certify/);
  const step = controls.STEP_UP_AUTHENTICATION.conditions.join(" ");
  assert.match(step, /max_age.*auth_time advanced by active authentication, not refresh/);
  assert.match(step, /AuthKit chooses a password, MFA factor or upstream SSO method/);
  assert.match(step, /stronger authentication, not repeating the same login/);
  assert.match(step, /essential-ACR.*availability remains UNKNOWN/);
  assert.match(step, /signed issuer\/audience\/expiry.*callback state binding.*required factor freshness/);
  assert.match(step, /Missing, malformed, future or insufficient evidence must deny/);
  assert.match(step, /step-up terminology describes recency.*not automatic NIST AAL/);
  assert.match(step, /Stronger-factor enforcement stays UNKNOWN/);
});

test("WorkOS authentication inventory separates MFA capability from unknown control enforcement", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 0, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 0, RESIDENCY: 4, AUTHENTICATION_CONTROL: 3 });
  assert.equal(inventory.options[0].recordedPathCount, 8);
  assert.equal(inventory.options[0].omittedPathCount, 60);
  assert.deepEqual(inventory.options[0].recordedUnknownPaths, [...controlPaths, ...["AUDIT_LOGS", "BACKUPS", "CREDENTIALS", "USER_PROFILES"].map(category => `residency.${category}`)]);
  assert.deepEqual(inventory.options[0].families[3].recordedPaths, controlPaths);
  assert.ok(inventory.options[0].families[3].omittedPaths.includes("authenticationControls.NATIVE_MOBILE.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE"));
  assert.ok(inventory.options[0].families[3].omittedPaths.includes("authenticationControls.BROWSER.EMPLOYEES.PHISHING_RESISTANCE"));
  assert.deepEqual(Object.values(draft.options[0].compatibility), [{}, {}, {}, {}, {}]);
  assert.equal(Object.keys(draft.options[0].residency).length, 4);
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("WorkOS inspection rejects product, environment, population, source and trust substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "workos-connect-staging-public-oidc-clients-draft-2026.10.10"; },
    (value) => { value.options[0].plan = "Production; AuthKit Free 1M MAU"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].product = "WorkOS AuthKit Connect"; },
    (value) => { value.options[0].product = "WorkOS Directory Sync"; },
    (value) => { value.options[0].region = "US; all storage verified"; },
    (value) => { value.options[0].configuration = "Native clients and enforced hardware-only authentication"; },
    (value) => { value.options[0].facts.MFA.availability = "MANDATORY"; },
    (value) => { value.options[0].facts.MFA.evidence.sourceUrl = "https://workos.com/docs/mfa"; },
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
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.evidence.sourceUrl = "https://workos.com/pricing"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.sourceUrl = "https://example.com/passkeys"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.conditions = []; },
    (value) => { const fact = value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.observedAt = "2026-02-30T21:09:22Z"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.support = "SUPPORTED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER = { support: "SUPPORTED", conditions: ["Not inherited"], evidence: structuredClone(value.options[0].facts.MFA.evidence) }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: value.options[0].facts.MFA.evidence }; },
    (value) => { value.sourceRelease = "verified-cloud-release"; },
    (value) => { value.approvalGranted = true; },
  ];
  mutations.forEach((change, index) => {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed), "mutation " + index);
  });
});

test("WorkOS authentication freshness and ordering preserve evidence and unknown enforcement", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.options[0].facts.filter(fact => !fact.path.startsWith("residency.") && fact.freshness === freshness).length, 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, [...controlPaths, ...["AUDIT_LOGS", "BACKUPS", "CREDENTIALS", "USER_PROFILES"].map(category => `residency.${category}`)]);
    assert.ok(report.options[0].facts.filter(fact => !fact.path.startsWith("residency.")).every((fact) => fact.evidence.observedAt === "2026-10-08T21:09:22Z"));
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

test("WorkOS hosted authentication does not populate seven older research, Directory Sync or Connect scopes", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "workos" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 6);
  assert.ok(originals.every((entry) => Object.keys(entry.options[0].authenticationControls).length === 0));
  const report = await inspectBaselinePack(observed);
  const workos = report.options.filter((option) => option.providerId === "workos");
  assert.equal(workos.length, 8);
  assert.equal(workos.reduce((sum, option) => sum + option.facts.length, 0), 34);
  assert.ok(workos.filter((option) => option.basis !== "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => !option.facts.some((fact) => fact.path.startsWith("authenticationControls.") || fact.path === "facts.MFA")));
  const directory = workos.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(directory.product, "WorkOS Directory Sync");
  assert.equal(directory.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T22:46:13Z");
  assert.equal(directory.facts.some((fact) => fact.path === "facts.OIDC"), false);
  const connect = workos.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(connect.product, "WorkOS AuthKit Connect");
  assert.equal(connect.facts.find((fact) => fact.path === "compatibility.clients.BROWSER").support, "UNKNOWN");
  const borrowed = structuredClone(originals.find((entry) => entry.catalogVersion === "workos-connect-staging-public-oidc-clients-draft-2026.10.10"));
  borrowed.options[0].authenticationControls = structuredClone(draft.options[0].authenticationControls);
  assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
  const keycloak = report.options.find((option) => option.providerId === "keycloak" && option.basis === "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(keycloak.facts.find((fact) => fact.path === prefix + "STEP_UP_AUTHENTICATION").enforcement, "SUPPORTED");
  assert.equal(controls.STEP_UP_AUTHENTICATION.enforcement, "UNKNOWN");
  assert.equal(JSON.stringify(originals), before);
});
