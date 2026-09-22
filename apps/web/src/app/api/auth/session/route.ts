import { type NextRequest, NextResponse } from "next/server";

import { authConfiguration, sameOriginRequest } from "@/lib/auth/config";
import { touchSession } from "@/lib/auth/store";
import { sessionCookieName } from "@/lib/auth/session-policy";

export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  try {
    const config = authConfiguration();
    if (!sameOriginRequest(request.url, config.origin)) {
      return new Response(null, { status: 400 });
    }
    const id = request.cookies.get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(id);
    return NextResponse.json(session ? {
      authenticated: true,
      subject: session.subject,
      email: session.email,
      displayName: session.displayName,
    } : { authenticated: false }, { headers: { "Cache-Control": "no-store" } });
  } catch {
    return NextResponse.json({ error: "Session is temporarily unavailable" }, {
      status: 503, headers: { "Cache-Control": "no-store" },
    });
  }
}
