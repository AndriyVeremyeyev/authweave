import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "workos-connect-staging-machine-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T18:39:35Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const paths = ["compatibility.clients.MACHINE_TO_MACHINE", "facts.OAUTH2_APIS"];
const apiSource = "https://workos.com/docs/authkit/connect/token-claims";
const machineSource = "https://workos.com/docs/authkit/connect/m2m";

test("WorkOS machine scope keeps Connect API capability and M2M compatibility as distinct unreviewed staging proposals", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Staging");
  assert.equal(option.product, "WorkOS AuthKit Connect");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(Object.hasOwn(option, "sourceRelease"), false);
  assert.equal(Object.hasOwn(option, "sourceCommit"), false);
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

test("WorkOS M2M requires a third-party organization-bound application, secret Post and least-privilege scopes", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /third-party Connect application with application_type m2m.*explicit customer\/partner organization/);
  assert.match(conditions, /only third-party applications.*not a first-party background-service/);
  assert.match(conditions, /grant_type=client_credentials.*HTTPS form body.*\/oauth2\/token.*client_secret_post only/);
  assert.match(conditions, /outside browser\/mobile bundles, URLs and logs/);
  assert.match(conditions, /management API key is not this application secret/);
  assert.match(conditions, /Basic, private_key_jwt and mTLS are outside scope/);
  assert.match(conditions, /least-privilege application scopes.*explicit subset.*optional scope parameter/);
  assert.match(conditions, /exact narrowing\/default behavior is untested/);
  assert.match(conditions, /Configure the organization and scopes explicitly.*common create schema marks them optional/);
  assert.match(conditions, /does not use ID Tokens, refresh tokens, end-user sessions or browser redirects/);
});

test("WorkOS machine sources retain metadata discrepancies and do not establish free production entitlement", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /OpenID discovery example includes client_credentials.*OAuth authorization-server example omits it/);
  assert.match(conditions, /do not treat examples as observed runtime metadata/);
  assert.match(conditions, /mutable and dated, not release-pinned/);
  assert.match(conditions, /Staging is testing-only, not customer-facing production/);
  assert.match(conditions, /separate keys, organizations and users/);
  assert.match(conditions, /Free staging does not verify production M2M entitlement, quotas, billing or future zero-cost/);
  assert.match(conditions, /No account, application, organization, credentials, subscription or live calls/);
});

test("WorkOS API conditions bind environment audience and machine organization without importing user claims", () => {
  const conditions = draft.options[0].facts.OAUTH2_APIS.conditions.join(" ");
  assert.match(conditions, /selected HTTPS AuthKit issuer.*trusted environment \/oauth2\/jwks.*allowed algorithm.*expiry.*environment client ID audience/);
  assert.match(conditions, /not the requesting M2M client ID or a per-application resource parameter/);
  assert.match(conditions, /machine sub and client_id.*expected org_id and granted scopes.*application-owned resource permissions/);
  assert.match(conditions, /Reject missing, unknown or foreign organization\/resource context/);
  assert.match(conditions, /signed org_id, decoded JWT or successful token exchange does not establish tenant isolation/);
  assert.match(conditions, /no sid and no JWT templates\/custom claims/);
  assert.match(conditions, /Granted scopes are not human permissions/);
  assert.match(conditions, /introspection credential policy and fail-closed transport handling/);
  assert.match(conditions, /example uses \/oauth2\/introspection.*typed endpoint heading says \/oauth2\/token/);
  assert.match(conditions, /org_id optional.*M2M guide requires an organization/);
  assert.match(conditions, /Do not promise immediate rejection of already-issued JWTs/);
});

test("WorkOS machine inventory records two paths without human compatibility, SCIM or control inheritance", () => {
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
  assert.ok(inventory.options[0].families[0].omittedPaths.includes("facts.SCIM"));
  for (const client of ["BROWSER", "NATIVE_MOBILE"]) {
    assert.ok(inventory.options[0].families[1].omittedPaths.includes(`compatibility.clients.${client}`));
  }
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("WorkOS machine inspection rejects product, audience, credential, source, context and authority substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "workos-connect-staging-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].id = "workos-authkit-staging-organization-context"; },
    (value) => { value.options[0].providerId = "auth0"; },
    (value) => { value.options[0].product = "WorkOS Directory Sync"; },
    (value) => { value.options[0].plan = "Production; unlimited free M2M"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "EU; all storage verified"; },
    (value) => { value.options[0].configuration = "First-party user login; API key; configurable resource audience"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.availability = "MANDATORY"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.support = "UNKNOWN"; },
    (value) => { delete value.options[0].compatibility.clients.MACHINE_TO_MACHINE; },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].facts.OIDC = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = machineSource; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.evidence.sourceUrl = apiSource; },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = "https://workos.com/docs/reference/authkit/session-tokens"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.conditions = []; },
    (value) => { const fact = value.options[0].facts.OAUTH2_APIS; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.observedAt = "2026-02-30T18:39:35Z"; },
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

test("WorkOS machine observations keep the inclusive freshness boundary without changing dates or trust", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 2);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T18:39:35Z"));
    assert.deepEqual(report.schemaPathInventory.proposedAvailabilityCounts, inspect().schemaPathInventory.proposedAvailabilityCounts);
    assert.deepEqual(report.schemaPathInventory.proposedCompatibilityCounts, inspect().schemaPathInventory.proposedCompatibilityCounts);
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("WorkOS machine context coexists with seven other scopes and deterministic inspection does not mutate them", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "workos" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 6);
  const report = await inspectBaselinePack(observed);
  const workos = report.options.filter((option) => option.providerId === "workos");
  assert.equal(workos.length, 8);
  assert.equal(workos.reduce((count, option) => count + option.facts.length, 0), 22);
  const earlier = workos.filter((option) => option.basis !== "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(earlier.every((option) => !option.facts.some((fact) => paths.includes(fact.path))));
  const native = earlier.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T22:46:13Z");
  assert.equal(Object.hasOwn(originals.find((entry) => entry.catalogVersion === "workos-directory-sync-staging-draft-2026.10.02").options[0].facts, "OIDC"), false);
  const publicClients = earlier.find((option) => option.basis === "CLIENT_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(publicClients.facts.find((fact) => fact.path === "compatibility.clients.BROWSER").support, "UNKNOWN");
  assert.equal(earlier.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT").product, "WorkOS AuthKit");
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
