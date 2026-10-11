import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { bootstrapDigest, prepareDecisionCandidate } from "../scripts/prepare-decision-candidate.mjs";
import { assistantAssessmentFromInput, bootstrapReviewSubmission } from "../../../apps/web/src/lib/catalog/bootstrap-review.ts";

const readJson = async relative => JSON.parse(await readFile(new URL(relative, import.meta.url), "utf8"));
const findings = await readJson("../decision-core/assistant-assessment.2026-10-11.v1.json");
const assembly = await prepareDecisionCandidate();
const before = JSON.stringify(assembly);
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
ajv.addSchema(await readJson("../schemas/provider-catalog-draft.v1.schema.json"));
ajv.addSchema(await readJson("../schemas/catalog-bootstrap-review-request.v1.schema.json"));
const validate = ajv.compile(await readJson("../schemas/assistant-bootstrap-assessment.v1.schema.json"));
const address = entry => `${entry.optionId}/${entry.factPath}`;
const proposed = entry => {
  const option = assembly.candidate.options.find(option => option.id === entry.optionId);
  return entry.factPath.split(".").reduce((value, key) => value[key], option);
};

test("dated assistant findings match the existing import schema, exact candidate and complete unique targets", () => {
  assert.equal(validate(findings), true, ajv.errorsText(validate.errors));
  assert.equal(assembly.candidate.catalogVersion, "decision-core-candidate-2026.10.10-residency1");
  assert.equal(findings.candidateSha256, assembly.bindings.bootstrapCandidateSha256);
  assert.equal(findings.candidateSha256, bootstrapDigest(assembly.candidate));
  assert.notEqual(findings.candidateSha256, assembly.bindings.decisionCatalogSha256);
  assert.equal(findings.observations.length, 48);
  assert.equal(new Set(findings.observations.map(address)).size, 48);
  assert.deepEqual(findings.observations.map(address).sort(), assembly.reviewTasks.map(address).sort());
  assert.equal(new Set(findings.observations.map(entry => entry.optionId)).size, 8);
  assert.equal(new Set(findings.observations.map(entry => proposed(entry).evidence.sourceUrl)).size, 30);
});

test("source support preserves 23 unknown claims and the passkey mechanism/enforcement distinction", () => {
  // Findings assess the cautious proposal, not availability on an untested account.
  const unknown = findings.observations.filter(entry => Object.values(proposed(entry)).includes("UNKNOWN"));
  assert.equal(unknown.length, 24);
  const partial = unknown.filter(entry => proposed(entry).availability === "SUPPORTED");
  assert.equal(partial.length, 1);
  assert.equal(partial[0].factPath, "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE");
  assert.equal(proposed(partial[0]).enforcement, "UNKNOWN");
  const residency = unknown.filter(entry => entry.factPath.startsWith("residency."));
  assert.equal(residency.length, 16);
  for (const entry of residency) {
    assert.equal(proposed(entry).coverage, "UNKNOWN");
    assert.deepEqual(proposed(entry).storageCountries, []);
  }
  const absent = findings.observations.filter(entry => proposed(entry).availability === "UNAVAILABLE");
  assert.deepEqual(absent.map(address), ["zitadel-cloud-free-native/facts.GROUP_SYNC"]);
  for (const entry of findings.observations) assert.equal(entry.verdict, "SOURCE_SUPPORTS_CLAIM");
});

test("focused Keycloak dependencies are assessed without changing hard SCIM, primary cases or source dates", () => {
  const c = assembly.focusedCase;
  assert.equal(c.profile.provisioning.scim, "REQUIRED");
  assert.equal(c.profile.audience.tenancy, "SINGLE_ORGANIZATION");
  assert.equal(c.requiredFactPaths.length, 7);
  assert.deepEqual(c.weights.values, [{ capability: "SAML", weight: 100 }]);
  for (const factPath of [...c.requiredFactPaths, ...c.preferredFactPaths]) {
    assert(findings.observations.some(entry => entry.optionId === c.targetOptionId
      && entry.factPath === factPath && entry.verdict === "SOURCE_SUPPORTS_CLAIM"));
  }
  // Dated research is not a new observation instant or an endorsed/stored review.
  assert.equal(assembly.candidate.options[0].facts.SCIM.evidence.observedAt, "2026-10-02T21:20:39Z");
  assert.equal(JSON.stringify(assembly), before);
  assert(assembly.reviewTasks.every(task => task.verdict === null));
  assert.equal(assembly.sourceVerificationPerformed, false);
  assert.equal(assembly.approvalGranted, false);
  assert.equal(assembly.publicationReady, false);
});

test("research artifact supplies no confirmation, actor, receipt, refreshed clock or runtime authority", () => {
  assert.deepEqual(Object.keys(findings).sort(), ["candidateSha256", "kind", "observations", "schemaVersion"]);
  for (const entry of findings.observations) {
    assert.deepEqual(Object.keys(entry).sort(), ["factPath", "optionId", "verdict"]);
  }
  for (const extra of ["confirmation", "actor", "reviewId", "observedAt", "sourceVerificationPerformed", "approvalGranted", "publicationReady"]) {
    assert.equal(validate({ ...findings, [extra]: true }), false);
  }
  // A read-only assembly never discovers or applies an assessment by convention.
  assert.equal(JSON.stringify(assembly), before);
});

test("the existing UI imports the real 48 findings but cannot submit them without separate owner confirmation", () => {
  // Synthetic display preparation only: no authenticated Core preparation or write.
  const prepared = { reviewId: "10000000-0000-4000-8000-000000000001",
    candidateSha256: assembly.bindings.bootstrapCandidateSha256, candidate: assembly.candidate,
    evaluatedAt: "2026-10-11T00:00:00Z",
    facts: assembly.reviewTasks.map(task => ({ optionId: task.optionId, path: task.factPath })) };
  const choices = assistantAssessmentFromInput(findings, prepared);
  assert(choices);
  assert.equal(Object.keys(choices).length, 48);
  for (const entry of findings.observations) assert.equal(choices[`${entry.optionId}\0${entry.factPath}`], entry.verdict);
  assert.equal(bootstrapReviewSubmission(prepared, choices, false, "ASSISTANT"), null);
  for (const change of [
    { candidateSha256: assembly.bindings.decisionCatalogSha256 },
    { observations: findings.observations.slice(1) },
    { observations: [...findings.observations.slice(1), findings.observations[1]] },
    { observations: findings.observations.map((entry, i) => i ? entry : { ...entry, optionId: "foreign-option" }) },
    { confirmation: "OWNER_APPROVES_ASSISTANT_SOURCE_ASSESSMENT" },
  ]) assert.equal(assistantAssessmentFromInput({ ...findings, ...change }, prepared), null);
});
