import { readFile } from "node:fs/promises";
import ts from "typescript";

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

export async function assessmentUiComponents() {
  const router = moduleUrl('export function useRouter() { return { push() { throw new Error("No navigation during rendering"); } }; }');
  const workflowUrl = moduleUrl((await compile("../../src/app/assessments/[id]/assessment-workflow.tsx"))
    .replaceAll('"next/navigation"', JSON.stringify(router))
    .replaceAll('"@/lib/assessment/workflow"', JSON.stringify(new URL("../../src/lib/assessment/workflow.ts", import.meta.url).href)));
  const workflow = await import(workflowUrl);
  const overview = await import(moduleUrl((await compile("../../src/app/assessments/[id]/saved-requirements-overview.tsx"))
    .replaceAll('"./assessment-workflow"', JSON.stringify(workflowUrl))
    .replaceAll('"@/lib/assessment/saved-requirements"', JSON.stringify(new URL("../../src/lib/assessment/saved-requirements.ts", import.meta.url).href))));
  return { ...workflow, ...overview };
}
