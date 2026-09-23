import type { NextRequest } from "next/server";
import { NextResponse } from "next/server.js";
import * as oidc from "openid-client";

import { authConfiguration, sameOriginMutation } from "../../../../lib/auth/config.ts";
import { authorizationUrl } from "../../../../lib/auth/oidc.ts";
import { beginReauthentication, touchSession } from "../../../../lib/auth/store.ts";
import {
  cookieOptions, loginCookieName, LOGIN_TRANSACTION_SECONDS, randomOpaqueValue, sessionCookieName,
} from "../../../../lib/auth/session-policy.ts";

export const runtime = "nodejs";

export async function POST(request: NextRequest) {
  try {
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) {
      return new Response("Reauthentication must start from AuthWeave.", {
        status: 403, headers: { "Cache-Control": "no-store" },
      });
    }
    const sessionId = request.cookies.get(sessionCookieName(config.secureCookies))?.value;
    if (!sessionId || !await touchSession(sessionId)) {
      return new Response("Sign in before reauthentication.", {
        status: 401, headers: { "Cache-Control": "no-store" },
      });
    }
    const state = randomOpaqueValue();
    const binding = randomOpaqueValue();
    const nonce = oidc.randomNonce();
    const verifier = oidc.randomPKCECodeVerifier();
    const destination = await authorizationUrl(config, state, nonce, verifier, true);
    await beginReauthentication(state, binding, verifier, nonce, sessionId);

    const response = NextResponse.redirect(destination, { status: 303 });
    response.cookies.set(loginCookieName(config.secureCookies), binding,
      cookieOptions(config.secureCookies, LOGIN_TRANSACTION_SECONDS));
    response.headers.set("Cache-Control", "no-store");
    response.headers.set("Referrer-Policy", "no-referrer");
    return response;
  } catch {
    return new Response("Reauthentication is temporarily unavailable.", {
      status: 503, headers: { "Cache-Control": "no-store" },
    });
  }
}
