import type { NextRequest } from "next/server.js";
import { boundedPrerequisiteText } from "../../../../../../../lib/assessment/architecture-prerequisites.ts";
import { decisionBriefFilename, decisionBriefForm, InvalidDecisionBriefRequest, savedDecisionMarkdown } from "../../../../../../../lib/assessment/decision-brief.ts";
import { resultUuid } from "../../../../../../../lib/assessment/decision-results.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../../../lib/auth/config.ts";
import { readPersonalDecisionAdvice } from "../../../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../../../lib/auth/store.ts";

export const runtime = "nodejs";
const headers = { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" };
const denied = (status: number) => new Response(null, { status, headers });

// Explicit same-origin download intent; one existing Core GET, no result/catalog writes.
export async function POST(request: NextRequest, context: RouteContext<"/api/assessments/[id]/decision-results/[resultId]/brief">): Promise<Response> {
  try {
    const { id, resultId } = await context.params;
    if (!resultUuid.test(id) || !resultUuid.test(resultId)) return denied(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return denied(403);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return denied(401);
    if (new URL(request.url).search) return denied(400);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/x-www-form-urlencoded") return denied(415);
    let body: string;
    try { body = await boundedPrerequisiteText(request, 256); }
    catch (error) { if (error instanceof RangeError) return denied(413); throw new InvalidDecisionBriefRequest(); }
    const reference = decisionBriefForm(new URLSearchParams(body), resultId);
    const advice = await readPersonalDecisionAdvice(session, id, reference);
    if (!advice) return denied(404);
    return new Response(savedDecisionMarkdown(advice, session.workspaceId, id, reference), { headers: { ...headers,
      "Content-Type": "text/markdown; charset=utf-8", "Content-Disposition": `attachment; filename="${decisionBriefFilename(id, reference)}"`,
      "X-Content-Type-Options": "nosniff",
    } });
  } catch (error) { return denied(error instanceof InvalidDecisionBriefRequest ? 400 : 503); }
}
