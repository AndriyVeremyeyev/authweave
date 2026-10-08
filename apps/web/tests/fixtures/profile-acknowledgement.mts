import { savedRequirementsFixture } from "./assessment-ui.mts";
import { profileFormFixture } from "./profile-save.mts";
import { parseEvaluationContextForm, withEvaluationContextValues } from "../../src/lib/assessment/evaluation-context.ts";
import { parseCapabilityForm, withCapabilityValues } from "../../src/lib/assessment/capabilities.ts";
import { parseAuditabilityForm, withAuditabilityValues } from "../../src/lib/assessment/auditability.ts";
import { parseUsagePlanningForm, withUsagePlanningValues } from "../../src/lib/assessment/usage-planning.ts";
import { parseOperationalPreferencesForm, withOperationalPreferences } from "../../src/lib/assessment/operational-preferences.ts";
import { updatePersonalEvaluationContext, updatePersonalCapabilities, updatePersonalAuditability,
  updatePersonalUsagePlanning, updatePersonalOperationalPreferences } from "../../src/lib/auth/core-client.ts";
import type { BrowserSession } from "../../src/lib/auth/store.ts";

export const acknowledgementSections = ["context", "capabilities", "auditability", "usage", "operations"] as const;
export function acknowledgementProfile() {
  const profile = savedRequirementsFixture();
  Object.assign(profile.application, { clients: ["BROWSER", "MACHINE_TO_MACHINE"] });
  Object.assign(profile.security, { assurance: "HIGH", complianceScopeStatus: "TARGETS_IDENTIFIED", complianceTargets: ["SOC_2", "GDPR"] });
  Object.assign(profile.operations.usagePlanning, { assumptions: ["Synthetic 😀 forecast", "Ordered second assumption"] });
  return profile;
}

// Real patchers and clients; synthetic values do not bypass route/session checks.
export const acknowledgementWriters = {
  context: { patch: (profile: Record<string, unknown>) => withEvaluationContextValues(profile, parseEvaluationContextForm(profileFormFixture("context")).values),
    save: (session: BrowserSession, id: string, version: number) => updatePersonalEvaluationContext(session, id, version, parseEvaluationContextForm(profileFormFixture("context")).values) },
  capabilities: { patch: (profile: Record<string, unknown>) => withCapabilityValues(profile, parseCapabilityForm(profileFormFixture("capabilities")).values),
    save: (session: BrowserSession, id: string, version: number) => updatePersonalCapabilities(session, id, version, parseCapabilityForm(profileFormFixture("capabilities")).values) },
  auditability: { patch: (profile: Record<string, unknown>) => withAuditabilityValues(profile, parseAuditabilityForm(profileFormFixture("auditability")).values),
    save: (session: BrowserSession, id: string, version: number) => updatePersonalAuditability(session, id, version, parseAuditabilityForm(profileFormFixture("auditability")).values) },
  usage: { patch: (profile: Record<string, unknown>) => withUsagePlanningValues(profile, parseUsagePlanningForm(profileFormFixture("usage")).values),
    save: (session: BrowserSession, id: string, version: number) => updatePersonalUsagePlanning(session, id, version, parseUsagePlanningForm(profileFormFixture("usage")).values) },
  operations: { patch: (profile: Record<string, unknown>) => withOperationalPreferences(profile, parseOperationalPreferencesForm(profileFormFixture("operations")).values),
    save: (session: BrowserSession, id: string, version: number) => updatePersonalOperationalPreferences(session, id, version, parseOperationalPreferencesForm(profileFormFixture("operations")).values) },
};

export function reverseProfileObjectsAndSets(value: unknown, path: string[] = []): unknown {
  if (Array.isArray(value)) {
    const sets = ["application.clients", "audience.populations", "security.complianceTargets",
      "security.dataResidencyDetails.allowedCountries", "security.dataResidencyDetails.dataCategories",
      "security.auditabilityRequirements.selectedCriteria"];
    return sets.includes(path.join(".")) ? [...value].reverse() : structuredClone(value);
  }
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).reverse().map(([key, item]) => [key, reverseProfileObjectsAndSets(item, [...path, key])]));
}
