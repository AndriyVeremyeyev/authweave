export const capabilityFields = [
  { capability: "OIDC", label: "OpenID Connect (OIDC)", path: ["protocols", "federation", "OIDC"] },
  { capability: "SAML", label: "SAML federation", path: ["protocols", "federation", "SAML"] },
  { capability: "OAUTH2_APIS", label: "OAuth 2.0 protected APIs", path: ["protocols", "oauth2ProtectedApis"] },
  { capability: "SOCIAL_LOGIN", label: "Social login", path: ["protocols", "socialLogin"] },
  { capability: "ENTERPRISE_SSO", label: "Enterprise single sign-on", path: ["protocols", "enterpriseSingleSignOn"] },
  { capability: "SCIM", label: "SCIM provisioning", path: ["provisioning", "scim"] },
  { capability: "JIT", label: "Just-in-time provisioning", path: ["provisioning", "justInTimeProvisioning"] },
  { capability: "GROUP_SYNC", label: "Group synchronization", path: ["provisioning", "groupSynchronization"] },
  { capability: "MFA", label: "Multi-factor authentication", path: ["security", "multiFactorAuthentication"] },
] as const;

export const criticalities = ["UNKNOWN", "REQUIRED", "PREFERRED", "NOT_REQUIRED", "FORBIDDEN"] as const;
export type Criticality = (typeof criticalities)[number];
export type Capability = (typeof capabilityFields)[number]["capability"];
export type CapabilityValues = Record<Capability, Criticality>;

export class InvalidCapabilityForm extends Error { }

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

function validCriticality(value: unknown): value is Criticality {
  return typeof value === "string" && (criticalities as readonly string[]).includes(value);
}

export function capabilityValues(profile: Record<string, unknown>): CapabilityValues | null {
  const values: Partial<CapabilityValues> = {};
  for (const field of capabilityFields) {
    let parent: Record<string, unknown> | null = profile;
    for (const part of field.path.slice(0, -1)) parent = record(parent?.[part]);
    if (!parent) return null;
    const leaf = field.path.at(-1)!;
    const value = Object.hasOwn(parent, leaf) ? parent[leaf] :
      (field.path.length === 3 ? "UNKNOWN" : undefined);
    if (!validCriticality(value)) return null;
    values[field.capability] = value;
  }
  return values as CapabilityValues;
}

export function parseCapabilityForm(params: URLSearchParams): { expectedVersion: number; values: CapabilityValues } {
  if ([...params.keys()].length !== capabilityFields.length + 1) throw new InvalidCapabilityForm();
  const versions = params.getAll("expectedVersion");
  if (versions.length !== 1 || !/^(0|[1-9][0-9]*)$/.test(versions[0])) throw new InvalidCapabilityForm();
  const expectedVersion = Number(versions[0]);
  if (!Number.isSafeInteger(expectedVersion)) throw new InvalidCapabilityForm();
  const values: Partial<CapabilityValues> = {};
  for (const field of capabilityFields) {
    const entries = params.getAll(field.capability);
    if (entries.length !== 1 || !validCriticality(entries[0])) throw new InvalidCapabilityForm();
    values[field.capability] = entries[0];
  }
  return { expectedVersion, values: values as CapabilityValues };
}

export function withCapabilityValues(profile: Record<string, unknown>, values: CapabilityValues): Record<string, unknown> {
  if (Object.keys(values).length !== capabilityFields.length ||
      capabilityFields.some(field => !validCriticality(values[field.capability]))) {
    throw new InvalidCapabilityForm();
  }
  if (!capabilityValues(profile)) throw new Error("Core profile is not editable in this form");
  const copy = structuredClone(profile);
  for (const field of capabilityFields) {
    let parent: Record<string, unknown> = copy;
    for (const part of field.path.slice(0, -1)) parent = parent[part] as Record<string, unknown>;
    const leaf = field.path.at(-1)!;
    // An absent federation key already means UNKNOWN. Keep a no-op save a no-op.
    if (!Object.hasOwn(parent, leaf) && values[field.capability] === "UNKNOWN") continue;
    parent[leaf] = values[field.capability];
  }
  return copy;
}
