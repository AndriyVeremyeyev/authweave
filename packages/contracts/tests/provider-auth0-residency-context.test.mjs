import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "auth0-b2b-free-residency-draft-2026.10.09");
const observed = new Date("2026-10-09T13:27:39Z");
const residency = draft.options[0].residency;
const paths = ["AUDIT_LOGS", "BACKUPS", "CREDENTIALS", "USER_PROFILES"].map((category) => "residency." + category);
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("Free Public Cloud residency records four dated UNKNOWN categories without release, entitlement or country assurance", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "RESIDENCY_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.product, "Auth0 Public Cloud");
  assert.equal(option.deployment, "MANAGED");
  assert.equal(option.sourcePlan, "B2B Free");
  for (const field of ["sourceRelease", "sourceCommit"]) assert.equal(Object.hasOwn(option, field), false);
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), paths);
  for (const fact of option.facts) {
    assert.equal(fact.coverage, "UNKNOWN");
    assert.deepEqual(fact.storageCountries, []);
    for (const field of ["availability", "support", "enforcement"]) assert.equal(Object.hasOwn(fact, field), false);
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.ok(["auth0.com", "support.auth0.com"].includes(new URL(fact.evidence.sourceUrl).hostname));
  }
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("hosted database and tenant localities do not establish every country or inherit external and Private Cloud placement", () => {
  const conditions = residency.USER_PROFILES.conditions.join(" ");
  assert.match(conditions, /B2B Free Public Cloud.*not Private Cloud or a custom external database/);
  assert.match(conditions, /no tenant, region, account entitlement or applicable terms were inspected/);
  assert.match(conditions, /Australia, Canada, Europe, Japan, United Kingdom and United States localities/);
  assert.match(conditions, /Region\/domain labels and assigned sub-localities do not enumerate every at-rest country/);
  assert.match(conditions, /profile copies from external connections/);
  assert.match(conditions, /Federation, custom databases, Actions, application copies and exports.*excluded here/);
  assert.match(conditions, /No Private Cloud regional or recovery guarantee is inherited/);
  assert.match(conditions, /not full coverage or a compliance verdict/);
});

test("password-hash and private-key API export limits are not country or non-exportable-key evidence", () => {
  const conditions = residency.CREDENTIALS.conditions.join(" ");
  assert.match(conditions, /excludes Auth0-hosted password hashes and private keys from Management API access/);
  assert.match(conditions, /obtaining password hashes requires a support request/);
  assert.match(conditions, /not proof of country coverage, hardware-only protection or key non-exportability/);
  assert.match(conditions, /Hashing, encryption, a tenant domain or credential export restrictions do not locate every stored copy/);
  assert.match(conditions, /Customer-managed credential stores, federation and exported credentials are outside/);
  assert.match(conditions, /no password, key, support ticket, enrollment, API token or tenant configuration was retrieved or changed/);
});

test("retention, streaming and customer restore limitations do not imply log absence or complete tenant recovery", () => {
  const logs = residency.AUDIT_LOGS.conditions.join(" ");
  assert.match(logs, /lowest tier Starter.*indexed tenant logs can be delayed/);
  assert.match(logs, /Free pricing table advertises one day.*https:\/\/auth0.com\/pricing/);
  assert.match(logs, /not verified tenant entitlement, measured retention, complete auditing or immutable history/);
  assert.match(logs, /Free comparison does not include a stream.*does not establish Free entitlement/);
  assert.match(logs, /No stream, SIEM, webhook or external collector is configured/);
  assert.match(logs, /UNKNOWN with empty countries does not mean logs are absent/);
  const backups = residency.BACKUPS.conditions.join(" ");
  assert.match(backups, /January 5, 2026.*tenant administrators rather than providing a customer backup\/restore service/);
  assert.match(backups, /does not establish that provider-internal disaster-recovery copies are absent/);
  assert.match(backups, /Deploy CLI does not back up user information/);
  assert.match(backups, /ordinary exports exclude hosted password hashes/);
  assert.match(backups, /configuration export is not complete tenant recovery/);
  assert.match(backups, /No Private Cloud restoration SKU.*measured RPO\/RTO or Free support entitlement is inherited/);
  assert.match(backups, /No account, subscription, support ticket, backup, export\/import, restore/);
  assert.match(backups, /ZITADEL\/BFF, UI and synthetic evaluator are unchanged/);
});

