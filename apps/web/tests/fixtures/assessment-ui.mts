import { readFile } from "node:fs/promises";
import ts from "typescript";
import type { SyntheticComparisonSummary } from "../../src/lib/auth/core-client.ts";

// Display-only fixture: no account, credentials, provider facts or persistent writes.
export function savedRequirementsFixture() {
  return {
    application: { type: "B2B_SAAS", clients: ["BROWSER"] },
    audience: { populations: ["EXTERNAL_CUSTOMERS", "PARTNERS"],
      tenancy: "MULTI_TENANT_ORGANIZATIONS", membership: "MULTIPLE_ORGANIZATIONS_PER_USER" },
    protocols: { federation: { OIDC: "REQUIRED", SAML: "PREFERRED" },
      oauth2ProtectedApis: "UNKNOWN", socialLogin: "NOT_REQUIRED", enterpriseSingleSignOn: "FORBIDDEN" },
    provisioning: { scim: "REQUIRED", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "UNKNOWN" },
    security: { multiFactorAuthentication: "REQUIRED", dataResidency: "REQUIRED",
      browserTokenExposureMinimization: "REQUIRED", complianceScopeStatus: "UNKNOWN", complianceTargets: [] as string[],
      dataResidencyDetails: { allowedCountries: ["US", "CA"], dataCategories: ["USER_PROFILES", "BACKUPS"] },
      authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "NOT_REQUIRED", stepUpAuthentication: "PREFERRED" },
      auditability: "REQUIRED", auditabilityRequirements: {
        selectedCriteria: ["AUTHENTICATION_SUCCESS_EVENTS", "AUDIT_LOG_RETENTION"], minimumRetentionDays: 30 as number | null } },
    operations: { usagePlanning: { scopeDescription: "Fictional B2B partner portal", assumptions: ["Pilot traffic only"],
      volumes: { MONTHLY_ACTIVE_USERS: { basis: "ASSUMED", value: 250 },
        MONTHLY_M2M_TOKEN_ISSUANCES: { basis: "OBSERVED", value: 0 } } } },
  };
}

// Already-projected display data; this fixture does not claim to run Core or verify facts.
export function comparisonUiFixture(): SyntheticComparisonSummary {
  return {
    assessmentVersion: 7, catalogVersion: "synthetic-ui-fixture", auditabilityEvidenceVersion: "synthetic-audit-ui-fixture",
    evaluatedAt: "2026-10-03T12:00:00Z",
    deferredPaths: ["security.browserTokenExposureMinimization", "security.auditability", "security.assurance", "security.complianceTargets", "operations"],
    candidates: [
      { optionId: "fictional-excluded", displayName: "Fictional Limited Plan", plan: "Demo", region: "Synthetic region",
        hardVerdict: "EXCLUDED", exclusionReasons: [{ dimension: "CAPABILITY", profilePath: "provisioning.scim", reasonCode: "REQUIRED_CAPABILITY_UNAVAILABLE",
          explanation: "SCIM: This fictional plan does not offer the required capability." }], informationGaps: [],
        capabilityPreferences: [{ capability: "SAML", profilePath: "protocols.federation.SAML", outcome: "AVAILABLE",
          reasonCode: "PREFERRED_CAPABILITY_AVAILABLE", explanation: "The preferred SAML capability is available; this does not reverse the SCIM exclusion." }] },
      { optionId: "fictional-unresolved", displayName: "Fictional Uncertain Plan", plan: "Demo", region: "Synthetic region",
        hardVerdict: "UNRESOLVED", exclusionReasons: [], informationGaps: [{ dimension: "CAPABILITY", profilePath: "provisioning.scim", reasonCode: "EVIDENCE_STALE",
          explanation: "SCIM: The fictional observation is older than the 90-day policy; it cannot establish support or incompatibility." }],
        capabilityPreferences: [{ capability: "SAML", profilePath: "protocols.federation.SAML", outcome: "UNKNOWN",
          reasonCode: "EVIDENCE_MISSING", explanation: "No fictional SAML evidence is recorded for this plan and region." }] },
      { optionId: "fictional-partial", displayName: "Fictional Broad Plan", plan: "Demo", region: "Synthetic region",
        hardVerdict: "PASSES_CHECKED_REQUIREMENTS", exclusionReasons: [], informationGaps: [],
        capabilityPreferences: [{ capability: "SAML", profilePath: "protocols.federation.SAML", outcome: "UNAVAILABLE",
          reasonCode: "PREFERRED_CAPABILITY_UNAVAILABLE", explanation: "SAML is unavailable; this preference alone does not exclude the plan." }] },
    ],
  };
}

