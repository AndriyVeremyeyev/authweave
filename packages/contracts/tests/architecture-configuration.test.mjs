import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { flags, patterns, values, expectedAnalysis, expectedDefinitions } from "./helpers/architecture-configuration-spec.mjs";

const root = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const request = ajv.getSchema("https://authweave.dev/contracts/architecture-configuration-request.v1.schema.json");
const analysis = ajv.getSchema("https://authweave.dev/contracts/architecture-configuration-preview.v1.schema.json#/$defs/analysis");

test("Five reference patterns accept only their typed settings and bounded versions", () => {
  for (const [patternId, pattern] of Object.entries(patterns)) {
    const baseline = { expectedVersion: 0, patternId, settings: {} };
    assert.equal(request(baseline), true, ajv.errorsText(request.errors));
    assert.equal(request({ ...baseline, expectedVersion: 9007199254740991 }), true);
    for (const id of Object.keys(values)) for (const value of [...new Set(Object.values(values).flat())]) {
      assert.equal(request({ ...baseline, settings: { [id]: value } }), pattern.ids.includes(id) && values[id].includes(value), `${patternId}/${id}/${value}`);
    }
    for (const invalid of [-1, 0.5, "0", true, null, 9007199254740992]) assert.equal(request({ ...baseline, expectedVersion: invalid }), false);
    for (const field of ["expectedVersion", "patternId", "settings"]) { const missing = { ...baseline }; delete missing[field]; assert.equal(request(missing), false); }
    for (const field of ["clientScope", "requirements", "evaluatedAt", "configurationObserved", "evidence", "recommendationReady", "clientSecret", "issuer", "providerId"])
      assert.equal(request({ ...baseline, [field]: "synthetic" }), false);
    for (const invalid of [null, 1, true, [], {}, "s256", "VERIFIED"]) assert.equal(request({ ...baseline, settings: { PKCE_METHOD: invalid } }), false);
  }
});

test("Conditional analysis never promotes unknowns, skips or unmet settings to a verified match", () => {
  for (const [patternId, pattern] of Object.entries(patterns)) for (const scope of ["SELECTED", "NOT_SELECTED", "UNKNOWN"]) {
    const settings = Object.fromEntries(pattern.ids.map((id, index) => [id, [pattern.values[index]].flat()[0]]));
    for (const id of pattern.ids) for (const value of values[id]) {
      const result = expectedAnalysis(patternId, scope, { ...settings, [id]: value });
      assert.equal(analysis(result), true, ajv.errorsText(analysis.errors));
      for (const flag of flags) { const invalid = { ...result, [flag]: true }; assert.equal(analysis(invalid), false); }
      const wrong = structuredClone(result); wrong.checks[0].reasonCode = wrong.checks[0].reasonCode === "EXPECTED_SETTING_DECLARED" ? "SETTING_UNKNOWN" : "EXPECTED_SETTING_DECLARED";
      assert.equal(analysis(wrong), false, "Outcome must agree with the conditional reason");
      const wrongStatus = { ...result, status: result.status === "NOT_APPLICABLE" ? "CONDITIONALLY_MATCHES" : "NOT_APPLICABLE" };
      assert.equal(analysis(wrongStatus), false);
    }
    const mixed = expectedAnalysis(patternId, scope, { OAUTH_FLOW: patternId === "M2M_CLIENT_CREDENTIALS" ? "AUTHORIZATION_CODE" : "CLIENT_CREDENTIALS" });
    assert.equal(analysis(mixed), true, ajv.errorsText(analysis.errors));
    if (scope === "SELECTED") { assert.equal(mixed.status, "CONDITIONALLY_DOES_NOT_MATCH"); assert.ok(mixed.checks.some(c => c.outcome === "UNKNOWN")); }
    assert.equal(expectedDefinitions(patternId).length, pattern.ids.length);
  }
});

test("PKCE is a conservative project policy and the redirect port exception is native-only", () => {
  for (const patternId of Object.keys(patterns)) {
    const definitions = expectedDefinitions(patternId), pkce = definitions.find(d => d.settingId === "PKCE_METHOD");
    if (pkce) { assert.match(pkce.description, /not a universal normative MUST/); assert.deepEqual(pkce.compatibleValues, ["S256"]); }
    const redirect = definitions.find(d => d.settingId === "REDIRECT_MATCHING");
    if (redirect) assert.equal(redirect.compatibleValues.includes("NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION"), patternId === "NATIVE_CODE_PKCE");
  }
});