test("Free residency inventory preserves UNKNOWN records separately from capabilities, compatibility and controls", () => {
  const inventory = inspect().schemaPathInventory;
  assert.deepEqual(inventory.proposedAvailabilityCounts, { OPTIONAL: 0, MANDATORY: 0, UNAVAILABLE: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.proposedCompatibilityCounts, { SUPPORTED: 0, UNSUPPORTED: 0, UNKNOWN: 0 });
  assert.deepEqual(inventory.recordedFamilyCounts, { CAPABILITY: 0, COMPATIBILITY: 0, RESIDENCY: 4, AUTHENTICATION_CONTROL: 0 });
  assert.deepEqual(inventory.recordedFreshnessCounts, { CURRENT: 4, STALE: 0, FUTURE: 0 });
  const option = inventory.options[0];
  assert.equal(option.recordedPathCount, 4);
  assert.equal(option.omittedPathCount, 64);
  assert.deepEqual(option.recordedUnknownPaths, paths);
  assert.deepEqual(option.families[2].recordedPaths, paths);
  assert.deepEqual(option.families[2].omittedPaths, []);
  assert.ok(option.families[0].omittedPaths.includes("facts.OIDC"));
  assert.ok(option.families[1].omittedPaths.includes("compatibility.clients.BROWSER"));
  assert.ok(option.families[3].omittedPaths.includes("authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE"));
  assert.deepEqual(draft.options[0].facts, {});
  assert.deepEqual(Object.values(draft.options[0].compatibility), [{}, {}, {}, {}, {}]);
  assert.deepEqual(draft.options[0].authenticationControls, {});
  assert.equal(inventory.requirementCoverageEstablished, false);
  assert.equal(inventory.evaluationReady, false);
});

test("residency inspection rejects paid, trial, external-store, country, source and authority substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "auth0-b2b-free-native-draft-2026.10.02"; },
    (value) => { value.options[0].id = "auth0-b2b-free-native"; },
    (value) => { value.options[0].providerId = "zitadel"; },
    (value) => { value.options[0].product = "Auth0 Private Cloud"; },
    (value) => { value.options[0].plan = "Enterprise; verified backup and residency SKU"; },
    (value) => { value.options[0].plan = "Free trial; all paid features"; },
    (value) => { value.options[0].deployment = "SELF_HOSTED"; },
    (value) => { value.options[0].region = "EU-2; all storage verified"; },
    (value) => { value.options[0].configuration = "Custom external database; exports and SIEM"; },
    (value) => { value.options[0].residency.USER_PROFILES.coverage = "COMPLETE"; },
    (value) => { value.options[0].residency.USER_PROFILES.coverage = "PARTIAL"; },
    (value) => { value.options[0].residency.USER_PROFILES.storageCountries = ["US"]; },
    (value) => { value.options[0].residency.USER_PROFILES.coverage = "COMPLETE"; value.options[0].residency.USER_PROFILES.storageCountries = ["EU"]; },
    (value) => { value.options[0].residency.BACKUPS.coverage = "PARTIAL"; value.options[0].residency.BACKUPS.storageCountries = ["GB"]; },
    (value) => { value.options[0].residency.CREDENTIALS.storageCountries = ["ZZ"]; },
    (value) => { value.options[0].residency.BACKUPS.storageCountries = ["gb"]; },
    (value) => { value.options[0].residency.BACKUPS.storageCountries = ["GB", "GB"]; },
    (value) => { delete value.options[0].residency.AUDIT_LOGS; },
    (value) => { value.options[0].residency.BACKUPS.evidence.sourceUrl = value.options[0].residency.USER_PROFILES.evidence.sourceUrl; },
    (value) => { value.options[0].residency.USER_PROFILES.evidence.sourceUrl = "https://auth0.com/pricing"; },
    (value) => { value.options[0].residency.CREDENTIALS.evidence.sourceUrl = "https://auth0.com/docs/deploy-monitor/deploy-private-cloud/private-cloud-on-aws"; },
    (value) => { value.options[0].residency.AUDIT_LOGS.evidence.sourceUrl = "https://example.com/audit"; },
    (value) => { value.options[0].residency.CREDENTIALS.availability = "SUPPORTED"; },
    (value) => { value.options[0].residency.BACKUPS.support = "SUPPORTED"; },
    (value) => { value.options[0].residency.USER_PROFILES.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].residency.BACKUPS.evidence.observedAt = "2026-02-30T13:27:39Z"; },
    (value) => { value.options[0].residency.USER_PROFILES.conditions = []; },
    (value) => { const fact = value.options[0].residency.AUDIT_LOGS; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OIDC = { availability: "OPTIONAL", conditions: ["Not inherited"], evidence: value.options[0].residency.USER_PROFILES.evidence }; },
    (value) => { value.options[0].compatibility.clients.BROWSER = { support: "SUPPORTED", conditions: ["Not inherited"], evidence: value.options[0].residency.USER_PROFILES.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = {}; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EXTERNAL_CUSTOMERS: {} }; },
    (value) => { value.options[0].authenticationControls.MACHINE_TO_MACHINE = {}; },
    (value) => { value.sourceRelease = "4.0.0"; },
    (value) => { value.sourceCommit = "verified-cloud-deployment"; },
    (value) => { value.approvalGranted = true; },
  ];
  mutations.forEach((change, index) => {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed), "mutation " + index);
  });
});

