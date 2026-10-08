import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "keycloak-26.8.0-public-oidc-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T06:12:22Z");
const commit = "4246609cf2024c85016d3fb1254c3d2533367c31";
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("public OIDC scope records typed browser/native compatibility without claiming a deployed integration", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourceRelease, "26.8.0");
  assert.equal(option.sourceCommit, commit);
  assert.equal(report.factCount, 3);
  assert.deepEqual(option.facts.map((fact) => fact.path), ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.deepEqual(option.facts.map((fact) => fact.support ?? fact.availability), ["SUPPORTED", "SUPPORTED", "OPTIONAL"]);
  for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.ok(fact.evidence.sourceUrl.includes("/blob/" + commit + "/docs/"));
  }
  const original = draft.options[0];
  assert.match(original.facts.OIDC.conditions.join(" "), /blank PKCE method does not require PKCE/);
  assert.match(original.facts.OIDC.conditions.join(" "), /excludes confidential BFF/);
  assert.match(original.compatibility.clients.NATIVE_MOBILE.conditions.join(" "), /external user-agent.*RFC 8252/);
  assert.match(original.compatibility.clients.NATIVE_MOBILE.conditions.join(" "), /no mobile SDK.*verified/);
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("inventory v2 does not count two compatibility assertions as two optional capabilities", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 2, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 2, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  const entry = inventory.options[0];
  assert.equal(entry.recordedPathCount, 3);
  assert.equal(entry.omittedPathCount, 65);
  assert.deepEqual(entry.recordedUnknownPaths, []);
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.clients.MACHINE_TO_MACHINE"));
  assert.ok(entry.families[1].omittedPaths.includes("compatibility.applications.PUBLIC_SECTOR_PORTAL"));
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("client context rejects scope, source, family and trust substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "keycloak-latest-public-clients"; },
    (value) => { value.options[0].configuration = "Confidential BFF and service-account OIDC"; },
    (value) => { value.options[0].deployment = "MANAGED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.support = "UNKNOWN"; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.support = "UNSUPPORTED"; },
    (value) => { delete value.options[0].compatibility.clients.NATIVE_MOBILE; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS = structuredClone(value.options[0].compatibility.clients.BROWSER); },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.sourceUrl = value.options[0].facts.OIDC.evidence.sourceUrl; },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.evidence.sourceUrl = value.options[0].compatibility.clients.NATIVE_MOBILE.evidence.sourceUrl.replace(commit, "main"); },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.conditions = []; },
    (value) => { const fact = value.options[0].compatibility.clients.BROWSER; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].compatibility.clients.NATIVE_MOBILE.evidence.observedAt = "2026-02-30T06:12:22Z"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.BROWSER.availability = "OPTIONAL"; },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OIDC); },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["DE"], conditions: [], evidence: value.options[0].facts.OIDC.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EMPLOYEES: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].facts.OIDC.evidence } } }; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("client-context observations retain the inclusive 90-day boundary and never refresh evidence", () => {
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

test("public client context does not populate research, native SCIM or workforce broker options", async () => {
  const report = await inspectBaselinePack(observed);
  const keycloak = report.options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 5);
  for (const option of keycloak.filter((option) => option.basis !== "CLIENT_SCOPED_DOCUMENTATION_DRAFT")) {
    assert.ok(option.facts.every((fact) => fact.path.startsWith("facts.")));
    assert.deepEqual(report.schemaPathInventory.options.find((entry) => entry.optionId === option.optionId).families[1].recordedPaths, []);
  }
  assert.deepEqual(inspect().options[0].omittedCapabilities, ["ENTERPRISE_SSO", "GROUP_SYNC", "JIT", "MFA", "OAUTH2_APIS", "SAML", "SCIM", "SOCIAL_LOGIN"]);
});

test("context inventory stays deterministic across compatibility and condition ordering", () => {
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.compatibility.clients = Object.fromEntries(Object.entries(option.compatibility.clients).reverse());
  option.compatibility = Object.fromEntries(Object.entries(option.compatibility).reverse());
  for (const fact of [option.facts.OIDC, ...Object.values(option.compatibility.clients)]) fact.conditions.reverse();
  const before = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), before);
});
