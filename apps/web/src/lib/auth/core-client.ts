// This local-only server-to-server call never exposes its credential to the browser.
import type { BrowserSession } from "./store.ts";

const CORE_ORIGIN = "http://127.0.0.1:8080";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export type PersonalAssessment = {
  id: string;
  status: "DRAFT" | "READY_FOR_EVALUATION" | "EVALUATED" | "DECIDED" | "ARCHIVED";
  version: number;
  profile: Record<string, unknown>;
};

function serviceToken(): string {
  const token = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  if (!token || token.length < 32) {
    throw new Error("Core service credential is not configured");
  }
  return token;
}

export async function provisionPersonalWorkspace(
  identity: Omit<BrowserSession, "workspaceId">,
): Promise<string> {
  const response = await fetch(`${CORE_ORIGIN}/internal/v1/personal-workspaces`, {
    method: "POST",
    headers: { Authorization: `Bearer ${serviceToken()}`, "Content-Type": "application/json" },
    body: JSON.stringify({ issuer: identity.issuer, subject: identity.subject }),
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (!response.ok) throw new Error("Core workspace provisioning failed");
  const body: unknown = await response.json();
  if (!body || typeof body !== "object" || !("workspaceId" in body) ||
      typeof body.workspaceId !== "string" ||
      !UUID.test(body.workspaceId)) {
    throw new Error("Core workspace response is invalid");
  }
  return body.workspaceId;
}

function assessmentHeaders(session: BrowserSession): Record<string, string> {
  if (!UUID.test(session.workspaceId) || !session.issuer || !session.subject) {
    throw new Error("Personal workspace session is invalid");
  }
  return {
    Authorization: `Bearer ${serviceToken()}`,
    "X-AuthWeave-Oidc-Issuer": session.issuer,
    "X-AuthWeave-Oidc-Subject": session.subject,
  };
}

function assessmentFromCore(value: unknown, session: BrowserSession, id?: string): PersonalAssessment {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Core assessment response is invalid");
  }
  const body = value as Record<string, unknown>;
  if (typeof body.id !== "string" || !UUID.test(body.id) || (id && body.id !== id) ||
      body.workspaceId !== session.workspaceId ||
      !["DRAFT", "READY_FOR_EVALUATION", "EVALUATED", "DECIDED", "ARCHIVED"].includes(String(body.status)) ||
      !Number.isSafeInteger(body.version) || Number(body.version) < 0 || body.profileSchemaVersion !== 5 ||
      !body.profile || typeof body.profile !== "object" || Array.isArray(body.profile)) {
    throw new Error("Core assessment response is invalid");
  }
  return {
    id: body.id,
    status: body.status as PersonalAssessment["status"],
    version: body.version as number,
    profile: body.profile as Record<string, unknown>,
  };
}

export async function createPersonalAssessment(session: BrowserSession): Promise<PersonalAssessment> {
  const headers = assessmentHeaders(session);
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments`, {
    method: "POST", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 201) throw new Error("Core assessment creation failed");
  return assessmentFromCore(await response.json(), session);
}

export async function readPersonalAssessment(session: BrowserSession, id: string): Promise<PersonalAssessment | null> {
  if (!UUID.test(id)) throw new Error("Assessment ID is invalid");
  const headers = assessmentHeaders(session);
  const response = await fetch(`${CORE_ORIGIN}/api/v5/workspaces/${session.workspaceId}/assessments/${id}`, {
    method: "GET", headers, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000),
  });
  if (response.status === 404) return null;
  if (response.status !== 200) throw new Error("Core assessment read failed");
  return assessmentFromCore(await response.json(), session, id);
}
