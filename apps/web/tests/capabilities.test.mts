import assert from "node:assert/strict";
import { test } from "node:test";

import { capabilityFields, capabilityValues, InvalidCapabilityForm, parseCapabilityForm,
  withCapabilityValues, type CapabilityValues } from "../src/lib/assessment/capabilities.ts";

const profile = {
  application: { type: "B2B_SAAS" },
  protocols: {
    federation: {}, oauth2ProtectedApis: "UNKNOWN", socialLogin: "UNKNOWN",
    enterpriseSingleSignOn: "UNKNOWN",
  },
  provisioning: {
    scim: "UNKNOWN", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "UNKNOWN",
  },
  security: {
    multiFactorAuthentication: "UNKNOWN", dataResidencyDetails: { allowedCountries: ["US"] },
  },
  operations: { budget: "unrecorded" },
};

const unknownValues = Object.fromEntries(capabilityFields.map(field => [field.capability, "UNKNOWN"])) as CapabilityValues;

test("capability editor reads an empty federation as unknown and preserves all other profile fields", () => {
  assert.deepEqual(capabilityValues(profile), unknownValues);
  assert.deepEqual(withCapabilityValues(profile, unknownValues), profile);
  const changed = withCapabilityValues(profile, { ...unknownValues, OIDC: "REQUIRED", SCIM: "PREFERRED" });
  assert.deepEqual(changed, {
    ...profile,
    protocols: { ...profile.protocols, federation: { OIDC: "REQUIRED" } },
    provisioning: { ...profile.provisioning, scim: "PREFERRED" },
  });
  assert.deepEqual(profile.protocols.federation, {});
  assert.deepEqual(changed.security, profile.security);
  assert.deepEqual(changed.operations, profile.operations);
});

test("capability form accepts only nine known enum choices and one canonical version", () => {
  const valid = new URLSearchParams({ expectedVersion: "12", ...unknownValues, SCIM: "PREFERRED" });
  assert.deepEqual(parseCapabilityForm(valid), {
    expectedVersion: 12, values: { ...unknownValues, SCIM: "PREFERRED" },
  });
  for (const invalid of [
    new URLSearchParams({ ...Object.fromEntries(valid), expectedVersion: "01" }),
    new URLSearchParams({ ...Object.fromEntries(valid), expectedVersion: "-1" }),
    new URLSearchParams({ ...Object.fromEntries(valid), expectedVersion: "9007199254740992" }),
    new URLSearchParams({ ...Object.fromEntries(valid), SCIM: "MAYBE" }),
    new URLSearchParams({ ...Object.fromEntries(valid), forged: "true" }),
    new URLSearchParams({ expectedVersion: "12" }),
  ]) assert.throws(() => parseCapabilityForm(invalid), InvalidCapabilityForm);
  const duplicate = new URLSearchParams(valid);
  duplicate.append("SCIM", "FORBIDDEN");
  assert.throws(() => parseCapabilityForm(duplicate), InvalidCapabilityForm);
});

test("malformed Core profile cannot be edited silently", () => {
  assert.equal(capabilityValues({ ...profile, protocols: { ...profile.protocols, federation: { OIDC: null } } }), null);
  assert.equal(capabilityValues({ ...profile, provisioning: { ...profile.provisioning, scim: "MAYBE" } }), null);
  assert.throws(() => withCapabilityValues({ ...profile, security: {} }, unknownValues), /not editable/);
});
