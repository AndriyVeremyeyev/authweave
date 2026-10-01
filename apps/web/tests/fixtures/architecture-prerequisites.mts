import { prerequisiteIds, type ArchitecturePatternId, type PrerequisiteInput } from "../../src/lib/assessment/architecture-prerequisites.ts";

export const prerequisiteWorkspaceId = "70000000-0000-4000-8000-000000000001";
export const prerequisiteAssessmentId = "80000000-0000-4000-8000-000000000001";
export const prerequisiteProfile = {
  application: { type: "B2B_SAAS", clients: ["BROWSER"] },
  audience: { populations: [], tenancy: "UNKNOWN", membership: "UNKNOWN" },
  security: { browserTokenExposureMinimization: "REQUIRED", dataResidency: "UNKNOWN",
    authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN" },
    dataResidencyDetails: { allowedCountries: [], dataCategories: [] }, complianceScopeStatus: "UNKNOWN", complianceTargets: [] },
};
export const prerequisiteInput: PrerequisiteInput = { expectedVersion: 2, patternId: "BFF_SESSION",
  declarations: { BFF_BACKEND_API_PROXY: "SATISFIED", BFF_SESSION_DEFENSES: "UNKNOWN" } };

export function prerequisiteFixture(input = prerequisiteInput, clients = ["BROWSER"]) {
  const metadata = [["BFF_SESSION", "BROWSER", "SERVER_SIDE"], ["SERVER_SIDE_SESSION", "BROWSER", "SERVER_SIDE"],
    ["SPA_CODE_PKCE", "BROWSER", "BROWSER"], ["NATIVE_CODE_PKCE", "NATIVE_MOBILE", "NATIVE_APP"],
    ["M2M_CLIENT_CREDENTIALS", "MACHINE_TO_MACHINE", "WORKLOAD"]];
  const patterns = metadata.map(([patternId, clientType, tokenHandling]) => {
    const selected = clients.includes(clientType), unknown = clients.length === 0;
    const status = unknown || selected && tokenHandling === "BROWSER" ? "NEEDS_INFORMATION" :
      selected ? "MATCHES_CHECKED_REQUIREMENTS" : "NOT_APPLICABLE";
    return { patternId, clientType, tokenHandling, displayName: patternId, status,
      checks: [{ profilePath: "application.clients", outcome: unknown ? "UNKNOWN" : selected ? "PASS" : "NOT_APPLIED",
        reasonCode: unknown ? "CLIENT_CONTEXT_UNKNOWN" : selected ? "CLIENT_SELECTED" : "CLIENT_NOT_SELECTED",
        explanation: "Synthetic client check." },
      { profilePath: "security.browserTokenExposureMinimization", outcome: unknown ? "UNKNOWN" : !selected || clientType !== "BROWSER" ? "NOT_APPLIED" : tokenHandling === "BROWSER" ? "UNKNOWN" : "PASS",
        reasonCode: unknown ? "CLIENT_CONTEXT_UNKNOWN" : !selected ? "PATTERN_NOT_APPLICABLE" : clientType !== "BROWSER" ? "BROWSER_CRITERION_NOT_APPLICABLE" : tokenHandling === "BROWSER" ? "ACCEPTABLE_EXPOSURE_UNDEFINED" : "TOKENS_HELD_SERVER_SIDE",
        explanation: "Synthetic browser criterion check." }],
      advantages: ["Synthetic advantage."], tradeoffs: ["Synthetic trade-off."],
      prerequisites: prerequisiteIds[patternId as ArchitecturePatternId].map(id => `Synthetic prerequisite ${id}.`),
      references: ["https://www.rfc-editor.org/rfc/rfc8252.html"],
    };
  });
  const pattern = patterns.find(p => p.patternId === input.patternId)!;
  const scope = clients.length === 0 ? "UNKNOWN" : clients.includes(pattern.clientType) ? "SELECTED" : "NOT_SELECTED";
  const checks = prerequisiteIds[input.patternId].map(id => {
    const declaration = input.declarations[id] ?? "UNKNOWN";
    return { prerequisiteId: id,
      outcome: scope === "UNKNOWN" ? "UNKNOWN" : scope === "NOT_SELECTED" ? "NOT_APPLICABLE" :
        declaration === "SATISFIED" ? "CONDITIONALLY_SATISFIED" : declaration === "NOT_SATISFIED" ? "CONDITIONALLY_NOT_SATISFIED" : "UNKNOWN",
      reasonCode: scope === "UNKNOWN" ? "CLIENT_SCOPE_UNKNOWN" : scope === "NOT_SELECTED" ? "PATTERN_NOT_APPLICABLE" :
        declaration === "SATISFIED" ? "DECLARED_CONDITION_SATISFIED" : declaration === "NOT_SATISFIED" ? "DECLARED_CONDITION_NOT_SATISFIED" : "CONDITION_UNKNOWN" };
  });
  return { preflight: { workspaceId: prerequisiteWorkspaceId, assessmentId: prerequisiteAssessmentId,
    assessmentVersion: input.expectedVersion, policyVersion: "architecture-pattern-preflight-1",
    evaluatedAt: "2026-10-01T12:00:00Z", scope: "ARCHITECTURE_PATTERN_PREFLIGHT", recommendationReady: false,
    selectedClients: clients, browserTokenExposureRequirement: "REQUIRED",
    checkedPaths: ["application.clients", "security.browserTokenExposureMinimization"],
    deferredPaths: ["application.type", "audience", "protocols", "provisioning", "security.multiFactorAuthentication",
      "security.auditability", "security.dataResidency", "security.assurance", "security.complianceTargets", "operations"], patterns },
  declarations: { ...input.declarations }, analysis: { patternId: input.patternId, clientScope: scope, checks,
    status: scope === "NOT_SELECTED" ? "NOT_APPLICABLE" : checks.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ?
      "CONDITIONALLY_DOES_NOT_MATCH" : checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES",
    policyVersion: "architecture-prerequisites-1", analysisBasis: "UNVERIFIED_DESIGN_DECLARATIONS",
    configurationVerified: false, providerCompatibilityVerified: false, recommendationReady: false } };
}
