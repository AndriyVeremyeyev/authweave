import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";
import config from "../next.config.ts";

const require = createRequire(import.meta.url);
const { ignoreLoggingIncomingRequests } = require("next/dist/server/dev/log-requests.js") as {
  ignoreLoggingIncomingRequests(request: { url: string }, logging: typeof config.logging): boolean;
};

test("development disables incoming URL logs without disabling other framework diagnostics", () => {
  assert.deepEqual(config.logging, { incomingRequests: false });
});

test("the pinned Next logger suppresses callback codes/state, encoded paths and all other incoming URLs", () => {
  for (const url of [
    "/api/auth/callback?code=synthetic-one-use-code&state=synthetic-state",
    "/api/auth/callback?error=access_denied&error_description=synthetic-private-detail",
    "/api/%61uth/callback?code=synthetic-one-use-code&state=synthetic-state",
    "/api/auth/start", "/api/auth/reauth", "/api/auth/logout", "/account", "/catalog/bootstrap",
  ]) assert.equal(ignoreLoggingIncomingRequests({ url }, config.logging), true);
});
