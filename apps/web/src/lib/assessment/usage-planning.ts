export const usageMetrics = [
  {
    key: "MONTHLY_ACTIVE_USERS",
    label: "Monthly active users",
    help: "Distinct humans authenticating in one month, not registered accounts or login count.",
  },
  {
    key: "ENTERPRISE_SSO_CONNECTIONS",
    label: "Enterprise SSO connections",
    help: "Configured upstream enterprise IdP connections, not the number of organizations.",
  },
  {
    key: "MONTHLY_M2M_TOKEN_ISSUANCES",
    label: "Monthly M2M token issuances",
    help: "Machine-to-machine access tokens issued in one month, not downstream API requests.",
  },
  {
    key: "PEAK_HUMAN_LOGINS_PER_SECOND",
    label: "Peak human logins per second",
    help: "Successful human logins during the busiest one-second interval.",
  },
] as const;

export type UsageMetric = (typeof usageMetrics)[number]["key"];
export type UsageQuantity = { basis: "ASSUMED" | "OBSERVED"; value: number };
export type UsagePlanningValues = {
  scopeDescription: string;
  assumptions: string[];
  volumes: Partial<Record<UsageMetric, UsageQuantity>>;
};

export class InvalidUsagePlanningForm extends Error { }

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ?
    value as Record<string, unknown> : null;
}

function validValues(value: unknown): value is UsagePlanningValues {
  const planning = record(value);
  const volumes = record(planning?.volumes);
  if (!planning || !volumes || typeof planning.scopeDescription !== "string" ||
      Object.keys(planning).some(key => !["scopeDescription", "assumptions", "volumes"].includes(key)) ||
      planning.scopeDescription.length > 500 || !Array.isArray(planning.assumptions) ||
      planning.assumptions.length > 10 ||
      planning.assumptions.some(item => typeof item !== "string" || item.trim() === "" || item.length > 500) ||
      new Set(planning.assumptions).size !== planning.assumptions.length) return false;
  const known = usageMetrics.map(metric => metric.key);
  return Object.entries(volumes).every(([key, value]) => {
    const quantity = record(value);
    return known.includes(key as UsageMetric) && quantity !== null &&
      Object.keys(quantity).length === 2 && Object.hasOwn(quantity, "basis") &&
      Object.hasOwn(quantity, "value") &&
      (quantity.basis === "ASSUMED" || quantity.basis === "OBSERVED") &&
      Number.isSafeInteger(quantity.value) && Number(quantity.value) >= 0;
  });
}

export function usagePlanningValues(profile: Record<string, unknown>): UsagePlanningValues | null {
  const operations = record(profile.operations);
  const planning = operations?.usagePlanning;
  if (!validValues(planning)) return null;
  return {
    scopeDescription: planning.scopeDescription,
    assumptions: [...planning.assumptions],
    volumes: structuredClone(planning.volumes),
  };
}

function single(params: URLSearchParams, key: string): string {
  const entries = params.getAll(key);
  if (entries.length !== 1) throw new InvalidUsagePlanningForm();
  return entries[0];
}

export function parseUsagePlanningForm(params: URLSearchParams): {
  expectedVersion: number; values: UsagePlanningValues;
} {
  const allowed = new Set(["expectedVersion", "scopeDescription", "assumption",
    ...usageMetrics.flatMap(metric => [`basis_${metric.key}`, `value_${metric.key}`])]);
  if ([...params.keys()].some(key => !allowed.has(key))) throw new InvalidUsagePlanningForm();
  const rawVersion = single(params, "expectedVersion");
  if (!/^(0|[1-9][0-9]*)$/.test(rawVersion) || !Number.isSafeInteger(Number(rawVersion))) {
    throw new InvalidUsagePlanningForm();
  }
  const scopeDescription = single(params, "scopeDescription");
  if (scopeDescription.length > 500) throw new InvalidUsagePlanningForm();
  const entries = params.getAll("assumption");
  if (entries.length !== 10 || entries.some(item => item.length > 500 || (item !== "" && item.trim() === ""))) {
    throw new InvalidUsagePlanningForm();
  }
  const assumptions = entries.filter(item => item !== "");
  if (new Set(assumptions).size !== assumptions.length) throw new InvalidUsagePlanningForm();
  const volumes: UsagePlanningValues["volumes"] = {};
  for (const metric of usageMetrics) {
    const basis = single(params, `basis_${metric.key}`);
    const rawValue = single(params, `value_${metric.key}`);
    if (basis === "UNKNOWN" && rawValue === "") continue;
    if (basis !== "ASSUMED" && basis !== "OBSERVED") throw new InvalidUsagePlanningForm();
    if (!/^(0|[1-9][0-9]*)$/.test(rawValue)) throw new InvalidUsagePlanningForm();
    const value = Number(rawValue);
    if (!Number.isSafeInteger(value)) throw new InvalidUsagePlanningForm();
    volumes[metric.key] = { basis, value };
  }
  const values = { scopeDescription: scopeDescription.trim() === "" ? "" : scopeDescription,
    assumptions, volumes };
  if (!validValues(values)) throw new InvalidUsagePlanningForm();
  return { expectedVersion: Number(rawVersion), values };
}

export function withUsagePlanningValues(profile: Record<string, unknown>,
  values: UsagePlanningValues): Record<string, unknown> {
  if (!usagePlanningValues(profile) || !validValues(values)) {
    throw new Error("Core profile is not editable in this form");
  }
  const copy = structuredClone(profile);
  const operations = copy.operations as Record<string, unknown>;
  operations.usagePlanning = structuredClone(values);
  return copy;
}
