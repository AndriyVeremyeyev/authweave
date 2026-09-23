import { type NextRequest } from "next/server.js";

import { InvalidWeightForm, parseSensitivityForm } from "../../../../../lib/assessment/weights.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { previewPersonalWeightSensitivity } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function noStore(status: number): Response {
  return new Response(null, { status, headers: { "Cache-Control": "no-store" } });
}

export async function POST(
  request: NextRequest, context: RouteContext<"/api/assessments/[id]/weight-sensitivity">,
): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!UUID.test(id)) return noStore(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return noStore(403);
    const sessionId = request.cookies.get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(sessionId);
    if (!session) return noStore(401);
    const mediaType = request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
    if (mediaType !== "application/x-www-form-urlencoded") return noStore(415);
    const declaredLength = request.headers.get("content-length");
    if (declaredLength && Number(declaredLength) > 4096) return noStore(413);
    const body = await request.text();
    if (body.length > 4096) return noStore(413);
    const { expectedVersion, baselineWeights, alternativeWeights } =
      parseSensitivityForm(new URLSearchParams(body));
    const result = await previewPersonalWeightSensitivity(session, id, expectedVersion,
      baselineWeights, alternativeWeights);
    if (result.kind === "not-found") return noStore(404);
    if (result.kind === "conflict") return noStore(409);
    if (result.kind === "invalid") return noStore(400);
    return Response.json(result.preview, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (error instanceof InvalidWeightForm) return noStore(400);
    return new Response("Sensitivity preview is temporarily unavailable.", {
      status: 503, headers: { "Cache-Control": "no-store" },
    });
  }
}
