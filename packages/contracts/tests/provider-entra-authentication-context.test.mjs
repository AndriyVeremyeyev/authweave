import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "entra-external-id-basic-browser-authentication-controls-draft-2026.10.08");
const observed = new Date("2026-10-08T21:34:08Z");
const prefix = "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.";
const controls = draft.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS;
const controlPaths = Object.keys(controls).map((name) => prefix + name).sort();
const unknownPaths = [prefix + "NON_EXPORTABLE_KEYS", prefix + "STEP_UP_AUTHENTICATION"];
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("Entra customer authentication proposals remain external-tenant scoped, typed and unreviewed", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.product, "Microsoft Entra External ID - external tenant");
  assert.equal(option.sourcePlan, "Basic MAU");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), [...controlPaths, "facts.MFA"]);
  assert.deepEqual(option.facts.filter((fact) => fact.path.startsWith(prefix)).map((fact) => [fact.availability, fact.enforcement]), [
    ["UNKNOWN", "UNKNOWN"], ["SUPPORTED", "UNSUPPORTED"], ["SUPPORTED", "UNKNOWN"],
  ]);
  assert.equal(option.facts.find((fact) => fact.path === "facts.MFA").availability, "OPTIONAL");
  assert.ok(option.facts.every((fact) => fact.evidenceStatus === "UNREVIEWED" && fact.freshness === "CURRENT"
    && new URL(fact.evidence.sourceUrl).hostname === "learn.microsoft.com"));
  assert.ok(option.facts.filter((fact) => fact.path.startsWith(prefix)).every((fact) => !Object.hasOwn(fact, "support")));
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Entra configurable MFA preserves first-factor, policy-exclusion and separate-cost boundaries", () => {
  const conditions = draft.options[0].facts.MFA.conditions.join(" ");
  assert.match(conditions, /local email\/password or username\/password browser customers/);
  assert.match(conditions, /No workforce B2B guests, Azure AD B2C, native authentication APIs, upstream federation, SCIM or paid M2M/);
  assert.match(conditions, /email OTP used as first factor cannot also be the second factor/);
  assert.match(conditions, /Email OTP is not phishing resistance/);
  assert.match(conditions, /Policy exclusions.*OPTIONAL is not observed compulsory use/);
  assert.match(conditions, /subscription, quota and entitlement are unverified.*SMS has separate charges and is excluded/);
  assert.match(conditions, /No tenant, account, subscription, credential, enrollment, custom domain, Azure resource or policy was created/);
});

test("Entra passkey availability is not Conditional Access phishing-resistant enforcement or free infrastructure", () => {
  const conditions = controls.PHISHING_RESISTANCE.conditions.join(" ");
  assert.match(conditions, /domain-bound FIDO2 passkeys.*completed MFA and app-built credential management/);
  assert.match(conditions, /does not cover email-OTP-first, social\/federated users, embedded webviews or native authentication APIs/);
  assert.match(conditions, /UNSUPPORTED enforcement is scoped to.*external-tenant Conditional Access authentication strengths/);
  assert.match(conditions, /Requiring generic MFA does not require passkeys/);
  assert.match(conditions, /not a provider-wide rejection/);
  assert.match(conditions, /custom URL domain.*Azure Front Door route incurs separate charges/);
  assert.match(conditions, /No paid infrastructure was selected.*not a zero-total-cost promise/);
  assert.match(conditions, /Enrollment, recovery, fallback and existing sessions need independent review/);
});

