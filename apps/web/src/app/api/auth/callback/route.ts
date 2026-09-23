import { type NextRequest, NextResponse } from "next/server";

import { authConfiguration, sameOriginRequest } from "@/lib/auth/config";
import { samePrincipalReauthentication } from "@/lib/auth/curator";
import { provisionPersonalWorkspace } from "@/lib/auth/core-client";
import { identityFromCallback } from "@/lib/auth/oidc";
import { consumeLogin, createSession, touchSession } from "@/lib/auth/store";
import {
  ABSOLUTE_SESSION_SECONDS, cookieOptions, loginCookieName, sessionCookieName,
  validOpaqueValue,
} from "@/lib/auth/session-policy";

export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  let secure = false;
  try {
    const config = authConfiguration();
    secure = config.secureCookies;
    if (!sameOriginRequest(request.url, config.origin)) {
      return failure(400, secure);
    }
    const url = new URL(request.url);
    const states = url.searchParams.getAll("state");
    const binding = request.cookies.get(loginCookieName(secure))?.value;
    const currentSessionId = request.cookies.get(sessionCookieName(secure))?.value;
    if (states.length !== 1 || !validOpaqueValue(states[0]) || !validOpaqueValue(binding)) {
      return failure(400, secure);
    }
    const transaction = await consumeLogin(states[0], binding, undefined, currentSessionId);
    if (!transaction || url.searchParams.has("error") || url.searchParams.getAll("code").length !== 1) {
      return failure(400, secure);
    }
    const identity = await identityFromCallback(config, url, states[0], transaction.nonce,
      transaction.codeVerifier, transaction.purpose === "REAUTH");
    let workspaceId: string;
    if (transaction.purpose === "REAUTH") {
      const existing = await touchSession(currentSessionId);
      if (!existing || !samePrincipalReauthentication(existing, identity, transaction.startedAt)) {
        return failure(403, secure);
      }
      workspaceId = existing.workspaceId;
    } else {
      try {
        workspaceId = await provisionPersonalWorkspace(identity);
      } catch {
        return failure(503, secure);
      }
    }
    const id = await createSession({ ...identity, workspaceId },
      currentSessionId, undefined, transaction.purpose === "REAUTH");
    const response = NextResponse.redirect(new URL("/account", config.origin), { status: 303 });
    response.cookies.set(loginCookieName(secure), "", cookieOptions(secure, 0));
    response.cookies.set(sessionCookieName(secure), id,
      cookieOptions(secure, ABSOLUTE_SESSION_SECONDS));
    response.headers.set("Cache-Control", "no-store");
    return response;
  } catch {
    return failure(400, secure);
  }
}

function failure(status: number, secure: boolean) {
  const response = new NextResponse("Sign-in could not be completed. Please try again.", {
    status, headers: { "Cache-Control": "no-store" },
  });
  response.cookies.set(loginCookieName(secure), "", cookieOptions(secure, 0));
  return response;
}
