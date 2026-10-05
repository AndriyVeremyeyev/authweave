import { parseEvaluationContextForm } from "./evaluation-context.ts";
import { maximumRetentionDays, parseAuditabilityForm } from "./auditability.ts";

export type SectionFormIssue = { fieldId: string | null; message: string };
const unreadable = (): SectionFormIssue[] => [{ fieldId: null,
  message: "Check this section's selections and values. Nothing was sent or automatically corrected." }];

// Presentation only: diagnostics explain an existing parser refusal, never accept a payload.
export function evaluationContextFormIssues(params: URLSearchParams): SectionFormIssue[] {
  try { parseEvaluationContextForm(params); return []; } catch { /* Explain only known field errors. */ }
  const countries = params.getAll("allowedCountries");
  if (countries.length !== 1) return unreadable();
  const text = countries[0];
  const fieldId = "context-allowedCountries";
  if (text.length > 1024) return [{ fieldId,
    message: "Allowed storage countries: keep this field within 1,024 characters." }];
  const codes = text.trim() === "" ? [] : text.split(",").map(code => code.trim());
  const issues: SectionFormIssue[] = [];
  if (codes.length > 249) issues.push({ fieldId,
    message: "Allowed storage countries: record no more than 249 codes." });
  if (codes.some(code => !/^[A-Z]{2}$/.test(code))) issues.push({ fieldId,
    message: "Allowed storage countries: use uppercase two-letter codes separated by commas, such as US, CA. Do not leave an empty entry; clear the whole field if the countries are not recorded." });
  if (new Set(codes).size !== codes.length) issues.push({ fieldId,
    message: "Allowed storage countries: keep each code once. Repeated codes are not removed automatically." });
  return issues.length ? issues : unreadable();
}

export function auditabilityFormIssues(params: URLSearchParams): SectionFormIssue[] {
  try { parseAuditabilityForm(params); return []; } catch { /* Explain only known field errors. */ }
  const durations = params.getAll("minimumRetentionDays");
  if (durations.length > 1) return unreadable();
  const duration = durations[0];
  const retentionSelected = params.getAll("selectedCriteria").includes("AUDIT_LOG_RETENTION");
  if (retentionSelected && (duration === undefined || duration === "")) return [{ fieldId: "audit-retention-days",
    message: "Requested minimum retention: enter 1–36,500 whole days when Log retention is selected, or unselect Log retention. No duration is filled automatically." }];
  if (duration !== undefined && duration !== "") {
    if (!retentionSelected) return [{ fieldId: "audit-AUDIT_LOG_RETENTION",
      message: "A retention duration needs Log retention selected. Unselecting retention leaves the duration out of the normal form payload and clears its saved value only when you save." }];
    if (!/^[1-9][0-9]*$/.test(duration) || !Number.isSafeInteger(Number(duration)) || Number(duration) > maximumRetentionDays)
      return [{ fieldId: "audit-retention-days",
        message: "Requested minimum retention: use a whole number from 1 to 36,500 without leading zeros, spaces, a sign, decimals or exponent notation. The input is not rounded or corrected automatically." }];
  }
  return unreadable();
}
