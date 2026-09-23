import assert from "node:assert/strict";
import { test } from "node:test";

import { authConfiguration, sameOriginMutation, sameOriginRequest } from "../src/lib/auth/config.ts";
import { oidcScopes } from "../src/lib/auth/oidc.ts";
import {
  ABSOLUTE_SESSION_SECONDS, IDLE_SESSION_SECONDS, LOGIN_TRANSACTION_SECONDS,
  cookieOptions, loginCookieName, opaqueHash, randomOpaqueValue,
  sessionCookieName, sessionExpiry, validOpaqueValue,
} from "../src/lib/auth/session-policy.ts";

const local = {
  AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081",
  AUTHWEAVE_OIDC_CLIENT_ID: "123456789012345678@authweave",
};

test("local BFF config uses exact registered callback and development-only HTTP origins", () => {
  const config = authConfiguration(local);
  assert.equal(config.callbackUrl.href, "http://localhost:3000/api/auth/callback");
  assert.equal(config.secureCookies, false);
  assert.equal(config.issuer.href, "http://localhost:8081/");
  assert.equal(config.curatorScope, null);
  assert.equal(oidcScopes(config), "openid profile email");
});

test("curator scope is explicit and project/organization bound", () => {
  const config = authConfiguration({ ...local, AUTHWEAVE_OIDC_PROJECT_ID: "123456789012345678",
    AUTHWEAVE_OIDC_ORG_ID: "987654321012345678" });
  assert.deepEqual(config.curatorScope, {
    projectId: "123456789012345678", organizationId: "987654321012345678",
  });
  assert.equal(oidcScopes(config),
    "openid profile email urn:zitadel:iam:org:project:id:123456789012345678:aud " +
    "urn:zitadel:iam:org:projects:roles");
  for (const overrides of [
    { AUTHWEAVE_OIDC_PROJECT_ID: "123" },
    { AUTHWEAVE_OIDC_ORG_ID: "456" },
    { AUTHWEAVE_OIDC_PROJECT_ID: "not-an-id", AUTHWEAVE_OIDC_ORG_ID: "456" },
    { AUTHWEAVE_OIDC_PROJECT_ID: "123", AUTHWEAVE_OIDC_ORG_ID: "not-an-id" },
  ]) assert.throws(() => authConfiguration({ ...local, ...overrides }));
});

test("remote BFF config requires HTTPS and host-only secure cookies", () => {
  const config = authConfiguration({ ...local, AUTHWEAVE_PUBLIC_ORIGIN: "https://auth.example.test",
    AUTHWEAVE_OIDC_ISSUER: "https://identity.example.test" });
  assert.equal(config.secureCookies, true);
  assert.equal(config.callbackUrl.href, "https://auth.example.test/api/auth/callback");
  assert.equal(sessionCookieName(true), "__Host-authweave-session");
  assert.equal(loginCookieName(true), "__Host-authweave-login");
  assert.deepEqual(cookieOptions(true, 60), {
    httpOnly: true, secure: true, sameSite: "lax", path: "/", maxAge: 60,
  });
});

test("untrusted origin, issuer, URL credentials and malformed client IDs fail closed", () => {
  for (const overrides of [
    { AUTHWEAVE_PUBLIC_ORIGIN: "http://127.0.0.1:3000" },
    { AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3001" },
    { AUTHWEAVE_PUBLIC_ORIGIN: "https://auth.example.test/path" },
    { AUTHWEAVE_PUBLIC_ORIGIN: "https://user:pass@auth.example.test" },
    { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8082" },
    { AUTHWEAVE_OIDC_ISSUER: "http://evil.example.test" },
    { AUTHWEAVE_OIDC_ISSUER: "https://identity.example.test#fragment" },
    { AUTHWEAVE_OIDC_CLIENT_ID: "bad id" },
  ]) {
    assert.throws(() => authConfiguration({ ...local, ...overrides }));
  }
  assert.throws(() => authConfiguration({}));
});

test("state-changing requests require an exact same-origin Origin header", () => {
  const origin = authConfiguration(local).origin;
  assert.equal(sameOriginRequest("http://localhost:3000/api/auth/callback", origin), true);
  assert.equal(sameOriginRequest("http://127.0.0.1:3000/api/auth/callback", origin), false);
  assert.equal(sameOriginMutation("http://localhost:3000/api/auth/logout", "http://localhost:3000", origin), true);
  assert.equal(sameOriginMutation("http://localhost:3000/api/auth/logout", null, origin), false);
  assert.equal(sameOriginMutation("http://localhost:3000/api/auth/logout", "https://evil.test", origin), false);
});

test("opaque browser values are random and only hashed values need persistence", () => {
  const first = randomOpaqueValue();
  const second = randomOpaqueValue();
  assert.equal(validOpaqueValue(first), true);
  assert.equal(validOpaqueValue(second), true);
  assert.notEqual(first, second);
  assert.equal(opaqueHash(first).length, 32);
  assert.notDeepEqual(opaqueHash(first), opaqueHash(second));
  assert.equal(validOpaqueValue("not-an-opaque-id"), false);
  assert.throws(() => opaqueHash("invalid"));
});

test("idle lifetime is capped by the eight-hour absolute deadline", () => {
  const created = new Date("2026-09-22T00:00:00.000Z");
  assert.equal(IDLE_SESSION_SECONDS, 1800);
  assert.equal(ABSOLUTE_SESSION_SECONDS, 28800);
  assert.equal(LOGIN_TRANSACTION_SECONDS, 600);
  const initial = sessionExpiry(created, created);
  assert.equal(initial.idle.toISOString(), "2026-09-22T00:30:00.000Z");
  assert.equal(initial.absolute.toISOString(), "2026-09-22T08:00:00.000Z");
  const late = sessionExpiry(created, new Date("2026-09-22T07:50:00.000Z"));
  assert.equal(late.idle.toISOString(), late.absolute.toISOString());
});