test("Entra profile metadata and acrs presence alone do not prove hardware or stronger operation enforcement", () => {
  const keys = controls.NON_EXPORTABLE_KEYS.conditions.join(" ");
  assert.match(keys, /Device-bound labeling alone does not establish.*hardware-backed non-exportability/);
  assert.match(keys, /Attestation validates make\/model.*hardware key-protection evidence.*unverified/);
  assert.match(keys, /Without attestation an AAGUID is not strict assurance/);
  assert.match(keys, /registration-time attestation changes do not block previously registered unattested credentials/);
  assert.match(keys, /Overlapping profiles, pre-existing credentials, fallback and recovery/);
  assert.match(keys, /UNKNOWN, not provider-wide UNSUPPORTED/);
  const step = controls.STEP_UP_AUTHENTICATION.conditions.join(" ");
  assert.match(step, /password-to-MFA elevation.*not prompt=login, max_age, refresh, or repeating the same factor/);
  assert.match(step, /insufficient_claims challenge with essential acrs/);
  assert.match(step, /acrs value can be issued without an attached policy.*not stronger-factor proof/);
  assert.match(step, /signed issuer\/audience\/expiry.*exact acrs mapping.*required factor freshness at the server operation gate/);
  assert.match(step, /Missing, malformed, future or insufficient evidence must deny/);
  assert.match(step, /MFA satisfied earlier does not guarantee a new prompt/);
  assert.match(step, /P1 licensing and a Free-edition limitation.*external-tenant guides list authentication context/);
  assert.match(step, /Basic MAU entitlement and effective policy remain unresolved.*enforcement stays UNKNOWN/);
  assert.match(step, /AuthWeave's ZITADEL\/BFF and synthetic evaluator are unchanged/);
});

test("Entra authentication inventory distinguishes a scoped negative from unknown and omitted controls", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
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

test("Entra inspection rejects workforce, free-cost, control, population, source and trust substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "entra-external-id-basic-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].id = "entra-external-id-basic-standard-native"; },
    (value) => { value.options[0].plan = "Basic MAU; unlimited free custom domains and P1"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].product = "Microsoft Entra workforce tenant"; },
    (value) => { value.options[0].product = "Azure AD B2C"; },
    (value) => { value.options[0].region = "EU; all storage verified"; },
    (value) => { value.options[0].configuration = "Native clients and enforced hardware-only authentication"; },
    (value) => { value.options[0].facts.MFA.availability = "MANDATORY"; },
    (value) => { value.options[0].facts.MFA.evidence.sourceUrl = "https://learn.microsoft.com/en-us/entra/external-id/b2b-tutorial-require-mfa"; },
    (value) => { value.options[0].facts.OIDC = structuredClone(value.options[0].facts.MFA); },
    (value) => { value.options[0].authenticationControls.NATIVE_MOBILE = structuredClone(value.options[0].authenticationControls.BROWSER); },
    (value) => { value.options[0].authenticationControls.BROWSER.EMPLOYEES = structuredClone(value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS); },
    (value) => { value.options[0].authenticationControls.NATIVE_MOBILE = {}; },
    (value) => { value.options[0].authenticationControls.BROWSER.EMPLOYEES = {}; },
    (value) => { value.options[0].authenticationControls.MACHINE_TO_MACHINE = {}; },
    (value) => { delete value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.enforcement = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.enforcement = "UNKNOWN"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.availability = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.enforcement = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.enforcement = "SUPPORTED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.availability = "UNKNOWN"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.evidence.sourceUrl = "https://learn.microsoft.com/en-us/entra/identity/authentication/concept-authentication-strengths"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE.conditions = []; },
    (value) => { const fact = value.options[0].facts.MFA; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.NON_EXPORTABLE_KEYS.evidence.observedAt = "2026-02-30T21:34:08Z"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.STEP_UP_AUTHENTICATION.support = "SUPPORTED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER = { support: "SUPPORTED", conditions: ["Not inherited"], evidence: structuredClone(value.options[0].facts.MFA.evidence) }; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: value.options[0].facts.MFA.evidence }; },
    (value) => { value.approvalGranted = true; },
  ];
  mutations.forEach((change, index) => {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed), "mutation " + index);
  });
});

test("Entra authentication freshness and ordering preserve scoped negatives and unresolved evidence", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, unknownPaths);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T21:34:08Z"));
    assert.equal(report.options[0].facts.find((fact) => fact.path === prefix + "PHISHING_RESISTANCE").enforcement, "UNSUPPORTED");
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

test("Entra local-customer authentication does not populate seven older research, federation or paid M2M scopes", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "entra-external-id" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 6);
  assert.ok(originals.every((entry) => Object.keys(entry.options[0].authenticationControls).length === 0));
  const report = await inspectBaselinePack(observed);
  const entra = report.options.filter((option) => option.providerId === "entra-external-id");
  assert.equal(entra.length, 8);
  assert.equal(entra.reduce((sum, option) => sum + option.facts.length, 0), 28);
  assert.ok(entra.filter((option) => option.basis !== "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => !option.facts.some((fact) => fact.path.startsWith("authenticationControls.") || fact.path === "facts.MFA")));
  const native = entra.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T23:12:01Z");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").availability, "UNKNOWN");
  const machine = entra.find((option) => option.basis === "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(machine.sourcePlan, "Basic MAU + M2M Premium add-on");
  assert.equal(machine.facts.find((fact) => fact.path === "compatibility.clients.MACHINE_TO_MACHINE").support, "SUPPORTED");
  const borrowed = structuredClone(originals.find((entry) => entry.catalogVersion === "entra-external-id-basic-public-oidc-clients-draft-2026.10.08"));
  borrowed.options[0].authenticationControls = structuredClone(draft.options[0].authenticationControls);
  assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
  const keycloak = report.options.find((option) => option.providerId === "keycloak" && option.basis === "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(keycloak.facts.find((fact) => fact.path === prefix + "STEP_UP_AUTHENTICATION").enforcement, "SUPPORTED");
  assert.equal(controls.STEP_UP_AUTHENTICATION.enforcement, "UNKNOWN");
  assert.equal(JSON.stringify(originals), before);
});
