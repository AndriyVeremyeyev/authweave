import { criticalities, type Criticality } from "./capabilities.ts";

export const applicationTypes = ["UNKNOWN", "B2B_SAAS", "PARTNER_PORTAL", "PUBLIC_SECTOR_PORTAL",
  "INTERNAL_WORKFORCE", "OTHER"] as const;
export const clientTypes = ["BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"] as const;
export const populations = ["EXTERNAL_CUSTOMERS", "PARTNERS", "CITIZENS", "EMPLOYEES",
  "CONTRACTORS", "INTERNAL_OPERATORS"] as const;
export const tenancyModels = ["UNKNOWN", "MULTI_TENANT_ORGANIZATIONS", "SINGLE_ORGANIZATION",
  "NO_ORGANIZATION_BOUNDARY"] as const;
export const membershipModels = ["UNKNOWN", "SINGLE_ORGANIZATION_PER_USER",
  "MULTIPLE_ORGANIZATIONS_PER_USER", "NOT_APPLICABLE"] as const;
export const complianceScopeStatuses = ["UNKNOWN", "NONE_IDENTIFIED", "TARGETS_IDENTIFIED"] as const;
export const complianceTargets = ["SOC_2", "ISO_27001", "HIPAA", "FEDRAMP", "GDPR", "OTHER"] as const;

export type EvaluationContextValues = {
  applicationType: (typeof applicationTypes)[number];
  clients: (typeof clientTypes)[number][];
  selectedPopulations: (typeof populations)[number][];
  tenancy: (typeof tenancyModels)[number];
  membership: (typeof membershipModels)[number];
  dataResidency: Criticality;
  phishingResistance: Criticality;
  nonExportableKeys: Criticality;
  stepUpAuthentication: Criticality;
  complianceScopeStatus: (typeof complianceScopeStatuses)[number];
  selectedComplianceTargets: (typeof complianceTargets)[number][];
};

export class InvalidEvaluationContextForm extends Error { }

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ?
    value as Record<string, unknown> : null;
}

function option<T extends string>(value: unknown, choices: readonly T[]): value is T {
  return typeof value === "string" && choices.includes(value as T);
}

function selected<T extends string>(value: unknown, choices: readonly T[]): T[] | null {
  if (!Array.isArray(value) || value.some(item => !option(item, choices)) ||
      new Set(value).size !== value.length) return null;
  return value as T[];
}

export function evaluationContextValues(profile: Record<string, unknown>): EvaluationContextValues | null {
  const application = record(profile.application);
  const audience = record(profile.audience);
  const security = record(profile.security);
  const controls = record(security?.authenticationControls);
  if (!application || !audience || !security || !controls) return null;
  const clients = selected(application.clients, clientTypes);
  const selectedPopulations = selected(audience.populations, populations);
  const selectedComplianceTargets = selected(security.complianceTargets, complianceTargets);
  if (!option(application.type, applicationTypes) || !clients || !selectedPopulations ||
      !selectedComplianceTargets ||
      !option(audience.tenancy, tenancyModels) || !option(audience.membership, membershipModels) ||
      !option(security.dataResidency, criticalities) ||
      !option(controls.phishingResistance, criticalities) ||
      !option(controls.nonExportableKeys, criticalities) ||
      !option(controls.stepUpAuthentication, criticalities) ||
      !option(security.complianceScopeStatus, complianceScopeStatuses)) return null;
  return {
    applicationType: application.type, clients, selectedPopulations,
    tenancy: audience.tenancy, membership: audience.membership,
    dataResidency: security.dataResidency,
    phishingResistance: controls.phishingResistance,
    nonExportableKeys: controls.nonExportableKeys,
    stepUpAuthentication: controls.stepUpAuthentication,
    complianceScopeStatus: security.complianceScopeStatus,
    selectedComplianceTargets,
  };
}

function single(params: URLSearchParams, key: string, choices: readonly string[]): string {
  const entries = params.getAll(key);
  if (entries.length !== 1 || !choices.includes(entries[0])) throw new InvalidEvaluationContextForm();
  return entries[0];
}

function many(params: URLSearchParams, key: string, choices: readonly string[]): string[] {
  const entries = params.getAll(key);
  if (entries.some(value => !choices.includes(value)) || new Set(entries).size !== entries.length) {
    throw new InvalidEvaluationContextForm();
  }
  return entries;
}

export function parseEvaluationContextForm(params: URLSearchParams): {
  expectedVersion: number; values: EvaluationContextValues;
} {
  const allowed = ["expectedVersion", "applicationType", "clients", "selectedPopulations", "tenancy",
    "membership", "dataResidency", "phishingResistance", "nonExportableKeys",
    "stepUpAuthentication", "complianceScopeStatus", "selectedComplianceTargets"];
  if ([...params.keys()].some(key => !allowed.includes(key))) throw new InvalidEvaluationContextForm();
  const versions = params.getAll("expectedVersion");
  if (versions.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versions[0])) {
    throw new InvalidEvaluationContextForm();
  }
  const expectedVersion = Number(versions[0]);
  if (!Number.isSafeInteger(expectedVersion)) throw new InvalidEvaluationContextForm();
  const values: EvaluationContextValues = {
    applicationType: single(params, "applicationType", applicationTypes) as EvaluationContextValues["applicationType"],
    clients: many(params, "clients", clientTypes) as EvaluationContextValues["clients"],
    selectedPopulations: many(params, "selectedPopulations", populations) as EvaluationContextValues["selectedPopulations"],
    tenancy: single(params, "tenancy", tenancyModels) as EvaluationContextValues["tenancy"],
    membership: single(params, "membership", membershipModels) as EvaluationContextValues["membership"],
    dataResidency: single(params, "dataResidency", criticalities) as Criticality,
    phishingResistance: single(params, "phishingResistance", criticalities) as Criticality,
    nonExportableKeys: single(params, "nonExportableKeys", criticalities) as Criticality,
    stepUpAuthentication: single(params, "stepUpAuthentication", criticalities) as Criticality,
    complianceScopeStatus: single(params, "complianceScopeStatus", complianceScopeStatuses) as EvaluationContextValues["complianceScopeStatus"],
    selectedComplianceTargets: many(params, "selectedComplianceTargets", complianceTargets) as EvaluationContextValues["selectedComplianceTargets"],
  };
  return { expectedVersion, values };
}

export function withEvaluationContextValues(profile: Record<string, unknown>,
  values: EvaluationContextValues): Record<string, unknown> {
  if (!evaluationContextValues(profile)) throw new Error("Core profile is not editable in this form");
  const copy = structuredClone(profile);
  const application = copy.application as Record<string, unknown>;
  const audience = copy.audience as Record<string, unknown>;
  const security = copy.security as Record<string, unknown>;
  const controls = security.authenticationControls as Record<string, unknown>;
  application.type = values.applicationType;
  application.clients = [...values.clients];
  audience.populations = [...values.selectedPopulations];
  audience.tenancy = values.tenancy;
  audience.membership = values.membership;
  security.dataResidency = values.dataResidency;
  security.complianceScopeStatus = values.complianceScopeStatus;
  security.complianceTargets = [...values.selectedComplianceTargets];
  controls.phishingResistance = values.phishingResistance;
  controls.nonExportableKeys = values.nonExportableKeys;
  controls.stepUpAuthentication = values.stepUpAuthentication;
  if (!evaluationContextValues(copy)) throw new InvalidEvaluationContextForm();
  return copy;
}
