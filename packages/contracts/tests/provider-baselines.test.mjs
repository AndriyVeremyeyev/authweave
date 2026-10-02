import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  inspectBaselineDrafts, inspectBaselinePack, inspectScopedBaselineDraft,
  readBaselineDrafts, readScopedBaselineDraft,
} from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readBaselineDrafts();
const scopedDraft = await readScopedBaselineDraft();
const observedAt = new Date("2026-10-02T19:58:06Z");
const copy = () => structuredClone(drafts);

function assertUntrusted(report) {
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false, flag);
  }
  for (const option of report.options) for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
  }
}

test("five real-provider research drafts remain partial, unknown and unreviewed", () => {
  const report = inspectBaselineDrafts(drafts, observedAt);
  assertUntrusted(report);
  assert.equal(report.optionCount, 5);
  assert.equal(report.factCount, 15);
  assert.deepEqual(report.options.map((option) => option.providerId), ["auth0", "entra-external-id", "keycloak", "workos", "zitadel"]);
  for (const option of report.options) {
    assert.equal(option.omittedCapabilities.length, 6);
    assert.deepEqual(option.facts.map((fact) => fact.path), ["facts.GROUP_SYNC", "facts.OIDC", "facts.SCIM"]);
    assert.ok(option.deferredDimensions.includes("RESIDENCY"));
    assert.ok(option.facts.every((fact) => fact.freshness === "CURRENT"));
    assert.ok(option.facts.every((fact) => fact.availability === "UNKNOWN"));
  }
  assert.deepEqual(inspectBaselineDrafts(drafts.toReversed(), observedAt), report);
});

test("freshness has an inclusive 90-day boundary without refreshing observations or trust", () => {
  const before = JSON.stringify(drafts);
  for (const [offset, freshness] of [[-1, "FUTURE"], [0, "CURRENT"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const report = inspectBaselineDrafts(drafts, new Date(observedAt.getTime() + offset));
    assertUntrusted(report);
    assert.ok(report.options.every((option) => option.facts.every((fact) => fact.freshness === freshness)));
    assert.ok(report.options.every((option) => option.facts.every((fact) => fact.evidence.observedAt === "2026-10-02T19:58:06Z")));
  }
  assert.equal(JSON.stringify(drafts), before);
  assert.throws(() => inspectBaselineDrafts(drafts, new Date("invalid")), /Invalid inspection time/);
});

test("the pack preserves provisioning directions and provider-specific unknowns", () => {
  const entry = (provider, capability) => drafts.find((draft) => draft.options[0].providerId === provider).options[0].facts[capability];
  assert.match(entry("entra-external-id", "SCIM").conditions.join(" "), /outbound.*inbound/);
  assert.match(entry("auth0", "SCIM").conditions.join(" "), /Enterprise Connections/);
  assert.match(entry("workos", "SCIM").conditions.join(" "), /read-only/);
  assert.match(entry("zitadel", "GROUP_SYNC").evidence.summary, /excludes Group provisioning/);
  assert.match(entry("keycloak", "SCIM").evidence.summary, /unresolved, not unavailable/);
});

test("a release-scoped Keycloak draft proposes optional capabilities without review or runtime verification", () => {
  const at = new Date("2026-10-02T21:20:39Z");
  const before = JSON.stringify(scopedDraft);
  const report = inspectScopedBaselineDraft(scopedDraft, at);
  assertUntrusted(report);
  assert.equal(report.optionCount, 1);
  assert.equal(report.factCount, 4);
  const option = report.options[0];
  assert.equal(option.basis, "RELEASE_SCOPED_DOCUMENTATION_DRAFT");
  assert.equal(option.sourceRelease, "26.8.0");
  assert.equal(option.sourceCommit, "4246609cf2024c85016d3fb1254c3d2533367c31");
  assert.ok(option.facts.every((fact) => fact.availability === "OPTIONAL" && fact.freshness === "CURRENT"));
  assert.equal(option.omittedCapabilities.length, 5);
  for (const [offset, freshness] of [[-1, "FUTURE"], [90 * 86400000, "CURRENT"], [90 * 86400000 + 1, "STALE"]]) {
    const later = inspectScopedBaselineDraft(scopedDraft, new Date(at.getTime() + offset));
    assertUntrusted(later);
    assert.ok(later.options[0].facts.every((fact) => fact.freshness === freshness));
  }
  assert.equal(JSON.stringify(scopedDraft), before);
  const claims = scopedDraft.options[0].facts;
  assert.match(claims.SCIM.conditions.join(" "), /inbound realm provisioning/);
  assert.match(claims.SCIM.conditions.join(" "), /confidential.*permissions.*audience/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /PATCH.*POST or PUT/);
  assert.match(claims.GROUP_SYNC.conditions.join(" "), /Organization API/);
});

test("scoped inspection rejects release drift, moving URLs, changed scope and invented coverage", () => {
  const mutations = [
    (input) => { input.approvalGranted = true; },
    (input) => { input.catalogVersion = "keycloak-latest"; },
    (input) => { input.options[0].product = "Keycloak upstream latest"; },
    (input) => { input.options[0].plan = "Managed enterprise"; },
    (input) => { input.options[0].region = "EU"; },
    (input) => { input.options[0].configuration = "SCIM via a third-party extension"; },
    (input) => { input.options[0].deployment = "MANAGED"; },
    (input) => { input.options[0].facts.SCIM.availability = "MANDATORY"; },
    (input) => { input.options[0].facts.SCIM.conditions = []; },
    (input) => { input.options[0].facts.SCIM.conditions.push(input.options[0].facts.SCIM.conditions[0]); },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = input.options[0].facts.SCIM.evidence.sourceUrl.replace("4246609cf2024c85016d3fb1254c3d2533367c31", "main"); },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl += "?reviewed=true"; },
    (input) => { input.options[0].facts.SCIM.evidence.sourceUrl = input.options[0].facts.OIDC.evidence.sourceUrl; },
    (input) => { input.options[0].facts.OIDC.evidence.sourceUrl = "https://www.keycloak.org/docs/latest/server_admin/index.html"; },
    (input) => { delete input.options[0].facts.SAML; },
    (input) => { input.options.push(structuredClone(input.options[0])); },
    (input) => { input.options[0].compatibility.clients.BROWSER = {
      support: "SUPPORTED", conditions: [], evidence: structuredClone(input.options[0].facts.OIDC.evidence),
    }; },
  ];
  for (const mutate of mutations) {
    const input = structuredClone(scopedDraft); mutate(input);
    assert.throws(() => inspectScopedBaselineDraft(input, observedAt));
  }
});

