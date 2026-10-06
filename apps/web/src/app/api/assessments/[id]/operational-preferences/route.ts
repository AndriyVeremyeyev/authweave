import { type NextRequest, NextResponse } from "next/server.js";
import { boundedPrerequisiteText } from "../../../../../lib/assessment/architecture-prerequisites.ts";
import { InvalidOperationalPreferencesForm, parseOperationalPreferencesForm } from "../../../../../lib/assessment/operational-preferences.ts";
import { profileWriteResponse } from "../../../../../lib/assessment/profile-save.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { updatePersonalOperationalPreferences } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function refusal(status: number): Response {
  return new Response(null, { status, headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" } });
}
function returnToAssessment(origin: URL, id: string, error?: string): NextResponse {
  const destination = new URL(`/assessments/${id}`, origin);
  destination.searchParams.set("step", "usage");
  if (error) destination.searchParams.set("operationsError", error);
  const response = NextResponse.redirect(destination, { status: 303 });
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}
export async function POST(request: NextRequest,
  context: RouteContext<"/api/assessments/[id]/operational-preferences">): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!uuid.test(id)) return refusal(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return refusal(403);
    if (new URL(request.url).search) return refusal(400);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return refusal(401);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/x-www-form-urlencoded") return refusal(415);
    let body: string;
    try { body = await boundedPrerequisiteText(request, 1024); }
    catch (error) { if (error instanceof RangeError) return refusal(413); throw new InvalidOperationalPreferencesForm(); }
    const { expectedVersion, values } = parseOperationalPreferencesForm(new URLSearchParams(body));
    const result = await updatePersonalOperationalPreferences(session, id, expectedVersion, values);
    if (result === "not-found") return refusal(404);
    if (request.headers.get("accept") === "application/json") return profileWriteResponse(id, expectedVersion,
      result === "not-editable" ? "locked" : result);
    return returnToAssessment(config.origin, id, result === "conflict" ? "stale" :
      result === "invalid" ? "invalid" : result === "not-editable" ? "locked" : undefined);
  } catch (error) {
    if (error instanceof InvalidOperationalPreferencesForm) return refusal(400);
    return new Response("Assessment update is temporarily unavailable.", { status: 503,
      headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" } });
  }
}
