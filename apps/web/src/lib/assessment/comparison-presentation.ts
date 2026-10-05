import { capabilityFields } from "./capabilities.ts";
import type { SavedInput, SavedRequirementGroup } from "./saved-requirements.ts";
import type { AssessmentStep } from "./workflow.ts";

// Display copy only: verdicts, findings, scope and order still come from Core.
export const comparisonVerdicts = {
  EXCLUDED: { label: "Excluded by a checked requirement", short: "Excluded", tone: "excluded",
    explanation: "At least one checked hard requirement is not met. Preferences cannot override that exclusion." },
  UNRESOLVED: { label: "Needs more information", short: "Needs information", tone: "unresolved",
    explanation: "The checked requirements do not establish a match. An unknown result is not proof of support or incompatibility." },
  PASSES_CHECKED_REQUIREMENTS: { label: "Passes checked requirements only", short: "Passes checked scope", tone: "partial",
    explanation: "No failure or information gap was returned within this checked scope. This is not a recommendation or a full suitability check." },
} as const;

type InputMapping = { group: string; label: string; rows: readonly string[] };
const inputMappings: Record<string, InputMapping> = {
  ...Object.fromEntries(capabilityFields.map(field => [field.path.join("."),
    { group: "capabilities", label: field.label, rows: [field.label] }])),
  "application.type": { group: "application", label: "Application type", rows: ["Application type"] },
  "application.clients": { group: "application", label: "Client types", rows: ["Client types"] },
  "audience.populations": { group: "application", label: "User populations", rows: ["User populations"] },
  "audience.tenancy": { group: "application", label: "Organization tenancy", rows: ["Organization tenancy"] },
  "audience.membership": { group: "application", label: "User membership", rows: ["User membership"] },
  "security.dataResidency": { group: "security", label: "At-rest data residency",
    rows: ["At-rest data residency", "Allowed storage countries", "Data categories"] },
  "security.authenticationControls.phishingResistance": { group: "security", label: "Phishing-resistant authentication", rows: ["Phishing-resistant authentication"] },
  "security.authenticationControls.nonExportableKeys": { group: "security", label: "Non-exportable authentication keys", rows: ["Non-exportable authentication keys"] },
  "security.authenticationControls.stepUpAuthentication": { group: "security", label: "Stronger authentication for sensitive actions", rows: ["Stronger authentication for sensitive actions"] },
  "security.complianceScopeStatus": { group: "security", label: "Compliance target scope",
    rows: ["Compliance target scope", "Compliance target labels"] },
  "security.auditability": { group: "auditability", label: "Auditability requirement",
    rows: ["Criticality of selected criteria"] },
  "security.auditabilityRequirements": { group: "auditability", label: "Auditability criteria and retention",
    rows: ["Criticality of selected criteria", "Selected logging criteria", "Minimum retention"] },
};

export type RelatedComparisonInput = { label: string; rows: SavedInput[] | null; step: AssessmentStep | null };

export function relatedComparisonInput(path: string, groups: readonly SavedRequirementGroup[]): RelatedComparisonInput | null {
  if (path === "assessment") return { label: "Assessment coverage", rows: [], step: "review" };
  // Exact allowlist, never a URL, arbitrary object traversal or prefix-based editor guess.
  if (!Object.hasOwn(inputMappings, path)) return null;
  const mapping = inputMappings[path];
  const group = groups.find(item => item.id === mapping.group);
  const rows = mapping.rows.map(label => group?.rows?.find(row => row.label === label));
  if (rows.some(row => !row)) return { label: mapping.label, rows: null, step: null };
  return { label: mapping.label, rows: rows as SavedInput[], step: group!.step };
}

const evidenceReasons = new Set(["EVIDENCE_MISSING", "EVIDENCE_UNREVIEWED", "EVIDENCE_STALE", "EVIDENCE_FROM_FUTURE"]);
export function isComparisonEvidenceGap(reason: string): boolean { return evidenceReasons.has(reason); }

const deferredLabels: Record<string, string> = {
  "security.browserTokenExposureMinimization": "Browser token exposure and architecture handling",
  "security.auditability": "Deployed logging, delivery, integrity and compliance evidence (capability checks are included above)",
  "security.assurance": "Authentication assurance requirements",
  "security.complianceTargets": "Compliance obligations and supporting evidence",
  operations: "Operations, usage, pricing and budget",
};
export function deferredComparisonLabel(path: string): string {
  return Object.hasOwn(deferredLabels, path) ? deferredLabels[path] : "Additional unchecked scope";
}
