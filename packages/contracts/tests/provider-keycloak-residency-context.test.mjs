import assert from "node:assert/strict";
import test from "node:test";

import { inspectBaselinePack, inspectScopedBaselineDraft, readScopedBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readScopedBaselineDrafts();
const draft = drafts.find((entry) => entry.catalogVersion === "keycloak-26.8.0-operator-residency-draft-2026.10.08");
const observed = new Date("2026-10-08T22:10:43Z");
const residency = draft.options[0].residency;
const paths = ["AUDIT_LOGS", "BACKUPS", "CREDENTIALS", "USER_PROFILES"].map((category) => "residency." + category);
const commit = "4246609cf2024c85016d3fb1254c3d2533367c31";
const inspect = () => inspectScopedBaselineDraft(draft, observed);

test("Keycloak operator residency is release-pinned category evidence, not capability or country assurance", () => {
  const report = inspect();
  const option = report.options[0];
  assert.equal(option.basis, "RESIDENCY_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.product, "Keycloak upstream 26.8.0");
  assert.equal(option.sourceRelease, "26.8.0");
  assert.equal(option.sourceCommit, commit);
  assert.equal(Object.hasOwn(option, "sourcePlan"), false);
  assert.equal(report.factCount, 4);
  assert.deepEqual(option.facts.map((fact) => fact.path), paths);
  for (const fact of option.facts) {
    assert.equal(fact.coverage, "UNKNOWN");
    assert.deepEqual(fact.storageCountries, []);
    for (const field of ["availability", "support", "enforcement"]) assert.equal(Object.hasOwn(fact, field), false);
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.freshness, "CURRENT");
    assert.ok(fact.evidence.sourceUrl.startsWith("https://github.com/keycloak/keycloak/blob/" + commit + "/"));
  }
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false);
  }
});

test("native profile and credential storage leave operator geography, copies and encryption unverified", () => {
  const users = residency.USER_PROFILES.conditions.join(" ");
  assert.match(users, /native local users.*operator-selected relational database/);
  assert.match(users, /No LDAP, upstream identity provider, custom User Storage SPI or managed Keycloak service/);
  assert.match(users, /storage mechanism, not a country/);
  assert.match(users, /Primary storage, replicas, snapshots, exports.*unverified/);
  assert.match(users, /User caches and default persisted sessions.*separate from a verified at-rest inventory/);
  const credentials = residency.CREDENTIALS.conditions.join(" ");
  assert.match(credentials, /hashed user passwords, client credentials and realm signing keys/);
  assert.match(credentials, /Hashing and recommended encryption do not identify storage countries or prove encryption is enabled/);
  assert.match(credentials, /database files, WAL\/redo copies and backup storage/);
  assert.match(credentials, /TLS connection or operator-selected region is not proof/);
  assert.match(credentials, /No deployment, credential inspection or encryption setting was changed/);
});

test("residency of event stores is separate from audit completeness, retention and immutable history", () => {
  const conditions = residency.AUDIT_LOGS.conditions.join(" ");
  assert.match(conditions, /persistence is configurable and not enabled by default/);
  assert.match(conditions, /Unknown log destinations do not mean no logs exist/);
  assert.match(conditions, /does not certify event completeness, retention or immutability/);
  assert.match(conditions, /Admin-event storage is a separate setting.*representations/);
  assert.match(conditions, /Console, file and Syslog handlers route output differently/);
  assert.match(conditions, /console stream can be persisted by its runtime/);
  assert.match(conditions, /local paths and collector endpoints do not identify countries/);
  assert.match(conditions, /no event setting, collector, retention rule or live log retrieval was performed/);
});

