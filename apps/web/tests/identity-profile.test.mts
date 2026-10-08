import assert from "node:assert/strict";
import { test } from "node:test";
import { identityProfile } from "../src/lib/auth/identity-profile.ts";

const sub = "synthetic-profile-subject";
const userInfo = { sub, name: "Alice Example", email: "alice@example.invalid", email_verified: true };

test("subject-validated UserInfo supplies profile omitted from the ID Token", () => {
  assert.deepEqual(identityProfile({ sub }, userInfo), { displayName: "Alice Example", email: "alice@example.invalid" });
});

test("existing ID Token profile takes precedence without mixing email verification", () => {
  assert.deepEqual(identityProfile({ sub, name: "ID Token name", email: "id@example.invalid", email_verified: true }, userInfo),
    { displayName: "ID Token name", email: "id@example.invalid" });
  assert.deepEqual(identityProfile({ sub, email: "id@example.invalid", email_verified: false }, userInfo),
    { displayName: "Alice Example", email: null });
});

for (const [index, unbound] of [null, [], "invalid", {}, { ...userInfo, sub: "another-subject" }].entries())
  test(`unbound UserInfo cannot supply display/profile claims: case ${index}`, () => {
    assert.deepEqual(identityProfile({ sub }, unbound), { displayName: null, email: null });
  });

test("UserInfo email requires literal verified true and string claims", () => {
  for (const email_verified of [false, "true", 1, undefined]) assert.equal(identityProfile({ sub }, { ...userInfo, email_verified }).email, null);
  assert.deepEqual(identityProfile({ sub }, { sub, name: 42, email: 42, email_verified: true }), { displayName: null, email: null });
});

test("failed UserInfo does not remove the validated ID Token profile", () => {
  assert.deepEqual(identityProfile({ sub, name: "Bob Example", email: "bob@example.invalid", email_verified: true }, null),
    { displayName: "Bob Example", email: "bob@example.invalid" });
});
