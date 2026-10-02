import { criticalities, type Criticality } from "./capabilities.ts";

export const auditCriteria = [
  { key: "AUTHENTICATION_SUCCESS_EVENTS", label: "Successful sign-ins", help: "Identity-provider events for successful authentication." },
  { key: "AUTHENTICATION_FAILURE_EVENTS", label: "Failed sign-ins", help: "Identity-provider events for unsuccessful authentication attempts." },
  { key: "ADMINISTRATIVE_CHANGE_EVENTS", label: "Administrative changes", help: "Identity-provider events for administrative changes, not application business events." },
  { key: "PROVISIONING_CHANGE_EVENTS", label: "Provisioning changes", help: "Identity-provider events for provisioning changes; this does not imply SCIM support." },
  { key: "AUDIT_LOG_EXPORT", label: "Log export", help: "Provider-supported export of identity audit logs, not a verified external logging pipeline." },
  { key: "AUDIT_LOG_RETENTION", label: "Log retention", help: "A requested minimum duration for the provider's identity audit logs, not an external sink." },
] as const;
export type AuditCriterion = (typeof auditCriteria)[number]["key"];
export const maximumRetentionDays = 36_500;
export type AuditabilityValues = {
  criticality: Criticality;
  selectedCriteria: AuditCriterion[];
  minimumRetentionDays: number | null;
};
export class InvalidAuditabilityForm extends Error {}

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

function valid(values: AuditabilityValues): boolean {
  return criticalities.includes(values.criticality) && Array.isArray(values.selectedCriteria) &&
    values.selectedCriteria.length <= auditCriteria.length &&
    new Set(values.selectedCriteria).size === values.selectedCriteria.length &&
    values.selectedCriteria.every(key => auditCriteria.some(criterion => criterion.key === key)) &&
    (values.selectedCriteria.includes("AUDIT_LOG_RETENTION")
      ? Number.isSafeInteger(values.minimumRetentionDays) && Number(values.minimumRetentionDays) >= 1 &&
        Number(values.minimumRetentionDays) <= maximumRetentionDays
      : values.minimumRetentionDays === null);
}

function canonical(values: AuditabilityValues): AuditabilityValues {
  return { ...values, selectedCriteria: auditCriteria.filter(criterion => values.selectedCriteria.includes(criterion.key))
    .map(criterion => criterion.key) };
}

// Legacy projection belongs to Core v6. Never infer missing fields or a duration here.
export function auditabilityValues(profile: Record<string, unknown>): AuditabilityValues | null {
  const security = object(profile.security);
  const requirements = object(security?.auditabilityRequirements);
  if (!security || !requirements || Object.keys(requirements).length !== 2 ||
      !Object.hasOwn(requirements, "selectedCriteria") || !Object.hasOwn(requirements, "minimumRetentionDays")) return null;
  const values = { criticality: security.auditability, selectedCriteria: requirements.selectedCriteria,
    minimumRetentionDays: requirements.minimumRetentionDays } as AuditabilityValues;
  return valid(values) ? canonical(values) : null;
}

export function parseAuditabilityForm(form: URLSearchParams): { expectedVersion: number; values: AuditabilityValues } {
  const allowed = ["expectedVersion", "criticality", "selectedCriteria", "minimumRetentionDays"];
  if ([...form.keys()].some(key => !allowed.includes(key)) ||
      form.getAll("expectedVersion").length !== 1 || form.getAll("criticality").length !== 1 ||
      form.getAll("minimumRetentionDays").length > 1) throw new InvalidAuditabilityForm();
  const version = form.get("expectedVersion")!;
  const expectedVersion = Number(version);
  if (!/^(0|[1-9][0-9]*)$/.test(version) || !Number.isSafeInteger(expectedVersion)) throw new InvalidAuditabilityForm();
  const duration = form.get("minimumRetentionDays");
  if (duration !== null && duration !== "" && !/^[1-9][0-9]*$/.test(duration)) throw new InvalidAuditabilityForm();
  const values = { criticality: form.get("criticality"), selectedCriteria: form.getAll("selectedCriteria"),
    minimumRetentionDays: duration === null || duration === "" ? null : Number(duration) } as AuditabilityValues;
  if (!valid(values)) throw new InvalidAuditabilityForm();
  return { expectedVersion, values: canonical(values) };
}

export function withAuditabilityValues(profile: Record<string, unknown>, values: AuditabilityValues): Record<string, unknown> {
  if (!auditabilityValues(profile) || !valid(values) || Object.keys(values).some(key =>
    !["criticality", "selectedCriteria", "minimumRetentionDays"].includes(key))) throw new InvalidAuditabilityForm();
  const next = structuredClone(profile);
  const security = next.security as Record<string, unknown>;
  const ordered = canonical(values);
  security.auditability = ordered.criticality;
  security.auditabilityRequirements = { selectedCriteria: ordered.selectedCriteria, minimumRetentionDays: ordered.minimumRetentionDays };
  return next;
}