test("Keycloak export limitations do not become complete backup, recovery, cost or deployment evidence", () => {
  const conditions = residency.BACKUPS.conditions.join(" ");
  assert.match(conditions, /Realm export is not a complete backup/);
  assert.match(conditions, /excludes user\/admin events, persisted sessions, workflow state and revoked tokens/);
  assert.match(conditions, /consistency requires stopped nodes.*not authorization to stop an installation or run export\/import/);
  assert.match(conditions, /partial export excludes users and masks sensitive values/);
  assert.match(conditions, /Database backups, WAL archives, snapshots, replicas and CLI export copies/);
  assert.match(conditions, /No backup, export, restore, snapshot, cloud storage, account, subscription or deployment was created/);
  assert.match(conditions, /No paid service or measured recovery objective is inferred/);
  assert.match(conditions, /AuthWeave's ZITADEL\/BFF, UI and synthetic evaluator are unchanged/);
});

test("residency inventory records four UNKNOWN categories without borrowing capability or context counts", () => {
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

test("residency inspection rejects hosted, country, complete-coverage, source and authority substitutions", () => {
  const mutations = [
    (value) => { value.catalogVersion = "keycloak-26.8.0-native-draft-2026.10.02"; },
    (value) => { value.options[0].id = "keycloak-26.8.0-native-self-hosted"; },
    (value) => { value.options[0].providerId = "zitadel"; },
    (value) => { value.options[0].product = "Managed Keycloak EU Cloud"; },
    (value) => { value.options[0].plan = "Hosted Free; verified infrastructure"; },
    (value) => { value.options[0].deployment = "MANAGED"; },
    (value) => { value.options[0].region = "EU; all storage verified"; },
    (value) => { value.options[0].configuration = "LDAP/custom storage; replicated globally"; },
    (value) => { value.options[0].residency.USER_PROFILES.coverage = "COMPLETE"; },
    (value) => { value.options[0].residency.USER_PROFILES.coverage = "PARTIAL"; },
    (value) => { value.options[0].residency.USER_PROFILES.storageCountries = ["DE"]; },
    (value) => { value.options[0].residency.USER_PROFILES.coverage = "COMPLETE"; value.options[0].residency.USER_PROFILES.storageCountries = ["DE"]; },
    (value) => { value.options[0].residency.CREDENTIALS.storageCountries = ["ZZ"]; },
    (value) => { value.options[0].residency.BACKUPS.storageCountries = ["de"]; },
    (value) => { value.options[0].residency.BACKUPS.storageCountries = ["DE", "DE"]; },
    (value) => { delete value.options[0].residency.AUDIT_LOGS; },
    (value) => { value.options[0].residency.CREDENTIALS.evidence.sourceUrl = value.options[0].residency.USER_PROFILES.evidence.sourceUrl; },
    (value) => { value.options[0].residency.USER_PROFILES.evidence.sourceUrl = "https://www.keycloak.org/server/db"; },
    (value) => { value.options[0].residency.AUDIT_LOGS.evidence.sourceUrl = "https://github.com/keycloak/keycloak/blob/main/docs/documentation/server_admin/topics/events/login.adoc"; },
    (value) => { value.options[0].residency.BACKUPS.evidence.sourceUrl = "https://example.com/backups"; },
    (value) => { value.options[0].residency.CREDENTIALS.availability = "SUPPORTED"; },
    (value) => { value.options[0].residency.BACKUPS.support = "SUPPORTED"; },
    (value) => { value.options[0].residency.USER_PROFILES.evidence.evidenceStatus = "REVIEWED"; },
    (value) => { value.options[0].residency.BACKUPS.evidence.observedAt = "2026-02-30T22:10:43Z"; },
    (value) => { value.options[0].residency.USER_PROFILES.conditions = []; },
    (value) => { const fact = value.options[0].residency.AUDIT_LOGS; fact.conditions.push(fact.conditions[0]); },
    (value) => { value.options[0].facts.OIDC = { availability: "OPTIONAL", conditions: ["Not inherited"], evidence: value.options[0].residency.USER_PROFILES.evidence }; },
    (value) => { value.options[0].compatibility.clients.BROWSER = { support: "SUPPORTED", conditions: ["Not inherited"], evidence: value.options[0].residency.USER_PROFILES.evidence }; },
    (value) => { value.options[0].authenticationControls.BROWSER = {}; },
    (value) => { value.options[0].authenticationControls.BROWSER = { EXTERNAL_CUSTOMERS: {} }; },
    (value) => { value.options[0].authenticationControls.MACHINE_TO_MACHINE = {}; },
    (value) => { value.sourceRelease = "26.8.1"; },
    (value) => { value.sourceCommit = "verified-deployment-commit"; },
    (value) => { value.approvalGranted = true; },
  ];
  mutations.forEach((change, index) => {
    const value = structuredClone(draft);
    change(value);
    assert.throws(() => inspectScopedBaselineDraft(value, observed), "mutation " + index);
  });
});

test("residency freshness, canonical ordering and returned arrays never refresh or mutate source evidence", () => {
  const before = JSON.stringify(draft);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectScopedBaselineDraft(draft, new Date(observed.getTime() + offset));
    assert.equal(report.schemaPathInventory.recordedFreshnessCounts[freshness], 4);
    assert.deepEqual(report.schemaPathInventory.options[0].recordedUnknownPaths, paths);
    assert.ok(report.options[0].facts.every((fact) => fact.evidence.observedAt === "2026-10-08T22:10:43Z"));
    assert.equal(report.approvalGranted, false);
    assert.equal(report.evaluationReady, false);
  }
  const reordered = structuredClone(draft);
  const option = reordered.options[0];
  option.residency = Object.fromEntries(Object.entries(option.residency).reverse());
  for (const fact of Object.values(option.residency)) fact.conditions.reverse();
  assert.deepEqual(inspectScopedBaselineDraft(reordered, observed), inspect());
  const report = inspect();
  report.options[0].facts[0].storageCountries.push("DE");
  report.options[0].facts[0].conditions.length = 0;
  report.schemaPathInventory.options[0].recordedUnknownPaths.length = 0;
  assert.equal(inspect().schemaPathInventory.options[0].recordedUnknownPaths.length, 4);
  assert.ok(inspect().options[0].facts.every((fact) => fact.storageCountries.length === 0 && fact.conditions.length > 0));
  assert.equal(JSON.stringify(draft), before);
});

test("operator residency coexists with eight older Keycloak scopes and cannot be borrowed by other scopes", async () => {
  const originals = drafts.filter((entry) => entry.options[0].providerId === "keycloak" && entry !== draft);
  const before = JSON.stringify(originals);
  assert.equal(originals.length, 7);
  assert.ok(originals.every((entry) => Object.keys(entry.options[0].residency).length === 0));
  const report = await inspectBaselinePack(observed);
  const keycloak = report.options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 9);
  assert.equal(keycloak.reduce((sum, option) => sum + option.facts.length, 0), 32);
  assert.ok(report.options.filter((option) => option.basis !== "RESIDENCY_SCOPED_DOCUMENTATION_DRAFT")
    .every((option) => !option.facts.some((fact) => fact.path.startsWith("residency."))));
  const native = keycloak.find((option) => option.basis === "RELEASE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").evidence.observedAt, "2026-10-02T21:20:39Z");
  assert.equal(native.facts.find((fact) => fact.path === "facts.SCIM").availability, "OPTIONAL");
  for (const catalogVersion of ["keycloak-26.8.0-public-oidc-clients-draft-2026.10.08", "zitadel-cloud-free-native-draft-2026.10.02"]) {
    const borrowed = structuredClone(drafts.find((entry) => entry.catalogVersion === catalogVersion));
    borrowed.options[0].residency = structuredClone(residency);
    assert.throws(() => inspectScopedBaselineDraft(borrowed, observed));
  }
  assert.equal(JSON.stringify(originals), before);
});
