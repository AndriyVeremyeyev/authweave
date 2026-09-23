import assert from "node:assert/strict";
import { test } from "node:test";

import { InvalidWeightForm, parseSensitivityForm, parseWeightForm, preferredCapabilities,
  weightsMatchPreferences } from "../src/lib/assessment/weights.ts";

const profile = {
  protocols: { federation: { OIDC: "PREFERRED" }, oauth2ProtectedApis: "UNKNOWN",
    socialLogin: "UNKNOWN", enterpriseSingleSignOn: "UNKNOWN" },
  provisioning: { scim: "REQUIRED", justInTimeProvisioning: "PREFERRED",
    groupSynchronization: "UNKNOWN" },
  security: { multiFactorAuthentication: "UNKNOWN" },
};

test("preferred capabilities come only from explicit profile choices", () => {
  assert.deepEqual(preferredCapabilities(profile), ["OIDC", "JIT"]);
  assert.equal(preferredCapabilities({ ...profile, security: {} }), null);
});

test("weight form requires canonical positive integer choices totaling 100", () => {
  const valid = new URLSearchParams({ expectedVersion: "7", OIDC: "60", JIT: "40" });
  assert.deepEqual(parseWeightForm(valid), { expectedVersion: 7, weights: { OIDC: 60, JIT: 40 } });
  assert.equal(weightsMatchPreferences({ OIDC: 60, JIT: 40 }, ["OIDC", "JIT"]), true);
  assert.equal(weightsMatchPreferences({ OIDC: 100 }, ["OIDC", "JIT"]), false);
  assert.equal(weightsMatchPreferences({ OIDC: 60, JIT: 40 }, ["OIDC", "SCIM"]), false);
  assert.deepEqual(parseWeightForm(new URLSearchParams({ expectedVersion: "0", OIDC: "100" })),
    { expectedVersion: 0, weights: { OIDC: 100 } });
  const invalidForms: Record<string, string>[] = [
    { expectedVersion: "1", OIDC: "60", JIT: "39" },
    { expectedVersion: "1", OIDC: "0", JIT: "100" },
    { expectedVersion: "1", OIDC: "060", JIT: "40" },
    { expectedVersion: "01", OIDC: "60", JIT: "40" },
    { expectedVersion: "9007199254740992", OIDC: "100" },
    { expectedVersion: "1", OIDC: "60", JIT: "40", forged: "1" },
    { expectedVersion: "1" },
  ];
  for (const invalid of invalidForms) {
    assert.throws(() => parseWeightForm(new URLSearchParams(invalid)), InvalidWeightForm);
  }
  const duplicate = new URLSearchParams(valid);
  duplicate.append("OIDC", "60");
  assert.throws(() => parseWeightForm(duplicate), InvalidWeightForm);
});

test("sensitivity form requires two complete, matching and explicit weight sets", () => {
  const valid = new URLSearchParams({ expectedVersion: "7", baseline_OIDC: "60", baseline_JIT: "40",
    alternative_OIDC: "20", alternative_JIT: "80" });
  assert.deepEqual(parseSensitivityForm(valid), {
    expectedVersion: 7, baselineWeights: { OIDC: 60, JIT: 40 },
    alternativeWeights: { OIDC: 20, JIT: 80 },
  });
  const invalidForms: Record<string, string>[] = [
    { expectedVersion: "7", baseline_OIDC: "100", alternative_JIT: "100" },
    { expectedVersion: "7", baseline_OIDC: "60", baseline_JIT: "40", alternative_OIDC: "100" },
    { expectedVersion: "7", baseline_OIDC: "60", baseline_JIT: "40",
      alternative_OIDC: "20", alternative_JIT: "79" },
    { expectedVersion: "7", baseline_OIDC: "60", baseline_JIT: "40",
      alternative_OIDC: "0", alternative_JIT: "100" },
    { expectedVersion: "7", baseline_OIDC: "60", baseline_JIT: "40",
      alternative_OIDC: "20", alternative_JIT: "80", rankingPerformed: "true" },
  ];
  for (const values of invalidForms) {
    assert.throws(() => parseSensitivityForm(new URLSearchParams(values)), InvalidWeightForm);
  }
  for (const duplicate of ["expectedVersion", "baseline_OIDC", "alternative_JIT"]) {
    const params = new URLSearchParams(valid);
    params.append(duplicate, params.get(duplicate)!);
    assert.throws(() => parseSensitivityForm(params), InvalidWeightForm);
  }
});
