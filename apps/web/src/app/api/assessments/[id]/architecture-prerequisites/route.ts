import { type NextRequest } from "next/server.js";
import { boundedPrerequisiteText, InvalidPrerequisiteForm, parsePrerequisiteForm } from "../../../../../lib/assessment/architecture-prerequisites.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { previewPersonalArchitecturePrerequisites } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const noStore = (status: number) => new Response(null, { status, headers: { "Cache-Control": "no-store" } });

export async function POST(request: NextRequest,
  context: RouteContext<"/api/assessments/[id]/architecture-prerequisites">): Promise<Response> {
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
      throw new InvalidPrerequisiteForm();
    }
    const input = parsePrerequisiteForm(new URLSearchParams(body));
    const result = await previewPersonalArchitecturePrerequisites(session, id, input);
    if (result.kind !== "preview") return noStore(result.kind === "not-found" ? 404 : result.kind === "conflict" ? 409 : 400);
    return Response.json(result.preview, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (error instanceof InvalidPrerequisiteForm) return noStore(400);
    return new Response("Architecture prerequisite preview is temporarily unavailable.",
      { status: 503, headers: { "Cache-Control": "no-store" } });
  }
}