function moduleUrl(source: string): string {
  return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
}

async function compile(relativePath: string): Promise<string> {
  const source = await readFile(new URL(relativePath, import.meta.url), "utf8");
  return ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"react"', JSON.stringify(import.meta.resolve("react")));
}

export async function architectureDesignFollowUpsModuleUrl() {
  return moduleUrl(await compile("../../src/app/assessments/[id]/architecture-design-follow-ups.tsx"));
}

export async function assessmentUiComponents() {
  const router = moduleUrl('export function useRouter() { return { push() { throw new Error("No navigation during rendering"); } }; }');
  const workflowUrl = moduleUrl((await compile("../../src/app/assessments/[id]/assessment-workflow.tsx"))
    .replaceAll('"next/navigation"', JSON.stringify(router))
    .replaceAll('"@/lib/assessment/workflow"', JSON.stringify(new URL("../../src/lib/assessment/workflow.ts", import.meta.url).href)));
  const workflow = await import(workflowUrl);
  const sectionFormUrl = moduleUrl((await compile("../../src/app/assessments/[id]/assessment-section-form.tsx"))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflowUrl))
    .replaceAll('"@/lib/assessment/profile-save"', JSON.stringify(new URL("../../src/lib/assessment/profile-save.ts", import.meta.url).href)));
  const sectionForm = await import(sectionFormUrl);
  const overview = await import(moduleUrl((await compile("../../src/app/assessments/[id]/saved-requirements-overview.tsx"))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflowUrl))
    .replaceAll('"@/lib/assessment/saved-requirements"', JSON.stringify(new URL("../../src/lib/assessment/saved-requirements.ts", import.meta.url).href))));
  const matrixUrl = moduleUrl((await compile("../../src/app/assessments/[id]/comparison-matrix.tsx"))
    .replaceAll('"@/lib/assessment/comparison-matrix"', JSON.stringify(new URL("../../src/lib/assessment/comparison-matrix.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/comparison-presentation"', JSON.stringify(new URL("../../src/lib/assessment/comparison-presentation.ts", import.meta.url).href)));
  const matrix = await import(matrixUrl);
  const followUpsUrl = moduleUrl((await compile("../../src/app/assessments/[id]/comparison-follow-ups.tsx"))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflowUrl))
    .replaceAll('"@/lib/assessment/comparison-follow-ups"', JSON.stringify(new URL("../../src/lib/assessment/comparison-follow-ups.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/comparison-presentation"', JSON.stringify(new URL("../../src/lib/assessment/comparison-presentation.ts", import.meta.url).href)));
  const followUps = await import(followUpsUrl);
  const comparison = await import(moduleUrl((await compile("../../src/app/assessments/[id]/comparison-section.tsx"))
    .replaceAll('"./comparison-follow-ups"', JSON.stringify(followUpsUrl))
    .replaceAll('"./comparison-matrix"', JSON.stringify(matrixUrl))
    .replaceAll('"@/lib/assessment/comparison-matrix"', JSON.stringify(new URL("../../src/lib/assessment/comparison-matrix.ts", import.meta.url).href))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflowUrl))
    .replaceAll('"@/lib/assessment/comparison-evidence"', JSON.stringify(new URL("../../src/lib/assessment/comparison-evidence.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/saved-requirements"', JSON.stringify(new URL("../../src/lib/assessment/saved-requirements.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/comparison-presentation"', JSON.stringify(new URL("../../src/lib/assessment/comparison-presentation.ts", import.meta.url).href))));
  const context = await import(moduleUrl((await compile("../../src/app/assessments/[id]/saved-context-summary.tsx"))
    .replaceAll('"@/lib/assessment/evaluation-context"', JSON.stringify(new URL("../../src/lib/assessment/evaluation-context.ts", import.meta.url).href))));
  const designFollowUpsUrl = await architectureDesignFollowUpsModuleUrl();
  const designFollowUps = await import(designFollowUpsUrl);
  const prerequisitesUrl = moduleUrl((await compile("../../src/app/assessments/[id]/architecture-prerequisites.tsx"))
    .replaceAll('"./architecture-design-follow-ups"', JSON.stringify(designFollowUpsUrl))
    .replaceAll('"@/lib/assessment/architecture-prerequisites"', JSON.stringify(new URL("../../src/lib/assessment/architecture-prerequisites.ts", import.meta.url).href)));
  const prerequisites = await import(prerequisitesUrl);
  const configurationUrl = moduleUrl((await compile("../../src/app/assessments/[id]/architecture-configuration.tsx"))
    .replaceAll('"./architecture-design-follow-ups"', JSON.stringify(designFollowUpsUrl))
    .replaceAll('"@/lib/assessment/architecture-configuration"', JSON.stringify(new URL("../../src/lib/assessment/architecture-configuration.ts", import.meta.url).href)));
  const configuration = await import(configurationUrl);
  const overviewUrl = moduleUrl((await compile("../../src/app/assessments/[id]/architecture-overview.tsx"))
    .replaceAll('"@/lib/assessment/architecture-presentation"', JSON.stringify(new URL("../../src/lib/assessment/architecture-presentation.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/evaluation-context"', JSON.stringify(new URL("../../src/lib/assessment/evaluation-context.ts", import.meta.url).href)));
  const architectureOverview = await import(overviewUrl);
  const architecture = await import(moduleUrl((await compile("../../src/app/assessments/[id]/architecture-patterns.tsx"))
    .replaceAll('"./architecture-overview"', JSON.stringify(overviewUrl))
    .replaceAll('"@/lib/assessment/architecture-presentation"', JSON.stringify(new URL("../../src/lib/assessment/architecture-presentation.ts", import.meta.url).href))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflowUrl))
    .replaceAll('"./architecture-prerequisites"', JSON.stringify(prerequisitesUrl))
    .replaceAll('"./architecture-configuration"', JSON.stringify(configurationUrl))
    .replaceAll('"@/lib/assessment/evaluation-context"', JSON.stringify(new URL("../../src/lib/assessment/evaluation-context.ts", import.meta.url).href))));
  const link = moduleUrl(`import { jsx } from ${JSON.stringify(import.meta.resolve("react/jsx-runtime"))};
    export default function Link({ children, ...props }) { return jsx("a", { ...props, children }); }`);
  const list = await import(moduleUrl((await compile("../../src/app/assessments/assessment-list.tsx"))
    .replaceAll('"next/link"', JSON.stringify(link))
    .replaceAll('"@/lib/assessment/evaluation-context"', JSON.stringify(new URL("../../src/lib/assessment/evaluation-context.ts", import.meta.url).href))));
  const requirementsExport = await import(moduleUrl((await compile("../../src/app/assessments/[id]/saved-requirements-export.tsx"))
    .replaceAll('"@/lib/assessment/requirements-brief"', JSON.stringify(new URL("../../src/lib/assessment/requirements-brief.ts", import.meta.url).href))));
  const capabilities = await import(moduleUrl((await compile("../../src/app/assessments/[id]/capability-editor.tsx"))
    .replaceAll('"./assessment-section-form"', JSON.stringify(sectionFormUrl))
    .replaceAll('"@/lib/assessment/capabilities"', JSON.stringify(new URL("../../src/lib/assessment/capabilities.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/capability-guidance"', JSON.stringify(new URL("../../src/lib/assessment/capability-guidance.ts", import.meta.url).href))));
  const contextEditor = await import(moduleUrl((await compile("../../src/app/assessments/[id]/evaluation-context-editor.tsx"))
    .replaceAll('"./assessment-section-form"', JSON.stringify(sectionFormUrl))
    .replaceAll('"@/lib/assessment/capabilities"', JSON.stringify(new URL("../../src/lib/assessment/capabilities.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/evaluation-context"', JSON.stringify(new URL("../../src/lib/assessment/evaluation-context.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/context-guidance"', JSON.stringify(new URL("../../src/lib/assessment/context-guidance.ts", import.meta.url).href))));
  const auditEditor = await import(moduleUrl((await compile("../../src/app/assessments/[id]/auditability-editor.tsx"))
    .replaceAll('"./assessment-section-form"', JSON.stringify(sectionFormUrl))
    .replaceAll('"@/lib/assessment/auditability"', JSON.stringify(new URL("../../src/lib/assessment/auditability.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/capabilities"', JSON.stringify(new URL("../../src/lib/assessment/capabilities.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/audit-guidance"', JSON.stringify(new URL("../../src/lib/assessment/audit-guidance.ts", import.meta.url).href))));
  const usageFormUrl = moduleUrl((await compile("../../src/app/assessments/[id]/usage-planning-form.tsx"))
    .replaceAll('"./assessment-section-form"', JSON.stringify(sectionFormUrl)));
  const usageForm = await import(usageFormUrl);
  const usageEditor = await import(moduleUrl((await compile("../../src/app/assessments/[id]/usage-planning-editor.tsx"))
    .replaceAll('"./usage-planning-form"', JSON.stringify(usageFormUrl))
    .replaceAll('"@/lib/assessment/usage-planning"', JSON.stringify(new URL("../../src/lib/assessment/usage-planning.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/usage-guidance"', JSON.stringify(new URL("../../src/lib/assessment/usage-guidance.ts", import.meta.url).href))));
  const operations = await import(moduleUrl((await compile("../../src/app/assessments/[id]/operations-planning.tsx"))
    .replaceAll('"@/lib/assessment/usage-planning"', JSON.stringify(new URL("../../src/lib/assessment/usage-planning.ts", import.meta.url).href))));
  const operationsEditor = await import(moduleUrl((await compile("../../src/app/assessments/[id]/operational-preferences-editor.tsx"))
    .replaceAll('"./assessment-section-form"', JSON.stringify(sectionFormUrl))
    .replaceAll('"@/lib/assessment/operational-preferences"', JSON.stringify(new URL("../../src/lib/assessment/operational-preferences.ts", import.meta.url).href))));
  const assurance = await import(moduleUrl((await compile("../../src/app/assessments/[id]/assurance-compliance-planning.tsx"))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflowUrl))
    .replaceAll('"@/lib/assessment/assurance-compliance-planning"', JSON.stringify(new URL("../../src/lib/assessment/assurance-compliance-planning.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/evaluation-context"', JSON.stringify(new URL("../../src/lib/assessment/evaluation-context.ts", import.meta.url).href))));
  return { ...workflow, ...sectionForm, ...overview, ...comparison, ...matrix, ...followUps, ...context, ...designFollowUps, ...prerequisites, ...configuration, ...architectureOverview, ...architecture, ...list, ...requirementsExport, ...capabilities, ...contextEditor, ...auditEditor, ...usageEditor, ...usageForm, ...operations, ...operationsEditor, ...assurance };
}
