import schema from "./generated/profile-schema.json" with { type: "json" };
import example from "./generated/example-profile.json" with { type: "json" };
import validateProfile from "./generated/validate-profile.mjs";

export type SectionId = "application" | "audience" | "protocols" | "provisioning" | "security" | "operations";
export type Profile = Record<SectionId, Record<string, unknown>>;
type SchemaNode = { type?: string; enum?: string[]; properties?: Record<string, SchemaNode>; items?: SchemaNode; $ref?: string };
export type Field = { path: string; label: string; help: string; multiple: boolean; options: string[]; criticality: boolean };
export type Section = { id: SectionId; title: string; description: string; fields: Field[] };

const definitions: [SectionId, string, string, [string, string, string][]][] = [
  ["application", "Application", "Start with the application and the clients it serves.", [
    ["type", "Application type", "Choose the closest description of your application."],
    ["clients", "Client types", "Select all clients that need to authenticate. Leave empty if this is still open."],
  ]],
  ["audience", "Audience", "Describe who signs in and how organizations are separated.", [
    ["populations", "User populations", "Select every group that will use the application."],
    ["tenancy", "Organization boundary", "Does the application serve one organization, many, or individual users?"],
    ["membership", "Organization membership", "Can one person belong to more than one organization?"],
  ]],
  ["protocols", "Sign-in & APIs", "Record protocol needs without choosing an identity provider.", [
    ["federation.OIDC", "OpenID Connect (OIDC)", "Federated sign-in using OpenID Connect."],
    ["federation.SAML", "SAML federation", "Federated sign-in using SAML, often requested by enterprise customers."],
    ["oauth2ProtectedApis", "OAuth 2.0 protected APIs", "API access using OAuth 2.0 access tokens."],
    ["socialLogin", "Social login", "Sign-in with a consumer identity provider."],
    ["enterpriseSingleSignOn", "Enterprise single sign-on", "Let organizations use their existing corporate identity provider."],
  ]],
  ["provisioning", "User lifecycle", "Describe how accounts and groups are created and maintained.", [
    ["scim", "SCIM provisioning", "Synchronize user lifecycle changes from an organization's directory."],
    ["justInTimeProvisioning", "Just-in-time provisioning", "Create an application account when a person first signs in."],
    ["groupSynchronization", "Group synchronization", "Keep application groups aligned with an external directory."],
  ]],
  ["security", "Security", "Capture security expectations and topics that need further review.", [
    ["multiFactorAuthentication", "Multi-factor authentication", "Require or prefer more than one authentication factor."],
    ["browserTokenExposureMinimization", "Minimize token exposure in browsers", "Reduce the exposure of access tokens to browser-side code."],
    ["auditability", "Auditability", "Record identity events for investigation and review."],
    ["dataResidency", "Data residency", "Is where identity data is stored a constraint? Specific regions need a later review."],
    ["assurance", "Identity assurance", "A planning expectation, not a certification or a formal assurance level."],
    ["complianceTargets", "Compliance topics to review", "These are discussion inputs, not a claim that a provider or application is compliant."],
  ]],
  ["operations", "Operations", "Balance hosting preferences with the team's capacity and budget.", [
    ["hosting", "Identity hosting preference", "Prefer a managed service, self-hosting, or keep the choice open."],
    ["deploymentTarget", "Application deployment target", "Where the application is expected to run; this does not select a provider."],
    ["identityExpertise", "Team identity expertise", "The team's current ability to operate identity infrastructure."],
    ["budgetSensitivity", "Budget sensitivity", "How strongly cost constrains the eventual decision."],
  ]],
];

export const sections: Section[] = definitions.map(([id, title, description, fields]) => ({
  id, title, description,
  fields: fields.map(([key, label, help]) => {
    const path = `${id}.${key}`;
    let node = schema as SchemaNode;
    for (const part of path.split(".")) {
      const next = node.properties?.[part];
      if (!next) throw new Error(`Missing profile contract field: ${path}`);
      node = next;
    }
    const criticality = node.$ref === "#/$defs/criticality";
    const options = criticality ? schema.$defs.criticality.enum : (node.enum ?? node.items?.enum);
    if (!options) throw new Error(`Unsupported profile field: ${path}`);
    return { path, label, help, options, multiple: node.type === "array", criticality };
  }),
}));

