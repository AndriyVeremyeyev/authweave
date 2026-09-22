import { type NextRequest, NextResponse } from "next/server.js";

import { authConfiguration, sameOriginMutation } from "../../../lib/auth/config.ts";
import { createPersonalAssessment } from "../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../lib/auth/store.ts";

export const runtime = "nodejs";

export async function POST(request: NextRequest) {
  try {
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) {
      return new Response(null, { status: 403, headers: { "Cache-Control": "no-store" } });
    }
    const id = request.cookies.get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(id);
    if (!session) {
      return new Response(null, { status: 401, headers: { "Cache-Control": "no-store" } });
    }
    const assessment = await createPersonalAssessment(session);
    const response = NextResponse.redirect(new URL(`/assessments/${assessment.id}`, config.origin), { status: 303 });
    response.headers.set("Cache-Control", "no-store");
    response.headers.set("Referrer-Policy", "no-referrer");
    return response;
  } catch {
    return new Response("Assessment creation is temporarily unavailable.", {
      status: 503, headers: { "Cache-Control": "no-store" },
    });
  }
}
