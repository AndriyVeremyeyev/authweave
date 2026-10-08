import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "entra-external-id-m2m-addon-machine-clients-draft-2026.10.08");
const observed = new Date("2026-10-08T19:00:07Z");
const inspect = () => inspectScopedBaselineDraft(draft, observed);
const paths = ["compatibility.clients.MACHINE_TO_MACHINE", "facts.OAUTH2_APIS"];
const apiSource = "https://learn.microsoft.com/en-us/entra/identity-platform/claims-validation";
const machineSource = "https://learn.microsoft.com/en-us/entra/external-id/customers/overview-customers-ciam";

test("Entra machine scope separates paid-add-on API capability and compatibility from Basic-only entitlement", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourcePlan, "Basic MAU + M2M Premium add-on");
  assert.equal(option.plan, "Basic MAU + M2M Premium add-on; transaction billing, entitlement unverified");
  assert.equal(option.product, "Microsoft Entra External ID - external tenant");
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

test("Entra machine proposal requires confidential secret Post, single-resource default and assigned application roles", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /standard-mode External ID external tenant.*M2M Premium add-on/);
  assert.match(conditions, /not Basic-only free MAU.*workforce tenant or Azure AD B2C/);
  assert.match(conditions, /grant_type=client_credentials.*URL-encoded client_secret.*scope=<API application ID URI>\/.default.*HTTPS form body/);
  assert.match(conditions, /selected external tenant's trusted token endpoint.*client_secret_post only/);
  assert.match(conditions, /Never expose secrets in source, browser\/mobile bundles, URLs or logs/);
  assert.match(conditions, /Certificate assertions, federated credentials, Basic and mTLS are outside scope/);
  assert.match(conditions, /application roles.*assign\/admin-consent.*Delegated user permissions do not apply/);
  assert.match(conditions, /not a dynamic per-request subset of individual roles/);
  assert.match(conditions, /no refresh token, human login, ID Token, redirect, BFF session or customer membership/);
});

test("Entra machine sources retain transaction costs and prohibit paid setup without a separate decision", () => {
  const conditions = draft.options[0].compatibility.clients.MACHINE_TO_MACHINE.conditions.join(" ");
  assert.match(conditions, /charges are transaction-based and separate from Basic MAU/);
  assert.match(conditions, /free interactive-user allowance does not include free machine authentication/);
  assert.match(conditions, /rates, quotas, subscription linkage and tenant entitlement remain unverified/);
  assert.match(conditions, /Do not enable billing or make paid test exchanges without a separate owner decision/);
  assert.match(conditions, /mutable and observation-dated, not release-pinned/);
  assert.match(conditions, /Do not copy generic workforce\/common endpoints/);
  assert.match(conditions, /No account, tenant, subscription, application, secret, app-role grant, payment or live call/);
});

test("Entra API checks distinguish resource version, app-only identity and customer-resource authorization", () => {
  const conditions = draft.options[0].facts.OAUTH2_APIS.conditions.join(" ");
  assert.match(conditions, /requestedAccessTokenVersion=2.*endpoint version alone does not determine/);
  assert.match(conditions, /trusted signing keys, allowed algorithms, exact issuer, API client ID audience and lifetime/);
  assert.match(conditions, /Reject Graph, ID, foreign-resource and v1 tokens/);
  assert.match(conditions, /external-directory tid, registered machine azp.*optional idtyp=app.*reject missing or user values/);
  assert.match(conditions, /not infer app-only identity solely from an absent scp or present roles/);
  assert.match(conditions, /assigned application roles.*independently authorize.*organization\/resource records/);
  assert.match(conditions, /tid is not a customer organization ID.*cross-organization access/);
  assert.match(conditions, /role-less app-only tokens.*must reject them.*assignment requirements/);
  assert.match(conditions, /Do not promise immediate rejection of issued access tokens/);
  assert.match(conditions, /synthetic evaluator remain unchanged/);
});

test("Entra machine inventory has only two paths without interactive, SCIM, residency or membership inheritance", () => {
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

test("Entra machine inspector rejects free entitlement, workforce, credential, context, source and authority substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "entra-external-id-basic-public-oidc-clients-draft-2026.10.08"; },
    (value) => { value.options[0].id = "entra-external-id-basic-standard-native"; },
    (value) => { value.options[0].providerId = "workos"; },
    (value) => { value.options[0].product = "Microsoft Entra workforce tenant"; },
    (value) => { value.options[0].product = "Azure AD B2C"; },
    (value) => { value.options[0].plan = "Basic MAU; unlimited free M2M"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "EU; verified storage"; },
    (value) => { value.options[0].configuration = "Delegated SPA; common endpoint; dynamic per-call scopes"; },
    (value) => { value.options[0].facts.OAUTH2_APIS.availability = "MANDATORY"; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.support = "UNKNOWN"; },
    (value) => { delete value.options[0].compatibility.clients.MACHINE_TO_MACHINE; },
    (value) => { value.options[0].compatibility.clients.BROWSER = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS = structuredClone(value.options[0].compatibility.clients.MACHINE_TO_MACHINE); },
    (value) => { value.options[0].facts.OIDC = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.SCIM = structuredClone(value.options[0].facts.OAUTH2_APIS); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.sourceUrl = machineSource; },
    (value) => { value.options[0].compatibility.clients.MACHINE_TO_MACHINE.evidence.sourceUrl = apiSource; },
    (value) => { value.options[0].facts.OAUTH2_APIS.conditions = []; },
    (value) => { const fact = value.options[0].facts.OAUTH2_APIS; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OAUTH2_APIS.evidence.observedAt = "2026-02-30T19:00:07Z"; },
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

test("Entra machine evidence keeps inclusive freshness and never rewrites observations or approval", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 2);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T19:00:07Z"));
    assert.deepEqual(report.schemaPathInventory.proposedAvailabilityCounts, inspect().schemaPathInventory.proposedAvailabilityCounts);
    assert.deepEqual(report.schemaPathInventory.proposedCompatibilityCounts, inspect().schemaPathInventory.proposedCompatibilityCounts);
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  assert.equal(JSON.stringify(draft), before);
});

test("Entra paid machine context coexists with six Basic/research scopes without changing them", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "entra-external-id" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 5);
  const report = await inspectBaselinePack(observed);
  const entra = report.options.filter((option) => option.providerId === "entra-external-id");
  assert.equal(entra.length, 7);
  assert.equal(entra.reduce((count, option) => count + option.facts.length, 0), 24);
  const earlier = entra.filter((option) => option.basis !== "MACHINE_SCOPED_DOCUMENTATION_DRAFT");
  assert.ok(earlier.every((option) => !option.facts.some((fact) => paths.includes(fact.path))));
  assert.ok(earlier.filter((option) => option.basis !== "UNRESOLVED_RESEARCH_SCOPE").every((option) => option.sourcePlan === "Basic MAU"));
  const native = earlier.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T23:12:01Z");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").availability, "UNKNOWN");
  const organizations = earlier.find((option) => option.basis === "ORGANIZATION_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(organizations.facts.find((fact) => fact.path === "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS").support, "UNKNOWN");
  assert.equal(JSON.stringify(originals), before);
  const reordered = structuredClone(draft);
  reordered.options[0].compatibility = Object.fromEntries(Object.entries(reordered.options[0].compatibility).reverse());
  for (const fact of [reordered.options[0].facts.OAUTH2_APIS, reordered.options[0].compatibility.clients.MACHINE_TO_MACHINE]) fact.conditions.reverse();
  const reorderedBefore = JSON.stringify(reordered);
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  assert.equal(JSON.stringify(reordered), reorderedBefore);
});
