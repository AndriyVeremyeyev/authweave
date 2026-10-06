import { hostingPreferences, deploymentTargets, identityExpertiseLevels, budgetSensitivities,
  operationsPlanningValues, type OperationsInputs } from "./operations-planning.ts";
import type { SectionFormIssue } from "./section-form-validation.ts";

export const operationalPreferenceFields = {
  hosting: { label: "Identity hosting preference", choices: hostingPreferences,
    help: "Managed delegates platform operation subject to the service contract; self-hosted leaves more runtime, updates and recovery work with your team. A preference does not exclude the other option." },
  deploymentTarget: { label: "Application deployment target", choices: deploymentTargets,
    help: "Where the application being assessed runs. This is not the IdP's location and does not verify provider compatibility, available regions or data residency." },
  identityExpertise: { label: "Team identity expertise", choices: identityExpertiseLevels,
    help: "Your team's self-reported familiarity with identity integration and operation. This is not a test of staffing, support coverage or recovery capacity; both hosting options still need named owners." },
  budgetSensitivity: { label: "Budget sensitivity", choices: budgetSensitivities,
    help: "How strongly cost affects your decision: high means it needs closer attention, low means less emphasis. This is not a spending cap, a price estimate or a claim that an option is free." },
} as const;
export const operationalPreferenceLabels: Record<string, string> = {
  MANAGED: "Managed identity service", SELF_HOSTED: "Self-hosted identity service",
  NO_PREFERENCE: "No hosting preference", UNKNOWN: "Unknown / not recorded",
  AZURE: "Microsoft Azure", AWS: "Amazon Web Services", GOOGLE_CLOUD: "Google Cloud",
  ON_PREMISES: "On premises", MULTI_CLOUD: "Multiple clouds", UNDECIDED: "Undecided / not recorded",
  LIMITED: "Limited", MODERATE: "Moderate", ADVANCED: "Advanced", HIGH: "High", LOW: "Low",
};
export class InvalidOperationalPreferencesForm extends Error {}

export function parseOperationalPreferencesForm(params: URLSearchParams): { expectedVersion: number; values: OperationsInputs } {
  const keys = Object.keys(operationalPreferenceFields) as (keyof OperationsInputs)[];
  if ([...params.keys()].some(key => key !== "expectedVersion" && !keys.includes(key as keyof OperationsInputs))) {
    throw new InvalidOperationalPreferencesForm();
  }
  const versions = params.getAll("expectedVersion");
  if (versions.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versions[0]) ||
      !Number.isSafeInteger(Number(versions[0]))) throw new InvalidOperationalPreferencesForm();
  const values: Record<string, string> = {};
  for (const key of keys) {
    const entries = params.getAll(key);
    if (entries.length !== 1 || !(operationalPreferenceFields[key].choices as readonly string[]).includes(entries[0])) {
      throw new InvalidOperationalPreferencesForm();
    }
    values[key] = entries[0];
  }
  return { expectedVersion: Number(versions[0]), values: values as OperationsInputs };
}

export function operationalPreferencesFormIssues(params: URLSearchParams): SectionFormIssue[] {
  try { parseOperationalPreferencesForm(params); return []; } catch { /* Keep submitted values unchanged. */ }
  const issues: SectionFormIssue[] = [];
  for (const [key, field] of Object.entries(operationalPreferenceFields)) {
    const entries = params.getAll(key);
    if (entries.length !== 1 || !(field.choices as readonly string[]).includes(entries[0])) {
      issues.push({ fieldId: `operations-${key}`, message: `${field.label}: choose one listed option, including unknown or undecided when appropriate.` });
    }
  }
  return issues.length ? issues : [{ fieldId: null,
    message: "This form's saved version or fields are invalid. Nothing was sent or automatically corrected; load and review the current saved version." }];
}

/** Replace only the four selected enums in a defensive copy of the saved v6 profile. */
export function withOperationalPreferences(profile: Record<string, unknown>, values: OperationsInputs): Record<string, unknown> {
  if (!operationsPlanningValues(profile)) throw new Error("Core profile is not editable in this form");
  const parsed = parseOperationalPreferencesForm(new URLSearchParams({ expectedVersion: "0", ...values }));
  const copy = structuredClone(profile);
  Object.assign(copy.operations as Record<string, unknown>, parsed.values);
  return copy;
}

/** A save acknowledgement must preserve every profile value; object key order is immaterial. */
export function operationalPreferencesSaveMatches(expected: unknown, saved: unknown): boolean {
  if (expected === null || typeof expected !== "object") return expected === saved;
  if (!saved || typeof saved !== "object" || Array.isArray(expected) !== Array.isArray(saved)) return false;
  if (Array.isArray(expected)) return expected.length === (saved as unknown[]).length &&
    expected.every((value, index) => operationalPreferencesSaveMatches(value, (saved as unknown[])[index]));
  const left = expected as Record<string, unknown>, right = saved as Record<string, unknown>;
  return Object.keys(left).length === Object.keys(right).length && Object.keys(left).every(key =>
    Object.hasOwn(right, key) && operationalPreferencesSaveMatches(left[key], right[key]));
}
