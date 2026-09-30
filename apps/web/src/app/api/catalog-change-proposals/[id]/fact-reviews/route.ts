import { type NextRequest, NextResponse } from "next/server.js";
import { authConfiguration, sameOriginMutation } from "../../../../../lib/auth/config.ts";
import { recordCatalogFactReview } from "../../../../../lib/auth/core-client.ts";
import { sessionCookieName } from "../../../../../lib/auth/session-policy.ts";
import { touchSession } from "../../../../../lib/auth/store.ts";
import { factReviewFormInput, factReviewInput, type FactReviewInput, type FactReviewReceipt } from "../../../../../lib/catalog/fact-review.ts";
import { factReviewHistoryHref } from "../../../../../lib/catalog/fact-review-history.ts";

export const runtime = "nodejs";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function noStore(status: number, message: string | null = null): Response {
  return new Response(message, { status, headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" } });
}
function returnToReview(destination: URL): Response {
  const response = NextResponse.redirect(destination, { status: 303 });
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}
function returnToObservation(origin: URL, review: FactReviewReceipt): Response {
  // Only a validated Core receipt selects the revision, cursor and observation, never a browser return URL.
  const destination = new URL(factReviewHistoryHref(review.proposalId, review.proposalVersion,
    Math.max(0, review.reviewNumber - 20)), origin);
  destination.searchParams.set("reviewResult", review.reviewId);
  return returnToReview(destination);
}
function retryObservation(id: string, input: FactReviewInput): Response {
  const escape = (value: string | number) => String(value).replace(/[&<>"']/g, character =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]!);
  const fields = Object.entries(input).filter(([name]) => name !== "confirmation")
    .map(([name, value]) => `<input type="hidden" name="${escape(name)}" value="${escape(value)}">`).join("");
  // Preserve the validated payload/key after an uncertain write; a new render must not silently mint a retry key.
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Observation outcome unconfirmed</title></head><body><main>
    <h1>Observation outcome unconfirmed</h1>
    <p>The observation may already be stored. <a href="/catalog/review/${escape(id)}">Check revision history</a> before retrying. A retry must use the original form unchanged, with the same observation ID, conclusion and revision.</p>
    <p>Fact: ${escape(input.optionId)} · ${escape(input.factPath)}. Conclusion: ${escape(input.verdict)}.</p>
    <p>Revision: ${escape(input.expectedVersion)}. SHA-256: ${escape(input.expectedSha256)}. Observation ID: ${escape(input.reviewId)}.</p>
    <p>This is a human observation only, not source verification, trust promotion, approval or publication.</p>
    <form method="post" action="/api/catalog-change-proposals/${escape(id)}/fact-reviews">${fields}
      <label><input type="checkbox" name="confirmation" value="MANUAL_SOURCE_REVIEW" required>I confirm retrying this exact manually assessed observation. If it is already recorded, the same payload and actor return the existing receipt rather than another observation.</label>
      <button type="submit">Retry this exact observation</button>
    </form></main></body></html>`, { status: 503,
    headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "Content-Type": "text/html; charset=utf-8" } });
}

export async function POST(request: NextRequest,
  context: RouteContext<"/api/catalog-change-proposals/[id]/fact-reviews">): Promise<Response> {
  const mediaType = request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
  const isForm = mediaType === "application/x-www-form-urlencoded";
  let retry: { id: string; input: FactReviewInput } | null = null;
  const unconfirmed = () => isForm && retry ? retryObservation(retry.id, retry.input) : noStore(503);
  try {
    const { id } = await context.params;
    if (!UUID.test(id)) return noStore(404);
    const config = authConfiguration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return noStore(403);
    const session = await touchSession(request.cookies.get(sessionCookieName(config.secureCookies))?.value);
    if (!session) return noStore(401);
    if (mediaType !== "application/json" && !isForm) return noStore(415);
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
    try {
      const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      input = isForm ? factReviewFormInput(text) : factReviewInput(JSON.parse(text));
    }
    catch { return noStore(400); }
    if (!input) return noStore(400);
    retry = { id, input };
    const result = await recordCatalogFactReview(session, config, id, input);
    if (result.kind === "recorded") return isForm ? returnToObservation(config.origin, result.review) :
      Response.json(result.review, { status: result.created ? 201 : 200, headers: { "Cache-Control": "no-store" } });
    if (result.kind === "not-found") return noStore(404);
    if (result.kind === "conflict") {
      if (!isForm) return noStore(409);
      const destination = new URL(`/catalog/review/${id}?reviewError=conflict#candidate-evidence`, config.origin);
      return returnToReview(destination);
    }
    if (result.kind === "invalid") return noStore(400);
    if (result.kind === "not-configured" || result.kind === "core-unavailable") return unconfirmed();
    return noStore(403, isForm ? "Curator access with a recent sign-in is required. Return to the proposal review screen and verify this account again before retrying." : null);
  } catch { return unconfirmed(); }
}
