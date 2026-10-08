import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { BrowserRuntime, browserApp, completion } from "./browser-runtime.mts";

assert.equal(process.env.AUTHWEAVE_TEST_BROWSER, "synthetic-browser-core-v1", "Run make check-browser");
const issuer = "http://localhost:8081", app = browserApp;
const clientId = "synthetic-browser-client", callback = `${app}/api/auth/callback`;
const runtime = new BrowserRuntime();

function json(response: ServerResponse, status: number, value: unknown) {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  response.end(JSON.stringify(value));
}

async function body(request: IncomingMessage) {
  let text = "";
  for await (const chunk of request) {
    text += String(chunk);
    assert.ok(text.length <= 8192);
  }
  return new URLSearchParams(text);
}

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), alg: "RS256", use: "sig", kid: "synthetic-browser-key" };
type Grant = { subject: string; nonce: string; challenge: string; wrongNonce: boolean; issuedAt: number };
const codes = new Map<string, Grant>();
const authorizations = new Map<string, URLSearchParams>();
const counts = { authorizations: 0, tokens: 0, pkceValidated: 0, reauthRequests: 0, logouts: 0 };

function idToken(grant: Grant) {
  const encoded = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const header = encoded({ alg: "RS256", kid: jwk.kid, typ: "JWT" });
  const claims = encoded({ iss: issuer, aud: clientId, sub: grant.subject, iat: grant.issuedAt,
    exp: grant.issuedAt + 300, auth_time: grant.issuedAt,
    nonce: grant.wrongNonce ? "synthetic-wrong-nonce" : grant.nonce,
    name: grant.subject, email: `${grant.subject}@example.invalid`, email_verified: true });
  const input = `${header}.${claims}`;
  return `${input}.${sign("RSA-SHA256", Buffer.from(input), privateKey).toString("base64url")}`;
}

// A protocol double, not an IdP implementation: no owner accounts, grants or tokens are used.
async function oidc(request: IncomingMessage, response: ServerResponse) {
  const url = new URL(request.url!, issuer);
  if (request.method === "GET" && url.pathname === "/.well-known/openid-configuration") {
    return json(response, 200, { issuer, authorization_endpoint: `${issuer}/authorize`, token_endpoint: `${issuer}/token`,
      jwks_uri: `${issuer}/jwks`, end_session_endpoint: `${issuer}/logout`, response_types_supported: ["code"],
      grant_types_supported: ["authorization_code"], subject_types_supported: ["public"],
      id_token_signing_alg_values_supported: ["RS256"], token_endpoint_auth_methods_supported: ["none"],
      code_challenge_methods_supported: ["S256"] });
  }
  if (request.method === "GET" && url.pathname === "/jwks") return json(response, 200, { keys: [jwk] });
  if (request.method === "GET" && url.pathname === "/__counts") return json(response, 200, counts);
  if (request.method === "GET" && url.pathname === "/authorize") {
    const params = url.searchParams;
    assert.equal(params.get("client_id"), clientId);
    assert.equal(params.get("redirect_uri"), callback);
    assert.equal(params.get("response_type"), "code");
    assert.equal(params.get("scope"), "openid profile email");
    assert.equal(params.get("code_challenge_method"), "S256");
    for (const field of ["state", "nonce", "code_challenge"]) assert.match(params.get(field)!, /^[A-Za-z0-9_-]{43}$/);
    assert.ok(["0", "28800"].includes(params.get("max_age")!));
    if (params.get("max_age") === "0") { assert.equal(params.get("prompt"), "login"); counts.reauthRequests++; }
    const transaction = randomBytes(24).toString("hex");
    authorizations.set(transaction, params); counts.authorizations++;
    response.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store",
      "Content-Security-Policy": `default-src 'none'; form-action 'self' ${app}` });
    return response.end(`<!doctype html><html lang="en"><title>Synthetic OIDC provider</title><h1>Synthetic OIDC provider</h1>
      <p>Protocol test only. Not ZITADEL. Never enter real credentials.</p><form method="post" action="/authorize">
      <input type="hidden" name="transaction" value="${transaction}"><label>Test subject
      <input name="subject" required pattern="synthetic-browser-[a-z0-9-]+"></label>
      <button name="mode" value="normal">Sign in synthetic user</button>
      <button name="mode" value="wrong-nonce">Return an invalid nonce</button></form></html>`);
  }
  if (request.method === "POST" && url.pathname === "/authorize") {
    const form = await body(request), transaction = form.get("transaction")!, params = authorizations.get(transaction);
    authorizations.delete(transaction);
    assert.ok(params); assert.match(form.get("subject")!, /^synthetic-browser-[a-z0-9-]+$/);
    const code = randomBytes(32).toString("base64url");
    codes.set(code, { subject: form.get("subject")!, nonce: params.get("nonce")!, challenge: params.get("code_challenge")!,
      wrongNonce: form.get("mode") === "wrong-nonce", issuedAt: Math.floor(Date.now() / 1000) });
    const location = new URL(callback); location.searchParams.set("state", params.get("state")!); location.searchParams.set("code", code);
    response.writeHead(303, { Location: location.href, "Cache-Control": "no-store" }); return response.end();
  }
  if (request.method === "POST" && url.pathname === "/token") {
    const form = await body(request), code = form.get("code")!, grant = codes.get(code);
    codes.delete(code); // A failed exchange also consumes the code.
    if (!grant || form.get("client_id") !== clientId || form.get("redirect_uri") !== callback ||
        form.get("grant_type") !== "authorization_code" ||
        createHash("sha256").update(form.get("code_verifier") ?? "").digest("base64url") !== grant.challenge) {
      return json(response, 400, { error: "invalid_grant" });
    }
    counts.tokens++; counts.pkceValidated++;
    return json(response, 200, { access_token: "synthetic-browser-discarded-access-token", token_type: "Bearer", expires_in: 300,
      id_token: idToken(grant) });
  }
  if (request.method === "GET" && url.pathname === "/logout") {
    assert.equal(url.searchParams.get("client_id"), clientId);
    assert.equal(url.searchParams.get("post_logout_redirect_uri"), `${app}/`);
    counts.logouts++; response.writeHead(303, { Location: `${app}/`, "Cache-Control": "no-store" }); return response.end();
  }
  json(response, 404, { error: "not_found" });
}

try {
  // Check both loopback families; localhost resolution differs across macOS and Linux.
  // Busy owner services cause a failure, never termination or connection to their endpoints.
  await runtime.reservePorts([3000, 8080, 8081]);
  const provider = createServer((request, response) => {
    void oidc(request, response).catch(() => json(response, 500, { error: "synthetic_provider_assertion" }));
  });
  await runtime.listen(provider, 8081, "localhost");
  const env = await runtime.startWeb({ AUTHWEAVE_OIDC_ISSUER: issuer, AUTHWEAVE_OIDC_CLIENT_ID: clientId });
  const tests = runtime.run(["node_modules/@playwright/test/cli.js", "test"], process.cwd(), { ...env, AUTHWEAVE_TEST_BROWSER: "synthetic-browser-core-v1" });
  assert.equal(await completion(tests), 0, "Browser tests failed");
  assert.deepEqual(counts, { authorizations: 16, tokens: 16, pkceValidated: 16, reauthRequests: 4, logouts: 10 });
  console.log("Browser/OIDC E2E: 8 passed; real Core, isolated PostgreSQL, signed ID Tokens and PKCE validated.");
} finally {
  await runtime.close();
}