test("residency freshness, canonical ordering and returned-array mutations do not alter source evidence or trust", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, paths);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-09T13:27:39Z"));
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  const reordered = structuredClone(draft);
  reordered.options[0].residency = Object.fromEntries(Object.entries(reordered.options[0].residency).reverse());
  for (const fact of Object.values(reordered.options[0].residency)) fact.conditions.reverse();
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  const report = inspect();
  report.options[0].facts[0].storageCountries.push("GB");
  report.options[0].facts[0].conditions.length = 0;
  report.schemaPathInventory.options[0].recordedUnknownPaths.length = 0;
  assert.equal(inspect().schemaPathInventory.options[0].recordedUnknownPaths.length, 4);
  assert.ok(inspect().options[0].facts.every((fact) => fact.storageCountries.length === 0 && fact.conditions.length > 0));
  assert.equal(JSON.stringify(draft), before);
});

test("residency coexists with eight older Auth0 scopes without borrowing ZITADEL or self-hosted operator evidence", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "auth0" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 7);
  assert.ok(originals.every((entry) => Object.keys(entry.options[0].residency).length === 0));
  const report = await inspectBaselinePack(observed);
  const options = report.options.filter((option) => option.providerId === "auth0");
  assert.equal(options.length, 9);
  assert.equal(options.reduce((sum, option) => sum + option.facts.length, 0), 30);
  const native = options.find((option) => option.basis === "PLAN_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").availability, "OPTIONAL");
  assert.equal(native.facts.find((fact) => fact.path === "facts.GROUP_SYNC").availability, "UNKNOWN");
  assert.equal(options.find((option) => option.basis === "AUTHENTICATION_SCOPED_DOCUMENTATION_DRAFT")
    .facts.find((fact) => fact.path === "facts.MFA").availability, "UNKNOWN");
  for (const providerId of ["zitadel", "keycloak"]) {
    const other = drafts.find((entry) => entry.options[0].providerId === providerId && Object.keys(entry.options[0].residency).length > 0);
    const original = JSON.stringify(other);
    const borrowed = structuredClone(draft);
    borrowed.options[0].residency = structuredClone(other.options[0].residency);
    assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
    assert.equal(JSON.stringify(other), original);
  }
  for (const catalogVersion of ["auth0-b2b-free-public-oidc-clients-draft-2026.10.08", "zitadel-cloud-free-native-draft-2026.10.02"]) {
    const borrowed = structuredClone(drafts.find((entry) => entry.catalogVersion === catalogVersion));
    borrowed.options[0].residency = structuredClone(residency);
    assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
  }
  assert.equal(JSON.stringify(originals), before);
});
