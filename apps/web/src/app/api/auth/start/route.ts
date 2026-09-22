import { type NextRequest, NextResponse } from "next/server";
import * as oidc from "openid-client";

import { authConfiguration, sameOriginMutation } from "@/lib/auth/config";
import { authorizationUrl } from "@/lib/auth/oidc";
import { beginLogin } from "@/lib/auth/store";
import {
  cookieOptions, loginCookieName, LOGIN_TRANSACTION_SECONDS, randomOpaqueValue,
} from "@/lib/auth/session-policy";

export const runtime = "nodejs";

export async function POST(request: NextRequest) {
  try {
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) {
      return new Response("Sign-in must start from AuthWeave.", { status: 403 });
    }
    const state = randomOpaqueValue();
    const binding = randomOpaqueValue();
    const nonce = oidc.randomNonce();
    const verifier = oidc.randomPKCECodeVerifier();
    const destination = await authorizationUrl(config, state, nonce, verifier);
    await beginLogin(state, binding, verifier, nonce);

    const response = NextResponse.redirect(destination, { status: 303 });
    response.cookies.set(loginCookieName(config.secureCookies), binding,
      cookieOptions(config.secureCookies, LOGIN_TRANSACTION_SECONDS));
    response.headers.set("Cache-Control", "no-store");
    response.headers.set("Referrer-Policy", "no-referrer");
    return response;
  } catch {
    return new Response("Sign-in is temporarily unavailable.", {
      status: 503, headers: { "Cache-Control": "no-store" },
    });
  }
}
