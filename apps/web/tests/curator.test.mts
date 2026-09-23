import assert from "node:assert/strict";
import { test } from "node:test";

import { curatorGrant, freshCuratorGrant, SENSITIVE_ACTION_REAUTH_SECONDS } from
  "../src/lib/auth/curator.ts";

const scope = { projectId: "123456789012345678", organizationId: "987654321012345678" };
const exactClaim = { [`urn:zitadel:iam:org:project:${scope.projectId}:roles`]: {
  catalog_curator: { [scope.organizationId]: "authweave.localhost" },
} };

test("curator grant requires exact project, role and organization", () => {
  assert.deepEqual(curatorGrant(exactClaim, scope), scope);
  assert.equal(curatorGrant(exactClaim, null), null);
  assert.equal(curatorGrant(exactClaim, { ...scope, projectId: "111" }), null);
  assert.equal(curatorGrant(exactClaim, { ...scope, organizationId: "222" }), null);
  assert.equal(curatorGrant({ "urn:zitadel:iam:org:project:roles": exactClaim[
    `urn:zitadel:iam:org:project:${scope.projectId}:roles`] }, scope), null);
  assert.equal(curatorGrant({ roles: ["catalog_curator"], admin: true }, scope), null);
  assert.equal(curatorGrant({ [`urn:zitadel:iam:org:project:${scope.projectId}:roles`]: {
    catalog_curator: [scope.organizationId],
  } }, scope), null);
  assert.equal(curatorGrant({ [`urn:zitadel:iam:org:project:${scope.projectId}:roles`]: {
    catalog_curator: { [scope.organizationId]: "" },
  } }, scope), null);
  assert.equal(curatorGrant(Object.create(exactClaim), scope), null);
});

test("sensitive curator grant expires 15 minutes after authentication", () => {
  const now = new Date("2026-09-23T12:15:00.000Z");
  assert.equal(SENSITIVE_ACTION_REAUTH_SECONDS, 900);
  assert.equal(freshCuratorGrant(scope, scope, new Date("2026-09-23T12:00:00.000Z"), now), true);
  assert.equal(freshCuratorGrant(scope, scope, new Date("2026-09-23T11:59:59.999Z"), now), false);
  assert.equal(freshCuratorGrant(scope, scope, new Date("2026-09-23T12:15:00.001Z"), now), false);
  assert.equal(freshCuratorGrant(scope, scope, new Date("invalid"), now), false);
  assert.equal(freshCuratorGrant(null, scope, now, now), false);
  assert.equal(freshCuratorGrant(scope, { ...scope, projectId: "111" }, now, now), false);
});
