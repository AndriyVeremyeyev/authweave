import { type NextRequest, NextResponse } from "next/server";

import { authConfiguration, sameOriginMutation } from "@/lib/auth/config";
import { providerLogoutUrl } from "@/lib/auth/oidc";
import { revokeSession } from "@/lib/auth/store";
import { cookieOptions, sessionCookieName } from "@/lib/auth/session-policy";

export const runtime = "nodejs";

export async function POST(request: NextRequest) {
  try {
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) {
      return new Response("Logout must start from AuthWeave.", { status: 403 });
    }
    await revokeSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    let destination = config.origin;
    try {
      destination = await providerLogoutUrl(config);
    } catch {
      // The local session is already revoked. Provider logout is best effort.
    }
    const response = NextResponse.redirect(destination, { status: 303 });
    response.cookies.set(sessionCookieName(config.secureCookies), "",
      cookieOptions(config.secureCookies, 0));
    response.headers.set("Cache-Control", "no-store");
    return response;
  } catch {
    return new Response("Logout could not be confirmed.", {
      status: 503, headers: { "Cache-Control": "no-store" },
    });
  }
}
