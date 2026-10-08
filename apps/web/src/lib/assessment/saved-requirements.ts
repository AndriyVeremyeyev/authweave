import { capabilityFields, capabilityValues } from "./capabilities.ts";
import { evaluationContextLabels as labels, evaluationContextValues } from "./evaluation-context.ts";
import { auditCriteria, auditabilityValues } from "./auditability.ts";
import { usageMetrics, usagePlanningValues } from "./usage-planning.ts";

export type SavedInputState = "recorded" | "not-recorded" | "needs-definition";
export type SavedInput = { label: string; value: string; state: SavedInputState };
export type SavedRequirementGroup = {
  id: string; title: string; step: "context" | "capabilities" | "auditability" | "usage";
  action: string; note: string; rows: SavedInput[] | null;
};

// Stable targets within the saved Review only, never inferred editor field IDs.
export function savedInputRowId(group: SavedRequirementGroup, index: number): string {
  return `saved-${group.id}-input-${index}`;
}

function choice(label: string, value: string): SavedInput {
  return { label, value: labels[value] ?? value,
    state: value === "UNKNOWN" ? "not-recorded" : value === "OTHER" ? "needs-definition" : "recorded" };
}

function list(label: string, values: readonly string[], translate = false): SavedInput {
  return { label, value: values.length ? values.map(value => translate ? labels[value] ?? value : value).join(", ") : "Nothing recorded",
    state: values.length ? translate && values.includes("OTHER") ? "needs-definition" : "recorded" : "not-recorded" };
}

// Display only existing editor projections. Core remains the domain/validation authority.
export function savedRequirementGroups(profile: Record<string, unknown>): SavedRequirementGroup[] {
  const context = evaluationContextValues(profile);
  const capabilities = capabilityValues(profile);
  const audit = auditabilityValues(profile);
  const usage = usagePlanningValues(profile);
  return [
    {
      id: "application", title: "Application and audience", step: "context", action: "Edit application context",
      note: "No selection is a recorded gap, not an automatic exemption. Applicability is checked separately by Core.",
      rows: context ? [
        choice("Application type", context.applicationType), list("Client types", context.clients, true),
        list("User populations", context.selectedPopulations, true), choice("Organization tenancy", context.tenancy),
        choice("User membership", context.membership),
      ] : null,
    },
    {
      id: "security", title: "Security and compliance scope", step: "context", action: "Edit security scope",
      note: "These are requested controls and target labels, not evidence of safety or compliance. Empty countries do not mean worldwide permission.",
      rows: context ? [
        choice("At-rest data residency", context.dataResidency), list("Allowed storage countries", context.allowedCountries),
        list("Data categories", context.selectedDataCategories, true),
        choice("Minimize browser OAuth token exposure", context.browserTokenExposureMinimization),
        choice("Phishing-resistant authentication", context.phishingResistance),
        choice("Non-exportable authentication keys", context.nonExportableKeys),
        choice("Stronger authentication for sensitive actions", context.stepUpAuthentication),
        choice("Compliance target scope", context.complianceScopeStatus),
        list("Compliance target labels", context.selectedComplianceTargets, true),
        ...(context.assuranceExpectation === undefined ? [] : [
          choice("Assurance expectation (planning label)", context.assuranceExpectation),
        ]),
      ] : null,
    },
    {
      id: "capabilities", title: "Identity capabilities", step: "capabilities", action: "Edit identity requirements",
      note: "Required and forbidden are hard constraints. Preferred is not a score; not required removes that constraint, not the need for safe design.",
      rows: capabilities ? capabilityFields.map(field => choice(field.label, capabilities[field.capability])) : null,
    },
    {
      id: "auditability", title: "Identity-provider auditability", step: "auditability", action: "Edit auditability requirements",
      note: "No event selection is unresolved scope, not a logging exemption. Provider logs are separate from AuthWeave's own change history.",
      rows: audit ? [
        choice("Criticality of selected criteria", audit.criticality),
        list("Selected logging criteria", auditCriteria.filter(criterion => audit.selectedCriteria.includes(criterion.key)).map(criterion => criterion.label)),
        { label: "Minimum retention", value: audit.minimumRetentionDays === null
          ? "No duration recorded; retention is not selected" : `${audit.minimumRetentionDays.toLocaleString("en-US")} days`, state: "recorded" },
      ] : null,
    },
    {
      id: "usage", title: "Usage assumptions", step: "usage", action: "Edit usage inputs",
      note: "Missing usage is not zero usage or zero cost. Assumed and observed quantities stay distinct; no provider price is calculated here.",
      rows: usage ? [
        { label: "Workload scope", value: usage.scopeDescription.trim() || "Nothing recorded",
          state: usage.scopeDescription.trim() ? "recorded" : "not-recorded" },
        ...usageMetrics.map((metric): SavedInput => {
          const quantity = usage.volumes[metric.key];
          return { label: metric.label, value: quantity
            ? `${quantity.value.toLocaleString("en-US")} · ${quantity.basis === "ASSUMED" ? "Assumed" : "Observed"}` : "Nothing recorded",
          state: quantity ? "recorded" : "not-recorded" };
        }),
        list("Recorded assumptions", usage.assumptions),
      ] : null,
    },
  ];
}
