import { type NextRequest } from "next/server.js";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { recordCatalogFactReview } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";
import { factReviewInput } from "../../../../../lib/catalog/fact-review.ts";

export const runtime = "nodejs";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function noStore(status: number): Response {
  return new Response(null, { status, headers: { "Cache-Control": "no-store" } });
}

export async function POST(request: NextRequest,
  context: RouteContext<"/api/catalog-change-proposals/[id]/fact-reviews">): Promise<Response> {
  try {
    const { id } = await context.params;
    if (!UUID.test(id)) return noStore(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return noStore(403);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return noStore(401);
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") return noStore(415);
    const length = request.headers.get("content-length");
    if (length && (!/^[0-9]+$/.test(length) || Number(length) > 2048)) return noStore(413);
    // Bound actual streamed bytes too; chunked requests cannot bypass the size limit.
    const reader = request.body?.getReader();
    if (!reader) return noStore(400);
    const chunks: Uint8Array[] = [];
    let size = 0;
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 2048) { await reader.cancel(); return noStore(413); }
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    let input;
    try { input = factReviewInput(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes))); }
    catch { return noStore(400); }
    if (!input) return noStore(400);
    const result = await recordCatalogFactReview(session, config, id, input);
    if (result.kind === "recorded") return Response.json(result.review,
      { status: result.created ? 201 : 200, headers: { "Cache-Control": "no-store" } });
    if (result.kind === "not-found") return noStore(404);
    if (result.kind === "conflict") return noStore(409);
    if (result.kind === "invalid") return noStore(400);
    if (result.kind === "not-configured" || result.kind === "core-unavailable") return noStore(503);
    return noStore(403);
  } catch { return noStore(503); }
}
