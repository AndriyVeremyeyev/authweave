import { capabilityFields } from "../../src/lib/assessment/capabilities.ts";
import { usageMetrics } from "../../src/lib/assessment/usage-planning.ts";

// Synthetic editor inputs, not complete golden profiles or provider recommendations.
export const guidedScenarios = [
  {
    key: "b2b", applicationType: "B2B_SAAS", clients: ["BROWSER"], populations: ["EXTERNAL_CUSTOMERS"],
    tenancy: "MULTI_TENANT_ORGANIZATIONS", membership: "MULTIPLE_ORGANIZATIONS_PER_USER",
    tokenExposure: "REQUIRED", phishingResistance: "UNKNOWN",
    capabilities: { OIDC: "REQUIRED", SCIM: "REQUIRED", MFA: "REQUIRED", ENTERPRISE_SSO: "PREFERRED" },
    audit: ["AUTHENTICATION_FAILURE_EVENTS", "AUDIT_LOG_RETENTION"], retention: 180,
    monthlyUsers: 100, ssoConnections: null, m2mTokens: 0, peakLogins: null,
    assumption: "Synthetic forecast; no M2M clients in this scenario",
    operations: { hosting: "MANAGED", deploymentTarget: "AZURE", identityExpertise: "MODERATE", budgetSensitivity: "HIGH" },
    expected: { application: "B2B SaaS", users: "External customers", clients: "Browser", scim: "Required", sso: "Preferred" },
  },
  {
    key: "citizen", applicationType: "PUBLIC_SECTOR_PORTAL", clients: ["BROWSER"], populations: ["CITIZENS"],
    tenancy: "NO_ORGANIZATION_BOUNDARY", membership: "NOT_APPLICABLE", tokenExposure: "REQUIRED", phishingResistance: "REQUIRED",
    capabilities: { OIDC: "REQUIRED", OAUTH2_APIS: "REQUIRED", SOCIAL_LOGIN: "FORBIDDEN", ENTERPRISE_SSO: "NOT_REQUIRED",
      SCIM: "NOT_REQUIRED", GROUP_SYNC: "NOT_REQUIRED", MFA: "REQUIRED" },
    audit: ["AUTHENTICATION_SUCCESS_EVENTS", "AUTHENTICATION_FAILURE_EVENTS", "AUDIT_LOG_RETENTION"], retention: 90,
    monthlyUsers: 1000, ssoConnections: 0, m2mTokens: 0, peakLogins: 5,
    assumption: "Synthetic forecast; no enterprise customer IdP connections or M2M clients",
    operations: { hosting: "NO_PREFERENCE", deploymentTarget: "ON_PREMISES", identityExpertise: "ADVANCED", budgetSensitivity: "HIGH" },
    expected: { application: "Public-sector portal", users: "Citizens", clients: "Browser", scim: "Not required", sso: "Not required" },
  },
  {
    key: "workforce", applicationType: "INTERNAL_WORKFORCE", clients: ["BROWSER", "MACHINE_TO_MACHINE"],
    populations: ["CONTRACTORS", "EMPLOYEES"], tenancy: "SINGLE_ORGANIZATION", membership: "SINGLE_ORGANIZATION_PER_USER",
    tokenExposure: "PREFERRED", phishingResistance: "UNKNOWN",
    capabilities: { OIDC: "REQUIRED", SAML: "PREFERRED", OAUTH2_APIS: "REQUIRED", SOCIAL_LOGIN: "FORBIDDEN",
      ENTERPRISE_SSO: "REQUIRED", GROUP_SYNC: "REQUIRED", MFA: "REQUIRED" },
    audit: ["ADMINISTRATIVE_CHANGE_EVENTS", "PROVISIONING_CHANGE_EVENTS", "AUDIT_LOG_RETENTION"], retention: 30,
    monthlyUsers: 250, ssoConnections: 1, m2mTokens: 5000, peakLogins: 2,
    assumption: "Synthetic workforce IdP and workload-token forecast, not observed traffic",
    operations: { hosting: "SELF_HOSTED", deploymentTarget: "MULTI_CLOUD", identityExpertise: "ADVANCED", budgetSensitivity: "MODERATE" },
    expected: { application: "Internal workforce application", users: "Employees, Contractors", clients: "Browser, Machine to machine",
      scim: "Unknown / not recorded", sso: "Required" },
  },
] as const;

// The session-double and real-Core suites submit the same five existing form payloads.
export function guidedScenarioForms(scenario: typeof guidedScenarios[number]) {
  const context = new URLSearchParams({ expectedVersion: "0", applicationType: scenario.applicationType,
    tenancy: scenario.tenancy, membership: scenario.membership, dataResidency: "UNKNOWN", allowedCountries: "",
    browserTokenExposureMinimization: scenario.tokenExposure, phishingResistance: scenario.phishingResistance,
    nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN", complianceScopeStatus: "UNKNOWN",
    assuranceExpectation: "ELEVATED" });
  for (const client of scenario.clients) context.append("clients", client);
  for (const population of scenario.populations) context.append("selectedPopulations", population);
  const capabilities = new URLSearchParams({ expectedVersion: "1" });
  const inputs: Readonly<Record<string, string>> = scenario.capabilities;
  for (const field of capabilityFields) capabilities.set(field.capability, inputs[field.capability] ?? "UNKNOWN");
  const auditability = new URLSearchParams({ expectedVersion: "2", criticality: "REQUIRED", minimumRetentionDays: String(scenario.retention) });
  for (const criterion of scenario.audit) auditability.append("selectedCriteria", criterion);
  const operations = new URLSearchParams({ expectedVersion: "3", ...scenario.operations });
  const usage = new URLSearchParams({ expectedVersion: "4", scopeDescription: `Synthetic ${scenario.key}: first-year monthly forecast` });
  for (let index = 0; index < 10; index++) usage.append("assumption", index === 0 ? scenario.assumption : "");
  const quantities = { MONTHLY_ACTIVE_USERS: scenario.monthlyUsers, ENTERPRISE_SSO_CONNECTIONS: scenario.ssoConnections,
    MONTHLY_M2M_TOKEN_ISSUANCES: scenario.m2mTokens, PEAK_HUMAN_LOGINS_PER_SECOND: scenario.peakLogins };
  for (const metric of usageMetrics) {
    const value = quantities[metric.key];
    usage.set(`basis_${metric.key}`, value === null ? "UNKNOWN" : "ASSUMED");
    usage.set(`value_${metric.key}`, value === null ? "" : String(value));
  }
  return { context, capabilities, auditability, operations, usage };
}
