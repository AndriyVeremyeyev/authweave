import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "zitadel-cloud-free-machine-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T17:31:13Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const paths = ["compatibility.clients.MACHINE_TO_MACHINE", "facts.OAUTH2_APIS"];
const apiSource = "https://zitadel.com/docs/guides/integrate/token-introspection";
const machineSource = "https://zitadel.com/docs/guides/integrate/service-accounts/private-key-jwt";

test("Cloud machine context keeps API capability and client compatibility as distinct dated proposals", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Free");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
  assert.equal(option.deployment, "MANAGED");
  assert.equal(report.factCount, 2);
  assert.deepEqual(option.facts.map((fact) => fact.path), paths);
  assert.equal(option.facts[0].support, "SUPPORTED");
  assert.equal(Object.hasOwn(option.facts[0], "availability"), false);
  assert.equal(option.facts[1].availability, "OPTIONAL");
  assert.equal(Object.hasOwn(option.facts[1], "support"), false);
  assert.deepEqual(option.facts.map((fact) => fact.evidence.sourceUrl), [machineSource, apiSource]);
  assert.ok(option.facts.every((fact) => fact.evidenceStatus === "UNREVIEWED" && fact.freshness === "CURRENT"));
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("service-account assertion exchange is not client authentication or a signed API bearer token", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /dedicated service account and registered RSA public key/);
  assert.match(conditions, /Sign RS256 with kid.*iss\/sub.*service account.*aud.*instance authority/);
  assert.match(conditions, /assertion expiry is separate from access-token expiry/i);
  assert.match(conditions, /grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer with assertion and required openid/);
  assert.match(conditions, /not client_credentials plus private_key_jwt client authentication/);
  assert.match(conditions, /separate application credentials\/client_assertion/);
  assert.match(conditions, /overview says client_assertion.*concrete token request uses assertion/);
  assert.match(conditions, /documentation discrepancies.*returned access_token as Bearer/);
  assert.match(conditions, /Free offer.*not verified account entitlement.*zero-cost guarantee/);
  assert.match(conditions, /No Cloud region, account, keys, API registration, role assignments/);
});

test("API audience and resource permissions do not inherit assertion audience, admin grants or local JWT validation", () => {
  const conditions = draft.options[0].facts.OAUTH2_APIS.conditions.join(" ");
  assert.match(conditions, /not the API bearer token, OIDC user login/);
  assert.match(conditions, /urn:zitadel:iam:org:project:id:\{projectId\}:aud/);
  assert.match(conditions, /assertion audience is the ZITADEL authority, not this API audience/);
  assert.match(conditions, /requested scope does not grant a role/);
  assert.match(conditions, /Reserved project id zitadel.*management, not.*application/);
  assert.match(conditions, /separate API application registration.*HTTPS introspection/);
  assert.match(conditions, /Check active, trusted issuer, expiry, intended audience/);
  assert.match(conditions, /fail closed on inactive tokens, errors, missing or foreign context/);
  assert.match(conditions, /active alone are not application authorization/);
  assert.match(conditions, /opaque or JWT.*do not assume.*locally verifiable/);
  assert.match(conditions, /caching, key rotation, role removal and service offboarding/);
  assert.match(conditions, /No immediate revocation.*guaranteed/);
  assert.match(conditions, /mutable and dated, not release-pinned/);
});

test("Cloud machine inventory has two typed paths and no inherited human or organization control coverage", () => {
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
  for (const client of ["BROWSER", "NATIVE_MOBILE"]) {
    assert.ok(inventory.options[0].families[1].omittedPaths.includes(`compatibility.clients.${client}`));
  }
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("Cloud machine inspection rejects scope, source, family and authority substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "zitadel-cloud-free-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].id = "zitadel-cloud-free-native"; },
    (value) => { value.options[0].providerId = "keycloak"; },
    (value) => { value.options[0].product = "ZITADEL self-hosted"; },
    (value) => { value.options[0].plan = "Pro; verified entitlement"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "CH; all storage verified"; },
    (value) => { value.options[0].configuration = "client_credentials; Basic; PAT; implicit roles"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.availability = "MANDATORY"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.support = "UNKNOWN"; },
    (value) => { delete value.options[0].compatibility.clients.MACHINE_TO_MACHINE; },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].compatibility.applications.B2B_SAAS = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].facts.OIDC = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = machineSource; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.evidence.sourceUrl = apiSource; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.evidence.sourceUrl = machineSource.replace("private-key-jwt", "client-credentials"); },
    (value) => { value.options[0].facts.OAUTH2_APIS.conditions = []; },
    (value) => { const fact = value.options[0].facts.OAUTH2_APIS; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.observedAt = "2026-02-30T17:31:13Z"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.availability = "OPTIONAL"; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["CH"], conditions: [], evidence: value.options[0].facts.OAUTH2_APIS.evidence }; },
    (value) => { value.options[0].authenticationControls.MACHINE_TO_MACHINE = {}; },
    (value) => { value.sourceRelease = "3.0.0"; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("Cloud machine freshness retains original observations and the inclusive ninety-day boundary", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 2);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T17:31:13Z"));
    assert.deepEqual(report.schemaPathInventory.proposedAvailabilityCounts, inspect().schemaPathInventory.proposedAvailabilityCounts);
    assert.deepEqual(report.schemaPathInventory.proposedCompatibilityCounts, inspect().schemaPathInventory.proposedCompatibilityCounts);
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("Cloud machine context coexists with six earlier scopes without filling their API omissions", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "zitadel" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 5);
  const report = await inspectBaselinePack(observed);
  const zitadel = report.options.filter((option) => option.providerId === "zitadel");
  assert.equal(zitadel.length, 7);
  assert.equal(zitadel.reduce((count, option) => count + option.facts.length, 0), 24);
  const earlier = zitadel.filter((option) => option.basis !== "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(earlier.every((option) => !option.facts.some((fact) => paths.includes(fact.path))));
  const native = earlier.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T21:44:10Z");
  assert.deepEqual(earlier.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT").facts.map((fact) => fact.path),
    ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.ok(earlier.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(JSON.stringify(originals), before);
});

test("Cloud machine inspection is deterministic and does not mutate the proposal", () => {
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.compatibility = Object.fromEntries(Object.entries(option.compatibility).reverse());
  for (const fact of [option.facts.OAUTH2_APIS, option.compatibility.clients.MACHINE_TO_MACHINE]) fact.conditions.reverse();
  const before = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), before);
});
