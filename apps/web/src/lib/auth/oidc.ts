// Standard OIDC only: no ZITADEL management API in the login path.
import * as oidc from "openid-client";

import type { AuthConfiguration } from "./config.ts";
import type { BrowserSession } from "./store.ts";
import { ABSOLUTE_SESSION_SECONDS } from "./session-policy.ts";
import { curatorGrant } from "./curator.ts";

let cached: { key: string; promise: Promise<oidc.Configuration> } | undefined;

export async function oidcClient(config: AuthConfiguration): Promise<oidc.Configuration> {
  const key = `${config.issuer.href}|${config.clientId}|${config.curatorScope?.projectId ?? ""}`;
  if (cached?.key === key) return cached.promise;
  const promise = oidc.discovery(config.issuer, config.clientId,
    { token_endpoint_auth_method: "none" }, oidc.None(), {
      timeout: 5,
      ...(config.issuer.protocol === "http:" ? { execute: [oidc.allowInsecureRequests] } : {}),
    }).then((client) => {
      const metadata = client.serverMetadata();
      for (const endpoint of [metadata.authorization_endpoint, metadata.token_endpoint,
        metadata.jwks_uri, metadata.end_session_endpoint]) {
        if (!endpoint || new URL(endpoint).origin !== config.issuer.origin) {
          throw new Error("OIDC metadata contains an unexpected endpoint");
        }
      }
      if (config.curatorScope && (!metadata.userinfo_endpoint ||
          new URL(metadata.userinfo_endpoint).origin !== config.issuer.origin)) {
        throw new Error("OIDC userinfo endpoint is required for curator role lookup");
      }
      if (!metadata.supportsPKCE() || !metadata.response_types_supported?.includes("code")) {
        throw new Error("OIDC provider must support Authorization Code and PKCE S256");
      }
      return client;
    });
  cached = { key, promise };
  try {
    return await promise;
  } catch {
    if (cached?.promise === promise) cached = undefined;
    throw new Error("OIDC discovery is unavailable");
  }
}

export async function authorizationUrl(config: AuthConfiguration, state: string,
                                       nonce: string, codeVerifier: string): Promise<URL> {
  const client = await oidcClient(config);
  const codeChallenge = await oidc.calculatePKCECodeChallenge(codeVerifier);
  return oidc.buildAuthorizationUrl(client, {
    redirect_uri: config.callbackUrl.href,
    scope: oidcScopes(config),
    state,
    nonce,
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
    max_age: String(ABSOLUTE_SESSION_SECONDS),
  });
}

export function oidcScopes(config: AuthConfiguration): string {
  return config.curatorScope
    ? `openid profile email urn:zitadel:iam:org:project:id:${config.curatorScope.projectId}:aud urn:zitadel:iam:org:projects:roles`
    : "openid profile email";
}

export async function identityFromCallback(config: AuthConfiguration, currentUrl: URL,
                                           state: string, nonce: string,
                                           codeVerifier: string): Promise<Omit<BrowserSession, "workspaceId">> {
  const client = await oidcClient(config);
  const tokens = await oidc.authorizationCodeGrant(client, currentUrl, {
    expectedState: state,
    expectedNonce: nonce,
    pkceCodeVerifier: codeVerifier,
    maxAge: ABSOLUTE_SESSION_SECONDS,
    idTokenExpected: true,
  });
  const claims = tokens.claims();
  if (!claims || typeof claims.sub !== "string" || !claims.sub || claims.sub.length > 256 ||
      !Number.isSafeInteger(claims.auth_time) || !claims.auth_time) {
    throw new Error("OIDC ID Token is missing required identity claims");
  }
  let userInfo: unknown = null;
  if (config.curatorScope) {
    try {
      userInfo = await oidc.fetchUserInfo(client, tokens.access_token, claims.sub);
    } catch {
      // A role lookup failure cannot grant curator access or break ordinary sign-in.
      console.error("OIDC curator role lookup unavailable; curator access denied");
    }
  }
  // Tokens are deliberately discarded after validation; the browser gets no provider credential.
  return {
    issuer: config.issuer.href.replace(/\/$/, ""),
    subject: claims.sub,
    email: claims.email_verified === true && typeof claims.email === "string" ? claims.email : null,
    displayName: typeof claims.name === "string" ? claims.name : null,
    authenticatedAt: new Date(claims.auth_time * 1000),
    curatorScope: curatorGrant(userInfo, config.curatorScope),
  };
}

export async function providerLogoutUrl(config: AuthConfiguration): Promise<URL> {
  return oidc.buildEndSessionUrl(await oidcClient(config), {
    client_id: config.clientId,
    post_logout_redirect_uri: config.origin.href,
  });
}
