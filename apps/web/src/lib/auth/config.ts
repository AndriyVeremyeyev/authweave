// Fail-closed browser and OIDC origins for the server-side BFF.

export type AuthConfiguration = {
  origin: URL;
  issuer: URL;
  clientId: string;
  callbackUrl: URL;
  secureCookies: boolean;
};

function endpoint(value: string | undefined, label: string): URL {
  if (!value) throw new Error(`${label} is not configured`);
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${label} is invalid`);
  }
  if (url.username || url.password || url.search || url.hash || !["http:", "https:"].includes(url.protocol)) {
    throw new Error(`${label} must be a clean HTTP(S) URL`);
  }
  return url;
}

export function authConfiguration(env: Record<string, string | undefined> = process.env): AuthConfiguration {
  const origin = endpoint(env.AUTHWEAVE_PUBLIC_ORIGIN ?? "http://localhost:3000", "Public origin");
  if (origin.pathname !== "/" || (origin.protocol === "http:" && origin.href !== "http://localhost:3000/")) {
    throw new Error("Public origin must be HTTPS or the exact local development origin");
  }

  const issuer = endpoint(env.AUTHWEAVE_OIDC_ISSUER, "OIDC issuer");
  if (issuer.protocol === "http:" && issuer.href !== "http://localhost:8081/") {
    throw new Error("HTTP OIDC issuer is allowed only for the exact local ZITADEL lab");
  }

  const clientId = env.AUTHWEAVE_OIDC_CLIENT_ID;
  if (!clientId || !/^[A-Za-z0-9._@~-]{3,256}$/.test(clientId)) {
    throw new Error("OIDC client ID is missing or invalid");
  }

  return {
    origin,
    issuer,
    clientId,
    callbackUrl: new URL("/api/auth/callback", origin),
    secureCookies: origin.protocol === "https:",
  };
}

export function sameOriginRequest(requestUrl: string, origin: URL): boolean {
  try {
    return new URL(requestUrl).origin === origin.origin;
  } catch {
    return false;
  }
}

export function sameOriginMutation(requestUrl: string, originHeader: string | null, origin: URL): boolean {
  return sameOriginRequest(requestUrl, origin) && originHeader === origin.origin;
}
