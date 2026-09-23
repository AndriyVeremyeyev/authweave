import { type NextRequest } from "next/server.js";

import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { rejectCatalogProposal, type CatalogRejectionInput } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const reasons = new Set(["INSUFFICIENT_EVIDENCE", "INACCURATE_FACTS", "OUT_OF_SCOPE", "OTHER"]);

function noStore(status: number): Response {
  return new Response(null, { status, headers: { "Cache-Control": "no-store" } });
}

function rejectionInput(value: unknown): CatalogRejectionInput | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  if (Object.keys(body).length !== 3 || !Object.hasOwn(body, "expectedVersion") ||
      !Object.hasOwn(body, "expectedSha256") || !Object.hasOwn(body, "reasonCode") ||
      !Number.isSafeInteger(body.expectedVersion) || Number(body.expectedVersion) < 0 ||
      typeof body.expectedSha256 !== "string" || !SHA256.test(body.expectedSha256) ||
      typeof body.reasonCode !== "string" || !reasons.has(body.reasonCode)) return null;
  return body as CatalogRejectionInput;
}

export async function POST(request: NextRequest,
  context: RouteContext<"/api/catalog-change-proposals/[id]/rejection">): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!UUID.test(id)) return noStore(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return noStore(403);
    const sessionId = request.cookies.get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(sessionId);
    if (!session) return noStore(401);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
      return noStore(415);
    }
    const declaredLength = request.headers.get("content-length");
    if (declaredLength && Number(declaredLength) > 512) return noStore(413);
    const text = await request.text();
    if (text.length > 512) return noStore(413);
    let input: CatalogRejectionInput | null;
    try { input = rejectionInput(JSON.parse(text)); } catch { return noStore(400); }
    if (!input) return noStore(400);
    const result = await rejectCatalogProposal(session, config, id, input);
    if (result.kind === "rejected") {
      return Response.json(result.decision, { status: 201, headers: { "Cache-Control": "no-store" } });
    }
    if (result.kind === "not-found") return noStore(404);
    if (result.kind === "conflict") return noStore(409);
    if (result.kind === "invalid") return noStore(400);
    if (result.kind === "not-configured" || result.kind === "core-unavailable") return noStore(503);
    return noStore(403);
  } catch {
    return noStore(503);
  }
}
