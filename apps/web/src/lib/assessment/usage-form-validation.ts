import { parseUsagePlanningForm, usageMetrics } from "./usage-planning.ts";

export type UsageFormIssue = { fieldId: string | null; message: string };

// Presentation only: the existing parser remains the acceptance boundary on both sides.
export function usagePlanningFormIssues(params: URLSearchParams): UsageFormIssue[] {
  const issues: UsageFormIssue[] = [];
  const scope = params.get("scopeDescription");
  if (scope !== null && scope.length > 500) {
    issues.push({ fieldId: "usage-scope", message: "Keep the planning scope within 500 characters." });
  }
  for (const metric of usageMetrics) {
    const basis = params.get(`basis_${metric.key}`);
    const value = params.get(`value_${metric.key}`);
    const fieldId = `usage-value-${metric.key}`;
    if (basis === "UNKNOWN") {
      if (value !== null && value !== "") issues.push({ fieldId,
        message: `${metric.label}: leave Value blank for Unknown, or choose Assumed / Observed to record a number.` });
    } else if (basis === "ASSUMED" || basis === "OBSERVED") {
      if (value === "") issues.push({ fieldId,
        message: `${metric.label}: enter a whole non-negative number, including zero, or choose Unknown and leave Value blank.` });
      else if (value !== null && (!/^(0|[1-9][0-9]*)$/.test(value) || !Number.isSafeInteger(Number(value)))) {
        issues.push({ fieldId, message: `${metric.label}: use a whole number from 0 to 9,007,199,254,740,991, without leading zeros, a sign, decimals or exponent notation.` });
      }
    }
  }
  const seen = new Set<string>();
  params.getAll("assumption").forEach((text, index) => {
    const fieldId = index < 10 ? `usage-assumption-${index}` : null;
    if (text.length > 500) issues.push({ fieldId, message: `Assumption ${index + 1}: keep the text within 500 characters.` });
    else if (text !== "" && text.trim() === "") issues.push({ fieldId,
      message: `Assumption ${index + 1}: enter text or clear the field; spaces alone are not an assumption.` });
    else if (text !== "" && seen.has(text)) issues.push({ fieldId,
      message: `Assumption ${index + 1}: this text is already recorded. Keep each assumption once or clear this field.` });
    if (text !== "") seen.add(text);
  });
  if (issues.length > 0) return issues;
  try {
    parseUsagePlanningForm(params);
    return [];
  } catch {
    return [{ fieldId: null, message: "These inputs could not be read safely. Reload this page and try again." }];
  }
}
