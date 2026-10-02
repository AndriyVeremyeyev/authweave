import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { inspectBaselineDrafts, readBaselineDrafts } from "../scripts/inspect-provider-baselines.mjs";

const drafts = await readBaselineDrafts();
const observedAt = new Date("2026-10-02T19:58:06Z");
const copy = () => structuredClone(drafts);

function assertUntrusted(report) {
  for (const flag of ["sourceVerificationPerformed", "approvalGranted", "writesPerformed", "fullCoverageEstablished", "evaluationReady"]) {
    assert.equal(report[flag], false, flag);
  }
  for (const option of report.options) for (const fact of option.facts) {
    assert.equal(fact.evidenceStatus, "UNREVIEWED");
    assert.equal(fact.availability, "UNKNOWN");
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
});
