import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "auth0-b2b-free-public-oidc-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T06:56:20Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("Auth0 public clients are typed first-party proposals, not verified Free entitlement or a release pin", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "B2B Free");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(option.deployment, "MANAGED");
  assert.equal(report.factCount, 3);
  assert.deepEqual(option.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.deepEqual(option.facts.map((fact) => fact.support ?? fact.availability), ["SUPPORTED", "SUPPORTED", "OPTIONAL"]);
  for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.equal(new URL(fact.evidence.sourceUrl).hostname, "auth0.com");
  }
  const original = draft.options[0];
  assert.match(original.facts.OIDC.conditions.join(" "), /token_endpoint_auth_method none.*application-type label/);
  assert.match(original.facts.OIDC.conditions.join(" "), /mutable and dated, not release-pinned/);
  assert.match(original.facts.OIDC.conditions.join(" "), /excludes confidential BFF.*third-party registrations/);
  assert.match(original.compatibility.clients.BROWSER.conditions.join(" "), /Enable at least one intended login connection/);
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("native callback proposals preserve impersonation caveats without creating authentication-control evidence", () => {
  const conditions = draft.options[0].compatibility.clients.NATIVE_MOBILE.conditions.join(" ");
  assert.match(conditions, /external user-agent.*RFC 8252/);
  assert.match(conditions, /claimed HTTPS Universal Links\/App Links/);
  assert.match(conditions, /PKCE alone does not prevent a malicious app/);
  assert.match(conditions, /non-verifiable callback.*retain end-user confirmation/);
  assert.match(conditions, /no mobile SDK.*verified/);
  assert.deepEqual(draft.options[0].authenticationControls, {});
  assert.ok(inspect().options[0].facts.every((fact) => fact.evidenceStatus === "UNREVIEWED"));
});

test("Auth0 inventory keeps one optional capability separate from two supported client contexts", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 2, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 2, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  const entry = inventory.options[0];
  assert.equal(entry.recordedPathCount, 3);
  assert.equal(entry.omittedPathCount, 65);
  assert.deepEqual(entry.recordedUnknownPaths, []);
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.clients.MACHINE_TO_MACHINE"));
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.applications.B2B_SAAS"));
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("Auth0 client inspection rejects scope, source, trust and inherited-fact substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "auth0-public-clients-latest"; },
    (value) => { value.options[0].plan = "B2C Professional"; },
    (value) => { value.options[0].product = "Auth0 Private Cloud"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "EU; all storage verified"; },
    (value) => { value.options[0].configuration = "Third-party confidential application; secret basic"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.support = "UNKNOWN"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.support = "UNSUPPORTED"; },
    (value) => { delete value.options[0].compatibility.clients.NATIVE_MOBILE; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.sourceUrl = value.options[0].facts.OIDC.evidence.sourceUrl; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.evidence.sourceUrl = "https://auth0.com/docs/authenticate/protocols/scim/configure-inbound-scim"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.clients.BROWSER; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OIDC.evidence.observedAt = "2026-02-30T06:56:20Z"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OIDC); },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: value.options[0].facts.OIDC.evidence }; },
    (value) => { value.options[0].authenticationControls.NATIVE_MOBILE = { EMPLOYEES: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].facts.OIDC.evidence } } }; },
    (value) => { value.sourceRelease = "verified-cloud-release"; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("Auth0 observations keep the inclusive 90-day boundary without rewriting evidence", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 3);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === observed.toISOString().replace(".000Z", "Z")));
    assert.equal(report.evaluationReady, false);
    assert.equal(report.approvalGranted, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("Auth0 public clients coexist with unchanged research/native/workforce claims without borrowing them", async () => {
  const originalDrafts = drafts.filter((entry) => entry.options[0].providerId === "auth0" && entry !== draft);
  const before = JSON.stringify(originalDrafts);
  const report = await inspectBaselinePack(observed);
  const auth0 = report.options.filter((option) => option.providerId === "auth0");
  assert.equal(auth0.length, 9);
  const organizations = auth0.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(organizations.facts.every((fact) => fact.path.startsWith("compatibility.") && !fact.path.startsWith("compatibility.clients.")));
  for (const option of auth0.filter((option) => !["CLIENT_SCOPED_DOCUMENTATION_DRAFT", "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT", "MACHINE_SCOPED_DOCUMENTATION_DRAFT", "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT", "RESIDENCY_SCOPED_DOCUMENTATION_DRAFT"].includes(option.basis))) {
    assert.ok(option.facts.every((fact) => fact.path.startsWith("facts.")));
    assert.deepEqual(report.schemaPathInventory.options.find((entry) => entry.optionId === option.optionId).families[1].recordedPaths, []);
  }
  assert.deepEqual(inspect().options[0].omittedCapabilities, ["ENTERPRISE_SSO", "GROUP_SYNC", "JIT", "MFA", "OAUTH2_APIS", "SAML", "SCIM", "SOCIAL_LOGIN"]);
  assert.equal(auth0.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT").facts.find((fact) => fact.path === "facts.SCIM").availability, "OPTIONAL");
  assert.equal(JSON.stringify(originalDrafts), before);
});

test("Auth0 context inspection is deterministic across object and condition ordering", () => {
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.compatibility.clients = Object.fromEntries(Object.entries(option.compatibility.clients).reverse());
  option.compatibility = Object.fromEntries(Object.entries(option.compatibility).reverse());
  for (const fact of [option.facts.OIDC, ...Object.values(option.compatibility.clients)]) fact.conditions.reverse();
  const before = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), before);
});
