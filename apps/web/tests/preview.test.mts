import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { allFields, blankProfile, exampleProfile, getValue, jsonBrief, markdownBrief, openFields, setValue, structuralIssues } from "../src/lib/preview/model.ts";

test("example and empty draft satisfy the shared contract", () => {
  assert.deepEqual(structuralIssues(exampleProfile()), []);
  assert.deepEqual(structuralIssues(blankProfile()), []);
  assert.equal(openFields(blankProfile()).length, allFields.length);
  const fixture = JSON.parse(readFileSync(new URL("../../../packages/contracts/tests/fixtures/assessment-response.valid.json", import.meta.url), "utf8"));
  assert.deepEqual(exampleProfile(), fixture.profile);
});

test("every contract leaf can be edited without mutating an earlier draft", () => {
  const original = blankProfile();
  let draft = original;
  for (const field of allFields) {
    for (const option of field.options) {
      const value = field.multiple ? [option] : option;
      const before = JSON.stringify(draft);
      const next = setValue(draft, field.path, value);
      assert.equal(JSON.stringify(draft), before);
      assert.deepEqual(getValue(next, field.path), value);
      assert.deepEqual(structuralIssues(next), [], field.path);
      draft = next;
    }
  }
  assert.equal(openFields(original).length, allFields.length);
});

test("unknown values and empty selections remain explicit; no-constraint is distinct from forbidden", () => {
  let profile = exampleProfile();
  profile = setValue(profile, "protocols.socialLogin", "NOT_REQUIRED");
  assert.match(markdownBrief(profile), /Social login:\*\* Not required/);
  assert.ok(!openFields(profile).some(field => field.path === "protocols.socialLogin"));
  profile = setValue(profile, "protocols.socialLogin", "FORBIDDEN");
  assert.match(markdownBrief(profile), /Social login:\*\* Forbidden/);
  profile = setValue(profile, "operations.deploymentTarget", "UNDECIDED");
  assert.ok(openFields(profile).some(field => field.path === "operations.deploymentTarget"));
  assert.ok(openFields(profile).some(field => field.path === "security.complianceTargets"));
  assert.match(markdownBrief(profile), /not a claim|compliance verification have not been performed/);
});

test("downloads round-trip all recorded fields and retain draft limitations", () => {
  const profile = exampleProfile();
  assert.deepEqual(JSON.parse(jsonBrief(profile)), profile);
  const markdown = markdownBrief(profile);
  for (const field of allFields) assert.ok(markdown.includes(`**${field.label}:**`));
  assert.match(markdown, /Not an architecture decision or provider recommendation/);
  assert.match(markdown, /cross-field domain validation/);
  assert.match(markdown, /Data residency/);
});

test("invalid structure cannot be exported", () => {
  const malformed = exampleProfile();
  malformed.application.clients = ["BROWSER", "BROWSER"];
  assert.ok(structuralIssues(malformed).length > 0);
  assert.throws(() => jsonBrief(malformed));
  assert.throws(() => markdownBrief(malformed));
  assert.throws(() => setValue(exampleProfile(), "application.type", "made-up"));
  assert.throws(() => setValue(exampleProfile(), "application.clients", "BROWSER"));
});

test("fresh example and reset never retain edits from an earlier draft", () => {
  const edited = exampleProfile();
  edited.application.type = "OTHER";
  (edited.application.clients as string[]).push("NATIVE_MOBILE");
  assert.equal(exampleProfile().application.type, "B2B_SAAS");
  assert.deepEqual(exampleProfile().application.clients, ["BROWSER"]);
  assert.equal(blankProfile().application.type, "UNKNOWN");
  assert.deepEqual(blankProfile().application.clients, []);
});
