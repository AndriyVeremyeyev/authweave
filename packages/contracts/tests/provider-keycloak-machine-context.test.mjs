import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "keycloak-26.8.0-machine-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T17:11:31Z");
const commit = "4246609cf2024c85016d3fb1254c3d2533367c31";
const root = `https://github.com/keycloak/keycloak/blob/${commit}/docs/documentation/server_admin/topics/clients/oidc/`;
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const paths = ["compatibility.clients.MACHINE_TO_MACHINE", "facts.OAUTH2_APIS"];

test("Keycloak machine context pins OAuth API availability and client compatibility to separate release sources", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourceRelease, "26.8.0");
  assert.equal(option.sourceCommit, commit);
  assert.equal(Object.hasOwn(option, "sourcePlan"), false);
  assert.equal(report.factCount, 2);
  assert.deepEqual(option.facts.map((fact) => fact.path), paths);
  assert.equal(option.facts[0].support, "SUPPORTED");
  assert.equal(Object.hasOwn(option.facts[0], "availability"), false);
  assert.equal(option.facts[1].availability, "OPTIONAL");
  assert.equal(Object.hasOwn(option.facts[1], "support"), false);
  assert.deepEqual(option.facts.map((fact) => fact.evidence.sourceUrl),
    [root + "proc-using-a-service-account.adoc", root + "con-audience.adoc"]);
  assert.ok(option.facts.every((fact) => fact.evidenceStatus === "UNREVIEWED" && fact.freshness === "CURRENT"));
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("machine clients keep Basic credentials, service-account role intersection and access-token defaults explicit", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /separate confidential.*Client authentication On.*Service account roles enabled/);
  assert.match(conditions, /grant_type=client_credentials/);
  assert.match(conditions, /no browser redirect, user password, user consent or user-delegation/);
  assert.match(conditions, /restrict Allowed authentication method to Authorization: Basic/);
  assert.match(conditions, /Basic encoding is not encryption.*TLS.*server-side/);
  assert.match(conditions, /intersection of assigned service-account roles and explicit role scope mappings/);
  assert.match(conditions, /Do not enable deprecated Full Scope Allowed/);
  assert.match(conditions, /without a refresh token or Keycloak user session/);
  assert.match(conditions, /example's lifetime is not a guaranteed deployment setting/);
  assert.match(conditions, /Signed JWT.*mTLS.*separate configurations/);
  assert.match(conditions, /No client, credentials, role assignments, secret rotation/);
});

test("OAuth API support does not silently grant user delegation, audiences, resource roles or instant revocation", () => {
  const conditions = draft.options[0].facts.OAUTH2_APIS.conditions.join(" ");
  assert.match(conditions, /client itself, not an end user.*not OIDC login/);
  assert.match(conditions, /requesting client ID is not automatically the API audience/);
  assert.match(conditions, /scope string alone does not grant a role/);
  assert.match(conditions, /validate the selected realm issuer, trusted signature\/key and algorithm, expiry/);
  assert.match(conditions, /Reject a missing or foreign audience, missing role or unknown resource context/);
  assert.match(conditions, /existing access token can remain valid until expiry/);
  assert.match(conditions, /No live token issuance, API validation, immediate revocation/);
  assert.match(conditions, /Hosting, operations and cost remain unverified/);
});

test("machine inventory records two typed paths without borrowing human controls or login capabilities", () => {
  const option = draft.options[0];
  assert.deepEqual(Object.keys(option.facts), ["OAUTH2_APIS"]);
  assert.deepEqual(Object.keys(option.compatibility.clients), ["MACHINE_TO_MACHINE"]);
  for (const group of ["applications", "populations", "tenancy", "membership"]) assert.deepEqual(option.compatibility[group], {});
  assert.deepEqual(option.residency, {});
  assert.deepEqual(option.authenticationControls, {});
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 1, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 1, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 1, COMPATIBILITY: 1, RESIDENCY: 0, AUTHENTICATION_CONTROL: 0 });
  assert.equal(inventory.options[0].recordedPathCount, 2);
  assert.equal(inventory.options[0].omittedPathCount, 66);
  assert.deepEqual(inventory.options[0].recordedUnknownPaths, []);
  assert.ok(inventory.options[0].families[0].omittedPaths.includes("facts.OIDC"));
  assert.ok(inventory.options[0].families[1].omittedPaths.includes("compatibility.clients.BROWSER"));
  assert.ok(inventory.options[0].families[1].omittedPaths.includes("compatibility.clients.NATIVE_MOBILE"));
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("machine inspection rejects source, scope, grant-family and authority substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "keycloak-latest-machine-clients"; },
    (value) => { value.options[0].id = "keycloak-26.8.0-public-oidc-clients"; },
    (value) => { value.options[0].providerId = "zitadel"; },
    (value) => { value.options[0].product = "Red Hat build of Keycloak"; },
    (value) => { value.options[0].plan = "Free managed M2M"; },
    (value) => { value.options[0].deployment = "MANAGED"; },
    (value) => { value.options[0].region = "CH; all storage verified"; },
    (value) => { value.options[0].configuration = "Public client; delegated password grant; private_key_jwt; refresh tokens"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.availability = "MANDATORY"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.support = "UNKNOWN"; },
    (value) => { delete value.options[0].compatibility.clients.MACHINE_TO_MACHINE; },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].facts.OIDC = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = root.replace(commit, "main") + "con-audience.adoc"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = root.replace(commit, "26.8.0") + "con-audience.adoc"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = root + "proc-using-a-service-account.adoc"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.evidence.sourceUrl = root + "con-audience.adoc"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.conditions = []; },
    (value) => { const fact = value.options[0].facts.OAUTH2_APIS; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.observedAt = "2026-02-30T17:11:31Z"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.availability = "OPTIONAL"; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: value.options[0].facts.OAUTH2_APIS.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EMPLOYEES: { PHISHING_RESISTANCE: { availability: "SUPPORTED", enforcement: "SUPPORTED", conditions: [], evidence: value.options[0].facts.OAUTH2_APIS.evidence } } }; },
    (value) => { value.options[0].authenticationControls.MACHINE_TO_MACHINE = {}; },
    (value) => { value.sourceCommit = commit; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("machine observations keep the inclusive freshness boundary without refreshing dates or trust", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 2);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T17:11:31Z"));
    assert.deepEqual(report.schemaPathInventory.proposedAvailabilityCounts, inspect().schemaPathInventory.proposedAvailabilityCounts);
    assert.deepEqual(report.schemaPathInventory.proposedCompatibilityCounts, inspect().schemaPathInventory.proposedCompatibilityCounts);
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("machine context coexists with other Keycloak scopes without changing their API omissions", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "keycloak" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 7);
  const report = await inspectBaselinePack(observed);
  const keycloak = report.options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 9);
  assert.equal(keycloak.reduce((count, option) => count + option.facts.length, 0), 37);
  const earlier = keycloak.filter((option) => option.basis !== "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(earlier.every((option) => !option.facts.some((fact) => paths.includes(fact.path))));
  const native = earlier.find((option) => option.basis === "RELEASE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T21:20:39Z");
  assert.deepEqual(earlier.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT").facts.map((fact) => fact.path),
    ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.ok(earlier.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(JSON.stringify(originals), before);
});

test("machine report ordering is deterministic and inspection does not mutate either fact family", () => {
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.compatibility = Object.fromEntries(Object.entries(option.compatibility).reverse());
  for (const fact of [option.facts.OAUTH2_APIS, option.compatibility.clients.MACHINE_TO_MACHINE]) fact.conditions.reverse();
  const before = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), before);
});