test("combined inspection keeps research and scoped options distinct without promoting either", async () => {
  const report = await inspectBaselinePack(new Date("2026-10-02T21:20:39Z"));
  assertUntrusted(report);
  assert.equal(report.scope, "PROVIDER_BASELINE_PACK_INSPECTION");
  assert.equal(report.optionCount, 6);
  assert.equal(report.factCount, 19);
  assert.equal(report.researchOptionCount, 5);
  assert.equal(report.scopedDraftOptionCount, 1);
  const keycloak = report.options.filter((option) => option.providerId === "keycloak");
  assert.equal(keycloak.length, 2);
  assert.notEqual(keycloak[0].optionId, keycloak[1].optionId);
  assert.equal(keycloak.filter((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").length, 1);
  assert.ok(keycloak.find((option) => option.basis === "UNRESOLVED_RESEARCH_SCOPE").facts.every((fact) => fact.availability === "UNKNOWN"));
  assert.deepEqual(await inspectBaselinePack(new Date("2026-10-02T21:20:39Z")), report);
});

test("research inspection rejects malformed, promoted, duplicated and cross-provider inputs", () => {
  const mutations = [
    (input) => { input[0].kind = "SYNTHETIC"; },
    (input) => { input[0].approvalGranted = true; },
    (input) => { input[0].options[0].facts.SCIM.availability = "OPTIONAL"; },
    (input) => { input[0].options[0].facts.SCIM.conditions = []; },
    (input) => { input[0].options[0].facts.SCIM.conditions.push(input[0].options[0].facts.SCIM.conditions[0]); },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://learn.microsoft.com.attacker.invalid/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://auth0.com/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://user@learn.microsoft.com/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.sourceUrl = "https://learn.microsoft.com:8443/docs"; },
    (input) => { input[0].options[0].facts.SCIM.evidence.observedAt = "2026-02-30T19:58:06Z"; },
    (input) => { input[0].options[0].deployment = "SELF_HOSTED"; },
    (input) => { input[0].options[0].residency.USER_PROFILES = {
      coverage: "COMPLETE", storageCountries: ["DE"], conditions: [],
      evidence: structuredClone(input[0].options[0].facts.OIDC.evidence),
    }; },
    (input) => { input[0].options[0].compatibility.applications.B2B_SAAS = {
      support: "SUPPORTED", conditions: [], evidence: structuredClone(input[0].options[0].facts.OIDC.evidence),
    }; },
    (input) => { input[0].options.push(structuredClone(input[0].options[0])); },
    (input) => { input[0].options[0].providerId = "other-vendor"; },
    (input) => { input[1] = structuredClone(input[0]); },
    (input) => { input[0].catalogVersion = "other-version"; },
    (input) => { delete input[0].options[0].facts.SCIM; },
    (input) => { input.pop(); },
    (input) => { input.push(structuredClone(input[0])); },
  ];
  for (const mutate of mutations) {
    const input = copy(); mutate(input);
    assert.throws(() => inspectBaselineDrafts(input, observedAt));
  }
});

test("CLI reads only fixed local inputs and accepts no arbitrary source argument", () => {
  const script = new URL("../scripts/inspect-provider-baselines.mjs", import.meta.url);
  const run = spawnSync(process.execPath, [script.pathname], { encoding: "utf8" });
  assert.equal(run.status, 0, run.stderr);
  assertUntrusted(JSON.parse(run.stdout));
  assert.equal(JSON.parse(run.stdout).factCount, 19);
  const rejected = spawnSync(process.execPath, [script.pathname, "https://attacker.invalid/catalog"], { encoding: "utf8" });
  assert.equal(rejected.status, 1);
  assert.equal(rejected.stdout, "");
  assert.match(rejected.stderr, /accepts no file paths, URLs or options/);
});

test("active catalog remains the separate synthetic fixture", async () => {
  const catalog = JSON.parse(await readFile(new URL(
    "../../../services/core-api/src/main/resources/catalog/synthetic.v4.json", import.meta.url,
  ), "utf8"));
  assert.equal(catalog.kind, "SYNTHETIC");
  assert.ok(catalog.options.every((option) => !drafts.some((draft) => draft.options[0].id === option.id)));
  assert.ok(catalog.options.every((option) => option.id !== scopedDraft.options[0].id));
});
