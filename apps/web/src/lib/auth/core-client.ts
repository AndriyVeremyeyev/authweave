// This local-only server-to-server call never exposes its credential to the browser.
import type { BrowserSession } from "./store.ts";

export async function provisionPersonalWorkspace(
  identity: Omit<BrowserSession, "workspaceId">,
): Promise<string> {
  const token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  if (!token || token.length < 32) {
    throw new Error("Core service credential is not configured");
  }
  const response = await fetch("http://127.0.0.1:8080/internal/v1/personal-workspaces", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ issuer: identity.issuer, subject: identity.subject }),
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (!response.ok) throw new Error("Core workspace provisioning failed");
  const body: unknown = await response.json();
  if (!body || typeof body !== "object" || !("workspaceId" in body) ||
      typeof body.workspaceId !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(body.workspaceId)) {
    throw new Error("Core workspace response is invalid");
  }
  return body.workspaceId;
}
