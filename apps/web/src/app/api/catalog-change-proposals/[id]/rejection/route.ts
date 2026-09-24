import { type NextRequest, NextResponse } from "next/server.js";

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

function rejectionFormInput(text: string): CatalogRejectionInput | null {
  const form = new URLSearchParams(text);
  const names = [...form.keys()];
  if (names.length !== 4 || new Set(names).size !== 4 ||
      !["expectedVersion", "expectedSha256", "reasonCode", "confirm"].every(name => form.has(name)) ||
      form.get("confirm") !== "REJECT") return null;
  const version = form.get("expectedVersion");
  if (!version || !/^(0|[1-9][0-9]*)$/.test(version)) return null;
  return rejectionInput({ expectedVersion: Number(version), expectedSha256: form.get("expectedSha256"),
    reasonCode: form.get("reasonCode") });
}

function returnToReview(origin: URL, id: string, outcome: "rejected" | "stale"): Response {
  const destination = new URL(`/catalog/review/${id}`, origin);
  destination.searchParams.set(outcome === "rejected" ? "result" : "error", outcome);
  const response = NextResponse.redirect(destination, { status: 303 });
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
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
    const mediaType = request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
    const isForm = mediaType === "application/x-www-form-urlencoded";
    if (mediaType !== "application/json" && !isForm) return noStore(415);
    const declaredLength = request.headers.get("content-length");
    if (declaredLength && Number(declaredLength) > 512) return noStore(413);
    const text = await request.text();
    if (text.length > 512) return noStore(413);
    let input: CatalogRejectionInput | null;
    try { input = isForm ? rejectionFormInput(text) : rejectionInput(JSON.parse(text)); }
    catch { return noStore(400); }
    if (!input) return noStore(400);
    const result = await rejectCatalogProposal(session, config, id, input);
    if (result.kind === "rejected") {
      if (isForm) return returnToReview(config.origin, id, "rejected");
      return Response.json(result.decision, { status: 201, headers: { "Cache-Control": "no-store" } });
    }
    if (result.kind === "not-found") return noStore(404);
    if (result.kind === "conflict") return isForm ? returnToReview(config.origin, id, "stale") : noStore(409);
    if (result.kind === "invalid") return noStore(400);
    if (result.kind === "not-configured" || result.kind === "core-unavailable") return noStore(503);
    return noStore(403);
  } catch {
    return noStore(503);
  }
}
