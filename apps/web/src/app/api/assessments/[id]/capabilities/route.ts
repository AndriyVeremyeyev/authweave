import { type NextRequest, NextResponse } from "next/server.js";

import { parseCapabilityForm, InvalidCapabilityForm } from "../../../../../lib/assessment/capabilities.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { updatePersonalCapabilities } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function noStore(status: number): Response {
  return new Response(null, { status, headers: { "Cache-Control": "no-store" } });
}

function returnToAssessment(origin: URL, id: string, error?: string): NextResponse {
  const destination = new URL(`/assessments/${id}`, origin);
  if (error) destination.searchParams.set("editError", error);
  const response = NextResponse.redirect(destination, { status: 303 });
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}

export async function POST(
  request: NextRequest, context: RouteContext<"/api/assessments/[id]/capabilities">,
): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!UUID.test(id)) return noStore(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) {
      return noStore(403);
    }
    const sessionId = request.cookies.get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(sessionId);
    if (!session) return noStore(401);
    const mediaType = request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
    if (mediaType !== "application/x-www-form-urlencoded") {
      return noStore(415);
    }
    const declaredLength = request.headers.get("content-length");
    if (declaredLength && Number(declaredLength) > 4096) return noStore(413);
    const body = await request.text();
    if (body.length > 4096) return noStore(413);
    const { expectedVersion, values } = parseCapabilityForm(new URLSearchParams(body));
    const result = await updatePersonalCapabilities(session, id, expectedVersion, values);
    if (result === "not-found") return noStore(404);
    if (result === "conflict") return returnToAssessment(config.origin, id, "stale");
    if (result === "invalid") return returnToAssessment(config.origin, id, "invalid");
    if (result === "not-editable") return returnToAssessment(config.origin, id, "locked");
    return returnToAssessment(config.origin, id);
  } catch (error) {
    if (error instanceof InvalidCapabilityForm) return noStore(400);
    return new Response("Assessment update is temporarily unavailable.", {
      status: 503, headers: { "Cache-Control": "no-store" },
    });
  }
}
