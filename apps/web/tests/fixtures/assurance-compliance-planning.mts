import { assurancePlanningExpectation } from "../../../../packages/contracts/tests/helpers/assurance-compliance-planning-spec.mjs";
import type { AssurancePlanningValues } from "../../src/lib/assessment/assurance-compliance-planning.ts";

export const assuranceWorkspaceId = "70000000-0000-4000-8000-000000000001";
export const assuranceAssessmentId = "80000000-0000-4000-8000-000000000001";
export const assuranceAt = "2026-10-06T12:00:00Z";
export function assuranceValues(): AssurancePlanningValues {
  return { clients: ["BROWSER", "MACHINE_TO_MACHINE"], populations: ["EMPLOYEES"], assuranceExpectation: "HIGH",
    controls: { multiFactorAuthentication: "REQUIRED", phishingResistance: "REQUIRED", nonExportableKeys: "UNKNOWN", stepUpAuthentication: "PREFERRED" },
    complianceScopeStatus: "TARGETS_IDENTIFIED", complianceTargets: ["SOC_2", "OTHER"] };
}
export function assuranceProfile(values = assuranceValues()) {
  const { multiFactorAuthentication, ...controls } = values.controls;
  return { application: { clients: values.clients }, audience: { populations: values.populations }, security: {
    multiFactorAuthentication, authenticationControls: controls, assurance: values.assuranceExpectation,
    complianceScopeStatus: values.complianceScopeStatus, complianceTargets: values.complianceTargets,
  }, operations: { usagePlanning: { scopeDescription: "Synthetic private scope", assumptions: ["Synthetic private forecast"] } } };
}
// A test-only independent contract expectation, never the production BFF's replay.
export function assuranceFixture(values = assuranceValues(), version = 7) {
  return structuredClone(assurancePlanningExpectation({ workspaceId: assuranceWorkspaceId, id: assuranceAssessmentId,
    version, profile: assuranceProfile(values) }, assuranceAt));
}
