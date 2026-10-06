import { operationsPlanningExpectation } from "../../../../packages/contracts/tests/helpers/operations-planning-spec.mjs";
import type { OperationsPlanningValues } from "../../src/lib/assessment/operations-planning.ts";

export const operationsWorkspaceId = "70000000-0000-4000-8000-000000000001";
export const operationsAssessmentId = "80000000-0000-4000-8000-000000000001";
export const operationsAt = "2026-10-06T12:00:00Z";
export function operationsValues(): OperationsPlanningValues {
  return { inputs: { hosting: "MANAGED", deploymentTarget: "AZURE", identityExpertise: "LIMITED", budgetSensitivity: "HIGH" },
    usagePlanning: { scopeDescription: "Synthetic private planning scope", assumptions: ["Synthetic private forecast"],
      volumes: { MONTHLY_ACTIVE_USERS: { basis: "ASSUMED", value: 0 } } } };
}
export function operationsProfile(values = operationsValues()) {
  return { operations: { ...values.inputs, usagePlanning: structuredClone(values.usagePlanning) } };
}
// Independent contract fixture, not a response manufactured by the BFF's production guard.
export function operationsFixture(values = operationsValues(), version = 7) {
  return structuredClone(operationsPlanningExpectation({ workspaceId: operationsWorkspaceId, id: operationsAssessmentId,
    version, profile: operationsProfile(values) }, operationsAt));
}
