import { type NextRequest } from "next/server.js";
import { resultUuid } from "../../../../../../../lib/assessment/decision-results.ts";
import { InvalidSensitivity, sensitivityForm, readSensitivityForm } from "../../../../../../../lib/assessment/decision-sensitivity.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../../../lib/auth/config.ts";
import { comparePersonalDecisionWeights } from "../../../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../../../lib/auth/store.ts";

export const runtime = "nodejs";
const headers = { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" };
const denied = (status: number) => new Response(null, { status, headers });
export async function POST(request: NextRequest, context: RouteContext<"/api/assessments/[id]/decision-results/[resultId]/sensitivity">): Promise<Response> {
  try {
    const { id, resultId } = await context.params;
    if (!resultUuid.test(id) || !resultUuid.test(resultId)) return denied(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return denied(403);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return denied(401);
    if (new URL(request.url).search || request.headers.get("accept") !== "application/json") return denied(400);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/x-www-form-urlencoded") return denied(415);
    const input = sensitivityForm(await readSensitivityForm(request), resultId);
    const comparison = await comparePersonalDecisionWeights(session, id, input);
    return typeof comparison === "number" ? denied(comparison) : Response.json(comparison, { headers });
  } catch (error) {
    if (error instanceof InvalidSensitivity) return denied(400);
    return denied(503);
  }
}
