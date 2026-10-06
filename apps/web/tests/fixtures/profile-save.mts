import { profileSaveSections, type ProfileSection } from "../../src/lib/assessment/profile-save.ts";
import { capabilityFields } from "../../src/lib/assessment/capabilities.ts";
import { usageMetrics } from "../../src/lib/assessment/usage-planning.ts";

export const profileSaveFixtureId = "4640bbac-c20f-476a-a4dc-23efad5ff14f";
export const profileSectionAction = (section: ProfileSection) =>
  `/api/assessments/${profileSaveFixtureId}/${profileSaveSections[section].route}`;

// Exact existing form payloads; no runtime defaults, accounts or persistent writes.
export function profileFormFixture(section: ProfileSection) {
  const params = new URLSearchParams({ expectedVersion: "0" });
  if (section === "context") {
    for (const key of ["applicationType", "tenancy", "membership", "dataResidency",
      "browserTokenExposureMinimization", "phishingResistance", "nonExportableKeys",
      "stepUpAuthentication", "complianceScopeStatus"]) params.set(key, "UNKNOWN");
    params.set("allowedCountries", "US, CA");
    params.append("clients", "BROWSER"); params.append("selectedPopulations", "PARTNERS");
  } else if (section === "capabilities") {
    for (const field of capabilityFields) params.set(field.capability, "UNKNOWN");
    params.set("SCIM", "REQUIRED");
  } else if (section === "auditability") {
    params.set("criticality", "PREFERRED"); params.append("selectedCriteria", "AUDIT_LOG_RETENTION");
    params.set("minimumRetentionDays", "30");
  } else if (section === "operations") {
    for (const [key, value] of Object.entries({ hosting: "UNKNOWN", deploymentTarget: "UNDECIDED",
      identityExpertise: "UNKNOWN", budgetSensitivity: "UNKNOWN" })) params.set(key, value);
  } else {
    params.set("scopeDescription", "Fictional unsaved input");
    for (let i=0; i<10; i++) params.append("assumption", "");
    for (const metric of usageMetrics) { params.set(`basis_${metric.key}`, "UNKNOWN"); params.set(`value_${metric.key}`, ""); }
    params.set("basis_MONTHLY_M2M_TOKEN_ISSUANCES", "OBSERVED"); params.set("value_MONTHLY_M2M_TOKEN_ISSUANCES", "0");
  }
  return params;
}
