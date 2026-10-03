import type { NextRequest } from "next/server.js";
import { boundedPrerequisiteText } from "../../../../../lib/assessment/architecture-prerequisites.ts";
import { InvalidRequirementsBriefRequest, parseRequirementsBriefForm, requirementsBriefFilename,
  savedRequirementsMarkdown } from "../../../../../lib/assessment/requirements-brief.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { readPersonalAssessment } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const noStore = (status: number) => new Response(null, { status, headers: { "Cache-Control": "no-store" } });

// POST requires explicit same-origin intent but performs only one Core GET; no export is stored.
export async function POST(request: NextRequest,
  context: RouteContext<"/api/assessments/[id]/requirements-brief">): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!UUID.test(id)) return noStore(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return noStore(403);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return noStore(401);
    if (new URL(request.url).search) return noStore(400);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !==
        "application/x-www-form-urlencoded") return noStore(415);
    let body: string;
    try { body = await boundedPrerequisiteText(request, 256); }
    catch (error) {
      if (error instanceof RangeError) return noStore(413);
      throw new InvalidRequirementsBriefRequest();
    }
    const version = parseRequirementsBriefForm(new URLSearchParams(body));
    const assessment = await readPersonalAssessment(session, id);
    if (!assessment) return noStore(404);
    if (assessment.version !== version) return noStore(409);
    return new Response(savedRequirementsMarkdown(assessment), { headers: {
      "Content-Type": "text/markdown; charset=utf-8",
      "Content-Disposition": `attachment; filename="${requirementsBriefFilename(id, version)}"`,
      "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
    } });
  } catch (error) {
    return noStore(error instanceof InvalidRequirementsBriefRequest ? 400 : 503);
  }
}