export const allFields = sections.flatMap(section => section.fields);
const labels: Record<string, string> = {
  B2B_SAAS: "B2B SaaS", OIDC: "OpenID Connect", SAML: "SAML", SOC_2: "SOC 2",
  ISO_27001: "ISO 27001", HIPAA: "HIPAA", FEDRAMP: "FedRAMP", GDPR: "GDPR",
  AZURE: "Azure", AWS: "AWS", GOOGLE_CLOUD: "Google Cloud", UNKNOWN: "Unknown",
  NOT_REQUIRED: "Not required", NO_ORGANIZATION_BOUNDARY: "No organization boundary",
  MULTI_TENANT_ORGANIZATIONS: "Multiple tenant organizations", NO_PREFERENCE: "No preference",
};
export function valueLabel(value: string): string {
  return labels[value] ?? value.toLowerCase().replaceAll("_", " ").replace(/^./, letter => letter.toUpperCase());
}
export function getValue(profile: Profile, path: string): string | string[] | undefined {
  let value: unknown = profile;
  for (const part of path.split(".")) value = value && typeof value === "object" ? (value as Record<string, unknown>)[part] : undefined;
  return typeof value === "string" || (Array.isArray(value) && value.every(item => typeof item === "string")) ? value : undefined;
}
export function setValue(profile: Profile, path: string, value: string | string[]): Profile {
  const field = allFields.find(item => item.path === path);
  if (!field || (field.multiple ? !Array.isArray(value) : typeof value !== "string")) throw new Error("Invalid profile field.");
  const values = Array.isArray(value) ? value : [value];
  if (values.some(item => !field.options.includes(item)) || new Set(values).size !== values.length) throw new Error("Invalid profile value.");
  const copy = structuredClone(profile);
  const parts = path.split(".");
  let parent: Record<string, unknown> = copy;
  for (const part of parts.slice(0, -1)) {
    parent[part] ??= {};
    parent = parent[part] as Record<string, unknown>;
  }
  parent[parts.at(-1)!] = Array.isArray(value) ? [...value] : value;
  return copy;
}
export function exampleProfile(): Profile { return structuredClone(example); }
export function blankProfile(): Profile {
  let result = Object.fromEntries(sections.map(section => [section.id, {}])) as Profile;
  for (const field of allFields) {
    result = setValue(result, field.path, field.multiple ? [] : field.options.includes("UNKNOWN") ? "UNKNOWN" : "UNDECIDED");
  }
  return result;
}
export function openFields(profile: Profile): Field[] {
  return allFields.filter(field => {
    const value = getValue(profile, field.path);
    return value === undefined || value === "UNKNOWN" || value === "UNDECIDED" || (Array.isArray(value) && value.length === 0);
  });
}
export function displayValue(profile: Profile, field: Field): string {
  const value = getValue(profile, field.path);
  return Array.isArray(value) ? (value.map(valueLabel).join(", ") || "Not recorded") : value ? valueLabel(value) : "Not recorded";
}
export function structuralIssues(profile: unknown): string[] {
  if (validateProfile(profile)) return [];
  return (validateProfile.errors ?? []).map(error => `${error.instancePath || "Profile"}: ${error.message ?? "Invalid value"}`);
}
function assertValid(profile: Profile) {
  if (structuralIssues(profile).length) throw new Error("This profile cannot be exported because its structure is invalid.");
}
export function jsonBrief(profile: Profile): string {
  assertValid(profile);
  return JSON.stringify(profile, null, 2) + "\n";
}
export function markdownBrief(profile: Profile): string {
  assertValid(profile);
  const lines = ["# AuthWeave requirements brief", "", "> Draft · Requirements preview · Not an architecture decision or provider recommendation.", "",
    "This brief records planning inputs. Only profile structure has been checked; cross-field domain validation, provider evaluation and compliance verification have not been performed.", "",
    "Required is mandatory; preferred is desirable; not required imposes no constraint; forbidden explicitly excludes a capability; unknown needs clarification.", ""];
  for (const section of sections) {
    lines.push(`## ${section.title}`, "", ...section.fields.map(field => `- **${field.label}:** ${displayValue(profile, field)}`), "");
  }
  const open = openFields(profile);
  lines.push("## Open questions and unrecorded selections", "");
  lines.push(...(open.length ? open.map(field => `- Clarify ${field.label.toLowerCase()} (${displayValue(profile, field)}).`) : ["No unknown values or unrecorded selections. This does not establish completeness, consistency or suitability."]));
  lines.push("", "Empty selections mean no choice was recorded; they do not establish that a capability or compliance topic is unnecessary.", "",
    "Generated in the browser with AuthWeave. The JSON download follows the application identity profile v1 contract.", "");
  return lines.join("\n");
}
