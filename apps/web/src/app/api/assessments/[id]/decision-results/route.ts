import { type NextRequest } from "next/server.js";
import { resultUuid } from "../../../../../lib/assessment/decision-results.ts";
import { InvalidRecording, parseRecordingForm, readRecordingForm } from "../../../../../lib/assessment/decision-recording.ts";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { recordPersonalDecision } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";
const headers = { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" };
const denied = (status: number) => new Response(null, { status, headers });
export async function POST(request: NextRequest, context: RouteContext<"/api/assessments/[id]/decision-results">): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!resultUuid.test(id)) return denied(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return denied(403);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return denied(401);
    if (new URL(request.url).search || request.headers.get("accept") !== "application/json") return denied(400);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/x-www-form-urlencoded") return denied(415);
    const input = parseRecordingForm(await readRecordingForm(request));
    const saved = await recordPersonalDecision(session, id, input);
    if (typeof saved === "number") return denied(saved);
    return Response.json(saved, { status: saved.created ? 201 : 200, headers });
  } catch (error) {
    if (error instanceof InvalidRecording) return denied(400);
    // A write may have committed. No error bodies or automatic second attempt are exposed.
    return Response.json({ error: "recording-unconfirmed" }, { status: 503, headers });
  }
}
