import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assurancePlanningExpectation, validateAssurancePlanning, assuranceFlags } from "./helpers/assurance-compliance-planning-spec.mjs";

const root = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/assurance-compliance-planning-preflight.v1.schema.json");
const at = "2026-09-12T12:00:00Z";
const saved = { workspaceId: "60000000-0000-4000-8000-000000000001", id: "80000000-0000-4000-8000-000000000001", version: 7,
  profile: { application: { clients: ["BROWSER", "MACHINE_TO_MACHINE"] }, audience: { populations: ["EMPLOYEES"] }, security: {
    assurance: "HIGH", multiFactorAuthentication: "REQUIRED", authenticationControls: { phishingResistance: "REQUIRED", nonExportableKeys: "NOT_REQUIRED", stepUpAuthentication: "PREFERRED" },
    complianceScopeStatus: "TARGETS_IDENTIFIED", complianceTargets: ["SOC_2", "OTHER"] } } };
test("All label/scope/target subsets remain unverified, including contradictory pure-kernel inputs", () => {
  const targets = ["SOC_2", "ISO_27001", "HIPAA", "FEDRAMP", "GDPR", "OTHER"];
  for (const assurance of ["BASELINE", "ELEVATED", "HIGH", "UNKNOWN"]) for (const scope of ["UNKNOWN", "NONE_IDENTIFIED", "TARGETS_IDENTIFIED"]) for (let mask = 0; mask < 64; mask++) {
    const context = structuredClone(saved); Object.assign(context.profile.security, { assurance, complianceScopeStatus: scope, complianceTargets: targets.filter((_, i) => mask & 1 << i) });
    const result = assurancePlanningExpectation(context, at); assert.equal(validate(result), true, ajv.errorsText(validate.errors)); validateAssurancePlanning(result, context, at);
    assert.equal(result.status, "NEEDS_INFORMATION"); assert.equal(result.assuranceItems[0].status, "INPUT_CLARIFICATION_NEEDED");
    for (const flag of assuranceFlags) assert.equal(result[flag], false);
  }
});
test("Every client subset distinguishes unknown, human and machine-only flows; legacy controls and scope stay unknown", () => {
  const clients = ["BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"];
  for (let mask = 0; mask < 8; mask++) for (const populations of [[], ["EMPLOYEES", "PARTNERS"]]) {
    const context = structuredClone(saved); context.profile.application.clients = clients.filter((_, i) => mask & 1 << i); context.profile.audience.populations = populations;
    delete context.profile.security.authenticationControls; delete context.profile.security.complianceScopeStatus;
    const result = assurancePlanningExpectation(context, at); assert.equal(validate(result), true, ajv.errorsText(validate.errors));
    assert.equal(result.complianceScopeCheck.outcome, "UNKNOWN");
    assert.equal(result.assuranceItems[2].status, mask === 4 ? "NOT_APPLIED" : "INPUT_CLARIFICATION_NEEDED");
    assert.equal(result.assuranceItems[5].status, mask & 4 ? "EVIDENCE_NEEDED" : mask === 0 ? "INPUT_CLARIFICATION_NEEDED" : "NOT_APPLIED");
  }
});
test("All625 independent control combinations require clarification or evidence, never an assurance claim", () => {
  const criticalities = ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN", "UNKNOWN"];
  for (const mfa of criticalities) for (const phishing of criticalities) for (const keys of criticalities) for (const step of criticalities) {
    const context = structuredClone(saved); context.profile.security.multiFactorAuthentication = mfa;
    context.profile.security.authenticationControls = { phishingResistance: phishing, nonExportableKeys: keys, stepUpAuthentication: step };
    const result = assurancePlanningExpectation(context, at); assert.equal(validate(result), true, ajv.errorsText(validate.errors));
    assert.equal(result.assuranceItems[2].status, [mfa, phishing, keys, step].some(c => ["UNKNOWN", "FORBIDDEN"].includes(c)) ? "INPUT_CLARIFICATION_NEEDED" : "EVIDENCE_NEEDED");
  }
});
test("Schema rejects authority, extra provider evidence and partial inventory; independent replay rejects valid-shaped substitutions", () => {
  const result = assurancePlanningExpectation(saved, at); assert.equal(validate(result), true);
  for (const flag of assuranceFlags) assert.equal(validate({ ...result, [flag]: true }), false);
  for (const key of ["providerId", "sourceUrl", "certification", "winner", "score", "formalAssuranceLevel"]) assert.equal(validate({ ...result, [key]: "forged" }), false);
  assert.equal(validate({ ...result, assuranceItems: result.assuranceItems.slice(1) }), false);
  for (const mutate of [r => r.assessmentVersion++, r => r.evaluatedAt = "2026-09-12T12:00:01Z", r => r.inputs.assuranceExpectation = "BASELINE",
    r => r.inputs.controls.multiFactorAuthentication = "NOT_REQUIRED", r => r.inputs.clients.pop(), r => r.humanScope = "MACHINE_ONLY",
    r => r.assuranceItems.reverse(), r => r.assuranceItems[0] = r.assuranceItems[1], r => r.assuranceItems[2].status = "NOT_APPLIED",
    r => r.assuranceItems[0].question = "All assurance requirements met", r => r.complianceItems.pop(), r => r.complianceItems.reverse(),
    r => r.complianceItems[0].status = "EVIDENCE_NEEDED", r => r.complianceScopeCheck.outcome = "NOT_APPLIED", r => r.complianceQuestions[0] = "No evidence is necessary"]) {
    const forged = structuredClone(result); mutate(forged); assert.equal(validate(forged), true, ajv.errorsText(validate.errors)); assert.throws(() => validateAssurancePlanning(forged, saved, at));
  }
});
