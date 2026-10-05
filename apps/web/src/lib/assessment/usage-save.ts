import { boundedPrerequisiteText } from "./architecture-prerequisites.ts";
import { parseUsagePlanningForm } from "./usage-planning.ts";

export type UsageWriteOutcome = "saved" | "conflict" | "invalid" | "locked";
export type UsageSaveResult = UsageWriteOutcome | "signed-out" | "forbidden" | "not-found" | "uncertain";
const statuses = { saved: 200, conflict: 409, invalid: 422, locked: 423 } as const;
const actionPattern = /^\/api\/assessments\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/usage-planning$/;

export function usageReloadPath(action: string): string | null {
  const match = actionPattern.exec(action);
  return match ? `/assessments/${match[1]}?step=usage` : null;
}

// An opt-in BFF acknowledgement, not a current-profile response or a merge instruction.
export function usageWriteResponse(assessmentId: string, expectedVersion: number, outcome: UsageWriteOutcome): Response {
  return Response.json({ assessmentId, expectedVersion, outcome }, { status: statuses[outcome],
    headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer", Vary: "Accept" } });
}

export async function postUsagePlanning(action: string, params: URLSearchParams,
  fetcher: typeof fetch = fetch): Promise<UsageSaveResult> {
  const match = actionPattern.exec(action);
  if (!match) return "invalid";
  let expectedVersion: number;
  try { expectedVersion = parseUsagePlanningForm(params).expectedVersion; }
  catch { return "invalid"; }
  try {
    const response = await fetcher(action, { method: "POST", body: params.toString(),
      headers: { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      credentials: "same-origin", mode: "same-origin", cache: "no-store", redirect: "error",
      signal: AbortSignal.timeout(10_000) });
    if (response.redirected) return "uncertain";
    if (response.status === 401) return "signed-out";
    if (response.status === 403) return "forbidden";
    if (response.status === 404) return "not-found";
    if ([400, 413, 415].includes(response.status)) return "invalid";
    if (![200, 409, 422, 423].includes(response.status) ||
        response.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") return "uncertain";
    const value: unknown = JSON.parse(await boundedPrerequisiteText(response, 1024));
    if (!value || typeof value !== "object" || Array.isArray(value)) return "uncertain";
    const receipt = value as Record<string, unknown>;
    if (Object.keys(receipt).length !== 3 || !["assessmentId", "expectedVersion", "outcome"].every(key=>Object.hasOwn(receipt, key)) ||
        receipt.assessmentId !== match[1] || receipt.expectedVersion !== expectedVersion ||
        typeof receipt.outcome !== "string" || !Object.hasOwn(statuses, receipt.outcome) ||
        statuses[receipt.outcome as UsageWriteOutcome] !== response.status) return "uncertain";
    return receipt.outcome as UsageWriteOutcome;
  } catch {
    // A timeout or lost reply cannot establish whether the server committed the write.
    return "uncertain";
  }
}

export const usageSaveFeedback = {
  conflict: { title: "This assessment changed in another tab or process", text: "This submission was not saved. Your edits remain here against an older saved version. Copy any changes you need before loading the current version; there is no automatic merge or overwrite." },
  invalid: { title: "These usage inputs were rejected", text: "Your edits remain in this form. Review the inputs before trying again; no automatic correction or retry is performed." },
  locked: { title: "This assessment is no longer editable", text: "Only drafts can be edited. Your inputs remain here; copy anything you need before loading the current saved version." },
  "signed-out": { title: "Your session needs attention", text: "Your inputs remain here. Keep this tab open, sign in again in another tab, then load and review the current saved version before trying to save." },
  forbidden: { title: "This save request was not allowed", text: "Your inputs remain here. Check your account and access before loading the current saved version. This message does not grant access." },
  "not-found": { title: "This assessment is unavailable to this account", text: "Your inputs remain here. Copy anything you need before checking your account or loading the current saved version." },
  uncertain: { title: "We could not confirm this save", text: "The server may have saved the request even though its reply was lost or unreadable. Your inputs remain here. Do not resubmit blindly; load and review the current saved version first." },
} as const;
