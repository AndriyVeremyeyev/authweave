// Shared by the real route handlers and request-level tests. All dependencies are server-owned.
import { sameOriginMutation } from "../auth/config.ts";
import type { AuthConfiguration } from "../auth/config.ts";
import type { BrowserSession } from "../auth/store.ts";
import type { BootstrapPreparationResult, BootstrapRecordResult, BootstrapReadResult } from "../auth/core-client.ts";
import { BOOTSTRAP_BODY_BYTES, boundedBootstrapJson, bootstrapReviewInput, type BootstrapReviewInput } from "./bootstrap-review.ts";

export type BootstrapDependencies = {
  configuration(): AuthConfiguration;
  session(request: Request, config: AuthConfiguration): Promise<BrowserSession | null>;
  prepare(session: BrowserSession, config: AuthConfiguration, candidate: unknown): Promise<BootstrapPreparationResult>;
  record(session: BrowserSession, config: AuthConfiguration, input: BootstrapReviewInput): Promise<BootstrapRecordResult>;
  read(session: BrowserSession, config: AuthConfiguration, id: unknown, digest: unknown): Promise<BootstrapReadResult>;
};
const headers = { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" };
function json(value: unknown, status = 200): Response {
  const body = JSON.stringify(value);
  // Repeated option scope can expand a small draft: bound the final browser response as well as Core bytes.
  if (new TextEncoder().encode(body).length > BOOTSTRAP_BODY_BYTES) return new Response(null, { status: 413, headers });
  return new Response(body, { status, headers: { ...headers, "Content-Type": "application/json" } });
}
function failure(kind: string): Response {
  const status = kind === "invalid" ? 400 : kind === "not-found" ? 404 : kind === "conflict" ? 409 :
    ["not-granted", "reauth-required", "core-rejected"].includes(kind) ? 403 : 503;
  return Response.json({ kind }, { status, headers });
}
export async function bootstrapMutation(request: Request, mode: "prepare" | "record", dependencies: BootstrapDependencies): Promise<Response> {
  try {
    const config = dependencies.configuration();
    if (!sameOriginMutation(request.url, request.headers.get("origin"), config.origin)) return new Response(null, { status: 403, headers });
    const session = await dependencies.session(request, config);
    if (!session) return new Response(null, { status: 401, headers });
    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
      return new Response(null, { status: 415, headers });
    }
    let body;
    try { body = await boundedBootstrapJson(request); }
    catch (error) { return new Response(null, { status: error instanceof RangeError ? 413 : 400, headers }); }
    if (mode === "prepare") {
      const result = await dependencies.prepare(session, config, body);
      return result.kind === "ready" ? json(result.preparation) : failure(result.kind);
    }
    const input = bootstrapReviewInput(body);
    if (!input) return failure("invalid");
    const result = await dependencies.record(session, config, input);
    return result.kind === "recorded" ? json(result.receipt, result.created ? 201 : 200) : failure(result.kind);
  } catch { return failure("core-unavailable"); }
}
export async function bootstrapRead(request: Request, id: string, dependencies: BootstrapDependencies): Promise<Response> {
  try {
    const config = dependencies.configuration();
    const session = await dependencies.session(request, config);
    if (!session) return new Response(null, { status: 401, headers });
    const query = new URL(request.url).searchParams;
    if (query.getAll("expectedSha256").length !== 1 || [...query.keys()].some(key => key !== "expectedSha256")) return failure("invalid");
    const result = await dependencies.read(session, config, id, query.get("expectedSha256"));
    return result.kind === "ready" ? json(result.receipt) : failure(result.kind);
  } catch { return failure("core-unavailable"); }
}
