import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "auth0-b2b-free-machine-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T17:49:06Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const paths = ["compatibility.clients.MACHINE_TO_MACHINE", "facts.OAUTH2_APIS"];
const apiSource = "https://auth0.com/docs/secure/tokens/access-tokens/validate-access-tokens";
const machineSource = "https://auth0.com/docs/get-started/authentication-and-authorization-flow/client-credentials-flow/call-your-api-using-the-client-credentials-flow";

test("Auth0 machine scope keeps API capability and M2M compatibility as distinct unreviewed Free proposals", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "B2B Free");
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

test("Auth0 machine grant selects first-party secret Post and bounded client scopes without user delegation", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /dedicated first-party confidential M2M application/);
  assert.match(conditions, /grant_type=client_credentials.*exact audience and explicit requested scopes/);
  assert.match(conditions, /application itself, not an end user/);
  assert.match(conditions, /token_endpoint_auth_method client_secret_post.*HTTPS request body/);
  assert.match(conditions, /outside browser\/mobile bundles, URLs and logs/);
  assert.match(conditions, /Basic, private_key_jwt, mTLS.*separate configurations/);
  assert.match(conditions, /Per-app authorization for Client Access.*explicit client grant.*scope ceiling/);
  assert.match(conditions, /not Always grant all permissions/);
  assert.match(conditions, /User-Delegated Access to No apps allowed/);
  assert.match(conditions, /returned access_token as Bearer.*validated expiry/);
  assert.match(conditions, /Refresh tokens are not part of this selected flow/);
  assert.match(conditions, /No tenant, application, API, credentials, grants, subscription or live requests/);
});

test("Auth0 machine scope preserves paid-organization discrepancies and bounded custom-audience token quota", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /Organization Support None.*omit organization/);
  assert.match(conditions, /paid B2B Professional\/Enterprise.*Select Enterprise Plans.*preserve this discrepancy/);
  assert.match(conditions, /do not infer Free organization-scoped M2M or import human memberships/);
  assert.match(conditions, /Free comparison.*1,000 M2M authentications.*not verified account entitlement/);
  assert.match(conditions, /Custom-audience tokens consume quota.*internal Auth0 audiences are a different case/);
  assert.match(conditions, /Management API grants are not custom API permissions and are excluded/);
  assert.match(conditions, /obtain a new token with client_credentials.*not for every API call/);
});

test("Auth0 custom API conditions keep RS256 validation distinct from secret authentication and resource authorization", () => {
  const conditions = draft.options[0].facts.OAUTH2_APIS.conditions.join(" ");
  assert.match(conditions, /custom API using the Auth0 JWT profile and RS256/);
  assert.match(conditions, /API Identifier is the audience, not the requesting client ID or an OIDC ID Token/);
  assert.match(conditions, /identifier trailing slashes are significant/);
  assert.match(conditions, /trusted tenant issuer, RS256 signature\/key, expiry.*API audience.*granted scopes/);
  assert.match(conditions, /token decoding or successful issuance alone is not authorization/);
  assert.match(conditions, /trusted JWKS, not a token-supplied URL.*pin the allowed algorithm/);
  assert.match(conditions, /client secret is not the RS256 signing key/);
  assert.match(conditions, /application-owned service-principal\/resource permissions/);
  assert.match(conditions, /Fail closed on missing or foreign resource context.*no organization claims/);
  assert.match(conditions, /Do not promise immediate rejection of already-issued JWTs/);
  assert.match(conditions, /mutable and dated, not release-pinned/);
});

test("Auth0 machine inventory records only two typed paths without inherited human or organization controls", () => {
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

test("Auth0 machine inspection rejects source, scope, grant-family and authority substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "auth0-b2b-free-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].id = "auth0-b2b-free-organization-context"; },
    (value) => { value.options[0].providerId = "zitadel"; },
    (value) => { value.options[0].product = "Auth0 Private Cloud"; },
    (value) => { value.options[0].plan = "B2B Professional; unlimited free M2M"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "EU; all storage verified"; },
    (value) => { value.options[0].configuration = "Public client; private_key_jwt; paid organization grants"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.availability = "MANDATORY"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.support = "UNKNOWN"; },
    (value) => { delete value.options[0].compatibility.clients.MACHINE_TO_MACHINE; },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].facts.OIDC = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = machineSource; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.evidence.sourceUrl = apiSource; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.evidence.sourceUrl = "https://auth0.com/docs/manage-users/organizations/organizations-for-m2m-applications"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.conditions = []; },
    (value) => { const fact = value.options[0].facts.OAUTH2_APIS; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.observedAt = "2026-02-30T17:49:06Z"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.availability = "OPTIONAL"; },
    (value) => { value.options[0].residency.USER_PROFILES = { coverage: "COMPLETE", storageCountries: ["US"], conditions: [], evidence: value.options[0].facts.OAUTH2_APIS.evidence }; },
    (value) => { value.options[0].authenticationControls.MACHINE_TO_MACHINE = {}; },
    (value) => { value.sourceRelease = "verified-cloud-release"; },
    (value) => { value.approvalGranted = true; },
  ];
  for (const change of mutations) {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed));
  }
});

test("Auth0 machine observations retain the inclusive freshness boundary without changing dates or trust", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 2);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T17:49:06Z"));
    assert.deepEqual(report.schemaPathInventory.proposedAvailabilityCounts, inspect().schemaPathInventory.proposedAvailabilityCounts);
    assert.deepEqual(report.schemaPathInventory.proposedCompatibilityCounts, inspect().schemaPathInventory.proposedCompatibilityCounts);
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("Auth0 machine context coexists with six earlier scopes and deterministic inspection does not mutate facts", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "auth0" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 5);
  const report = await inspectBaselinePack(observed);
  const auth0 = report.options.filter((option) => option.providerId === "auth0");
  assert.equal(auth0.length, 7);
  assert.equal(auth0.reduce((count, option) => count + option.facts.length, 0), 22);
  const earlier = auth0.filter((option) => option.basis !== "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(earlier.every((option) => !option.facts.some((fact) => paths.includes(fact.path))));
  const native = earlier.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T22:07:48Z");
  assert.deepEqual(earlier.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT").facts.map((fact) => fact.path),
    ["compatibility.clients.BROWSER", "compatibility.clients.NATIVE_MOBILE", "facts.OIDC"]);
  assert.ok(earlier.filter((option) => option.basis === "UPSTREAM_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => option.facts.find((fact) => fact.path === "facts.SCIM").availability === "UNKNOWN"));
  assert.equal(JSON.stringify(originals), before);
  const reordered = structuredClone(draft);
  reordered.options[0].compatibility = Object.fromEntries(Object.entries(reordered.options[0].compatibility).reverse());
  for (const fact of [reordered.options[0].facts.OAUTH2_APIS, reordered.options[0].compatibility.clients.MACHINE_TO_MACHINE]) {
    fact.conditions.reverse();
  }
  const reorderedBefore = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), reorderedBefore);
});
