import { architectureSettingDefinitions, architectureConfigurationDeferred, type ArchitectureConfigurationInput,
  type ArchitectureConfigurationContext } from "../../src/lib/assessment/architecture-configuration.ts";
import type { ArchitecturePatternId } from "../../src/lib/assessment/architecture-prerequisites.ts";
import { prerequisiteAssessmentId, prerequisiteWorkspaceId, prerequisiteFixture } from "./architecture-prerequisites.mts";

// Independent expected designs; these fixtures do not run the production evaluator or prove IdP behavior.
export const configurationMatching = {
  BFF_SESSION: { OAUTH_FLOW: "AUTHORIZATION_CODE", OAUTH_CLIENT_TYPE: "CONFIDENTIAL", CLIENT_AUTHENTICATION: "SERVER_HELD_CREDENTIAL", TOKEN_LOCATION: "APPLICATION_SERVER",
    PKCE_METHOD: "S256", REDIRECT_MATCHING: "EXACT_REGISTERED", SESSION_COOKIE_SECURE: "ENABLED", SESSION_COOKIE_HTTP_ONLY: "ENABLED", SESSION_CSRF_DEFENSE: "DEFENSE_PLANNED", RESOURCE_ACCESS: "BFF_PROXY" },
  SERVER_SIDE_SESSION: { OAUTH_FLOW: "AUTHORIZATION_CODE", OAUTH_CLIENT_TYPE: "CONFIDENTIAL", CLIENT_AUTHENTICATION: "SERVER_HELD_CREDENTIAL", TOKEN_LOCATION: "APPLICATION_SERVER",
    PKCE_METHOD: "S256", REDIRECT_MATCHING: "EXACT_REGISTERED", SESSION_COOKIE_SECURE: "ENABLED", SESSION_COOKIE_HTTP_ONLY: "ENABLED", SESSION_CSRF_DEFENSE: "DEFENSE_PLANNED", RESOURCE_ACCESS: "SESSION_BACKEND" },
  SPA_CODE_PKCE: { OAUTH_FLOW: "AUTHORIZATION_CODE", OAUTH_CLIENT_TYPE: "PUBLIC", CLIENT_AUTHENTICATION: "NONE", TOKEN_LOCATION: "BROWSER", PKCE_METHOD: "S256", REDIRECT_MATCHING: "EXACT_REGISTERED", RESOURCE_ACCESS: "DIRECT_BROWSER", BROWSER_TOKEN_ENDPOINT_ACCESS: "REQUIRED_ORIGINS_PLANNED" },
  NATIVE_CODE_PKCE: { OAUTH_FLOW: "AUTHORIZATION_CODE", OAUTH_CLIENT_TYPE: "PUBLIC", CLIENT_AUTHENTICATION: "NONE", TOKEN_LOCATION: "NATIVE_APP", PKCE_METHOD: "S256", REDIRECT_MATCHING: "EXACT_REGISTERED", NATIVE_USER_AGENT: "EXTERNAL_BROWSER" },
  M2M_CLIENT_CREDENTIALS: { OAUTH_FLOW: "CLIENT_CREDENTIALS", OAUTH_CLIENT_TYPE: "CONFIDENTIAL", CLIENT_AUTHENTICATION: "WORKLOAD_HELD_CREDENTIAL", TOKEN_LOCATION: "WORKLOAD", WORKLOAD_AUTHORIZATION: "WORKLOAD_OWN_OR_PREARRANGED" },
} as const;
export const configurationInput: ArchitectureConfigurationInput = { expectedVersion: 2, patternId: "BFF_SESSION", settings: {} };
export const configurationContext: ArchitectureConfigurationContext = { clients: ["BROWSER"], browserTokenExposureMinimization: "REQUIRED" };
export const configurationBinding = { workspaceId: prerequisiteWorkspaceId, assessmentId: prerequisiteAssessmentId, input: configurationInput, context: configurationContext };
export function configurationFixture(input = configurationInput, context = configurationContext) {
  const preflight = prerequisiteFixture({ expectedVersion: input.expectedVersion, patternId: input.patternId, declarations: {} }, context.clients).preflight;
  preflight.selectedClients = [...context.clients].sort();
  preflight.browserTokenExposureRequirement = context.browserTokenExposureMinimization;
  for (const pattern of preflight.patterns) {
    const selected = context.clients.includes(pattern.clientType as ArchitectureConfigurationContext["clients"][number]);
    if (context.clients.length && selected && pattern.clientType === "BROWSER") {
      const reason = context.browserTokenExposureMinimization === "REQUIRED" ? pattern.tokenHandling === "BROWSER" ? "ACCEPTABLE_EXPOSURE_UNDEFINED" : "TOKENS_HELD_SERVER_SIDE"
        : context.browserTokenExposureMinimization === "PREFERRED" ? "PREFERENCE_NOT_SCORED" : context.browserTokenExposureMinimization === "NOT_REQUIRED" ? "NO_REQUIREMENT"
          : context.browserTokenExposureMinimization === "FORBIDDEN" ? "MINIMIZATION_PROHIBITION_UNDEFINED" : "REQUIREMENT_UNKNOWN";
      pattern.checks[1].reasonCode = reason; pattern.checks[1].outcome = reason === "TOKENS_HELD_SERVER_SIDE" ? "PASS"
        : ["NO_REQUIREMENT", "PREFERENCE_NOT_SCORED"].includes(reason) ? "NOT_APPLIED" : "UNKNOWN";
      pattern.status = pattern.checks[1].outcome === "UNKNOWN" ? "NEEDS_INFORMATION" : "MATCHES_CHECKED_REQUIREMENTS";
    }
  }
  const client = input.patternId === "NATIVE_CODE_PKCE" ? "NATIVE_MOBILE" : input.patternId === "M2M_CLIENT_CREDENTIALS" ? "MACHINE_TO_MACHINE" : "BROWSER";
  const scope = context.clients.length === 0 ? "UNKNOWN" : context.clients.includes(client) ? "SELECTED" : "NOT_SELECTED";
  const checks = Object.entries(configurationMatching[input.patternId]).map(([key, matching]) => {
    const settingId = key as keyof ArchitectureConfigurationInput["settings"], value = input.settings[settingId] ?? "UNKNOWN";
    const compatible = value === matching || input.patternId === "NATIVE_CODE_PKCE" && settingId === "REDIRECT_MATCHING" && value === "NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION";
    const reasonCode = scope === "UNKNOWN" ? "CLIENT_SCOPE_UNKNOWN" : scope === "NOT_SELECTED" ? "PATTERN_NOT_APPLICABLE" : value === "UNKNOWN" ? "SETTING_UNKNOWN" : compatible ? "EXPECTED_SETTING_DECLARED" : "INCOMPATIBLE_SETTING_DECLARED";
    return { settingId, reasonCode, outcome: scope === "NOT_SELECTED" ? "NOT_APPLICABLE" : scope === "UNKNOWN" || value === "UNKNOWN" ? "UNKNOWN" : compatible ? "CONDITIONALLY_SATISFIED" : "CONDITIONALLY_NOT_SATISFIED" };
  });
  return { preflight, analysis: { patternId: input.patternId, clientScope: scope, settings: { ...input.settings }, checks,
    status: scope === "NOT_SELECTED" ? "NOT_APPLICABLE" : checks.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH" : checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES",
    policyVersion: "architecture-configuration-design-1", analysisBasis: "UNVERIFIED_PROPOSED_CONFIGURATION", configurationObserved: false, configurationVerified: false,
    providerCompatibilityVerified: false, runtimeFlowVerified: false, recommendationReady: false, publicationReady: false, writesPerformed: false },
    settingDefinitions: architectureSettingDefinitions(input.patternId), deferredBoundaries: [...architectureConfigurationDeferred] };
}
export const configurationPatterns = Object.keys(configurationMatching) as ArchitecturePatternId[];
