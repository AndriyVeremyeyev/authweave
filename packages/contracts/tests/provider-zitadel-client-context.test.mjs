import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "zitadel-cloud-free-public-oidc-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T06:37:37Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("Cloud public clients are typed documentation proposals, not a release pin or deployed entitlement", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Free");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(option.deployment, "MANAGED");
  assert.equal(report.factCount, 3);
  assert.deepEqual(option.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.deepEqual(option.facts.map((fact) => fact.support ?? fact.availability), ["SUPPORTED", "SUPPORTED", "OPTIONAL"]);
  for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.equal(new URL(fact.evidence.sourceUrl).hostname, "zitadel.com");
  }
  const original = draft.options[0];
  assert.match(original.facts.OIDC.conditions.join(" "), /mutable and dated, not release-pinned/);
  assert.match(original.facts.OIDC.conditions.join(" "), /Free-account entitlement.*not been verified/);
  assert.match(original.compatibility.clients.BROWSER.conditions.join(" "), /Development Mode restrictions/);
  assert.match(original.compatibility.clients.NATIVE_MOBILE.conditions.join(" "), /external user-agent.*RFC 8252/);
  assert.match(original.compatibility.clients.NATIVE_MOBILE.conditions.join(" "), /no mobile SDK.*verified/);
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("Cloud inventory keeps one optional capability separate from two supported client contexts", () => {
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

test("Cloud client inspection rejects scope, source, trust and inherited-fact substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "zitadel-self-hosted-public-clients"; },
    (value) => { value.options[0].plan = "Pro"; },
    (value) => { value.options[0].product = "ZITADEL self-hosted"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "EU; all storage verified"; },
    (value) => { value.options[0].configuration = "Confidential BFF and API grants"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.support = "UNKNOWN"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.support = "UNSUPPORTED"; },
    (value) => { delete value.options[0].compatibility.clients.NATIVE_MOBILE; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.sourceUrl = value.options[0].facts.OIDC.evidence.sourceUrl; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.evidence.sourceUrl = "https://zitadel.com/docs/apis/scim2"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.clients.BROWSER; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OIDC.evidence.observedAt = "2026-02-30T06:37:37Z"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OIDC); },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: value.options[0].facts.OIDC.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EMPLOYEES: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].facts.OIDC.evidence } } }; },
    (value) => { value.sourceRelease = "verified-cloud-release"; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("Cloud observations keep the inclusive 90-day boundary without rewriting evidence", () => {
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

test("Cloud client scope coexists with research/native/workforce facts without borrowing them", async () => {
  const report = await inspectBaselinePack(observed);
  const zitadel = report.options.filter((option) => option.providerId === "zitadel");
  assert.equal(zitadel.length, 8);
  for (const option of zitadel.filter((option) => !["CLIENT_SCOPED_DOCUMENTATION_DRAFT", "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT", "MACHINE_SCOPED_DOCUMENTATION_DRAFT", "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT"].includes(option.basis))) {
    assert.ok(option.facts.every((fact) => fact.path.startsWith("facts.")));
    assert.deepEqual(report.schemaPathInventory.options.find((entry) => entry.optionId === option.optionId).families[1].recordedPaths, []);
  }
  assert.deepEqual(inspect().options[0].omittedCapabilities, ["ENTERPRISE_SSO", "GROUP_SYNC", "JIT", "MFA", "OAUTH2_APIS", "SAML", "SCIM", "SOCIAL_LOGIN"]);
  assert.equal(zitadel.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT").facts.find((fact) => fact.path === "facts.SCIM").availability, "UNKNOWN");
  const organizations = zitadel.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(organizations.facts.length, 4);
  assert.ok(organizations.facts.every((fact) => fact.path.startsWith("compatibility.") && !fact.path.startsWith("compatibility.clients.")));
});

test("Cloud context inspection is deterministic across object and condition ordering", () => {
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.compatibility.clients = Object.fromEntries(Object.entries(option.compatibility.clients).reverse());
  option.compatibility = Object.fromEntries(Object.entries(option.compatibility).reverse());
  for (const fact of [option.facts.OIDC, ...Object.values(option.compatibility.clients)]) fact.conditions.reverse();
  const before = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), before);
});
