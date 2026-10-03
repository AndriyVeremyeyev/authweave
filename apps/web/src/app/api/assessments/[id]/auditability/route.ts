import { NextRequest, NextResponse } from "next/server.js";
import { InvalidAuditabilityForm, parseAuditabilityForm } from "../../../../../lib/assessment/auditability.ts";
import { boundedPrerequisiteText } from "../../../../../lib/assessment/architecture-prerequisites.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { updatePersonalAuditability } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const noStore = (status: number) => new Response(null, { status, headers: { "Cache-Control": "no-store" } });

export async function POST(request: NextRequest,
  context: RouteContext<"/api/assessments/[id]/auditability">): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!UUID.test(id)) return noStore(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return noStore(403);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return noStore(401);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !==
        "application/x-www-form-urlencoded") return noStore(415);
    let body: string;
    try { body = await boundedPrerequisiteText(request, 2048); }
    catch (error) {
      if (error instanceof RangeError) return noStore(413);
      throw new InvalidAuditabilityForm();
    }
    const input = parseAuditabilityForm(new URLSearchParams(body));
    const result = await updatePersonalAuditability(session, id, input.expectedVersion, input.values);
    if (result === "not-found") return noStore(404);
    const target = new URL(`/assessments/${id}`, config.origin);
    target.searchParams.set("step", "auditability");
    if (result !== "saved") target.searchParams.set("auditError",
      result === "conflict" ? "stale" : result === "not-editable" ? "locked" : "invalid");
    const response = NextResponse.redirect(target, 303);
    response.headers.set("Cache-Control", "no-store");
    response.headers.set("Referrer-Policy", "no-referrer");
    return response;
  } catch (error) {
    if (error instanceof InvalidAuditabilityForm) return noStore(400);
    return new Response("Assessment update is temporarily unavailable.",
      { status: 503, headers: { "Cache-Control": "no-store" } });
  }
}
