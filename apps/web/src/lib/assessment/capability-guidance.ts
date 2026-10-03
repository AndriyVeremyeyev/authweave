import type { Capability, Criticality } from "./capabilities.ts";

// Educational copy only: not provider evidence, applicability rules or default answers.
export const criticalityGuidance = {
  UNKNOWN: { label: "Unknown", explanation: "You have not decided yet. Keep this answer when you need more information; it is not a yes or a no." },
  REQUIRED: { label: "Required", explanation: "A hard constraint: the option must offer this capability. Missing or outdated evidence leaves the check unresolved, not passed." },
  PREFERRED: { label: "Preferred", explanation: "Useful, but not a deal-breaker. Its absence alone does not exclude an option. This editor does not assign a weight or select a winner." },
  NOT_REQUIRED: { label: "Not required", explanation: "This capability does not affect your decision. An option may still offer it; this is not a prohibition." },
  FORBIDDEN: { label: "Forbidden", explanation: "A hard constraint: the capability must be absent, or optional and kept disabled. Do not use this answer simply because you do not need it." },
} as const satisfies Record<Criticality, { label: string; explanation: string }>;

type CapabilityGuidance = {
  definition: string;
  usefulWhen: string;
  tradeOff: string;
  question: string;
  source: { title: string; href: string };
};

const identityArchitectureSource = {
  title: "Microsoft: identity architecture concepts",
  href: "https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/identity",
};

export const capabilityGuidance = {
  OIDC: {
    definition: "A standard way for an application to sign users in through an identity provider, built on OAuth 2.0.",
    usefulWhen: "Your application needs federated sign-in rather than managing a separate password for each user.",
    tradeOff: "Sign-in does not provision or remove accounts. API access and account lifecycle need separate decisions.",
    question: "Which identity providers must users sign in through, and do those connections support OIDC?",
    source: { title: "OpenID Foundation: OpenID Connect Core", href: "https://openid.net/specs/openid-connect-core-1_0.html" },
  },
  SAML: {
    definition: "A federation standard that carries identity assertions between an identity provider and an application.",
    usefulWhen: "A customer or partner requires a SAML connection to its existing identity provider.",
    tradeOff: "Trust configuration, metadata and signing certificates need maintenance. SAML sign-in is not account provisioning.",
    question: "Does an organization require SAML specifically, or can its connection use OIDC? Both may be needed.",
    source: { title: "OASIS: SAML technical overview", href: "https://docs.oasis-open.org/security/saml/Post2.0/sstc-saml-tech-overview-2.0.html" },
  },
  OAUTH2_APIS: {
    definition: "Access tokens let clients call protected APIs under an authorization policy.",
    usefulWhen: "Your APIs need access from applications, integrations or machine-to-machine clients.",
    tradeOff: "OAuth 2.0 alone is not a user sign-in protocol. Tokens still need validation, and the API must enforce permissions.",
    question: "Who calls the APIs: users, services, or both, and what access must each caller receive?",
    source: { title: "IETF: OAuth 2.0 authorization framework", href: "https://www.rfc-editor.org/rfc/rfc6749.html" },
  },
  SOCIAL_LOGIN: {
    definition: "Users sign in with an existing personal account at an external identity provider.",
    usefulWhen: "Your audience wants to reuse personal accounts instead of creating another sign-in credential.",
    tradeOff: "You depend on an external account and recovery process. Personal accounts do not establish membership in a customer organization.",
    question: "Are personal accounts acceptable for this audience, and how will you safely link accounts?",
    source: identityArchitectureSource,
  },
  ENTERPRISE_SSO: {
    definition: "Users access the application through their organization's identity provider and existing sign-in session.",
    usefulWhen: "A customer or employer wants to control sign-in through its own identity system.",
    tradeOff: "Each organization needs a trusted connection and tenant mapping. SSO does not by itself remove local accounts or application sessions.",
    question: "Which organizations need their own connection? Record OIDC or SAML separately; SSO is not another protocol.",
    source: identityArchitectureSource,
  },
  SCIM: {
    definition: "A standard API for provisioning and managing user and group identity data across systems.",
    usefulWhen: "An organization needs automated account creation, updates or deactivation without waiting for a user to sign in.",
    tradeOff: "Both ends need compatible mappings and lifecycle behavior. Provisioning does not authenticate users or guarantee immediate session revocation.",
    question: "Who is the source of truth, which lifecycle changes must reach the app, and how quickly?",
    source: { title: "IETF: SCIM protocol", href: "https://www.rfc-editor.org/rfc/rfc7644.html" },
  },
  JIT: {
    definition: "Just-in-time provisioning creates a local user account when the user first signs in through a trusted identity connection.",
    usefulWhen: "You want to onboard users at sign-in rather than create their local accounts in advance.",
    tradeOff: "JIT alone does not handle offboarding when users stop signing in. It is not a replacement for a required SCIM lifecycle.",
    question: "Can accounts wait until first sign-in, and what separate process removes access afterward?",
    source: { title: "Okta: a JIT provisioning example", href: "https://help.okta.com/oie/en-us/content/topics/users-groups-profiles/usgp-add-users-jit.htm" },
  },
  GROUP_SYNC: {
    definition: "Keep group memberships in the application aligned with an external identity source.",
    usefulWhen: "Teams or directory groups determine access, and membership changes need to reach the application.",
    tradeOff: "Groups still need explicit permission and tenant mappings. A sign-in claim is not automatically continuous synchronization; SCIM support alone does not prove the required group behavior.",
    question: "Which groups are authoritative, how are they mapped, and how quickly must membership removals take effect?",
    source: { title: "IETF: SCIM group schema", href: "https://www.rfc-editor.org/rfc/rfc7643.html#section-4.2" },
  },
  MFA: {
    definition: "Multi-factor authentication uses more than one distinct factor, such as a password plus proof from a device.",
    usefulWhen: "Access needs stronger authentication than a single factor can provide.",
    tradeOff: "Enrollment and recovery need planning. Not all MFA is phishing-resistant; two passwords are still one factor type.",
    question: "Which users and actions need MFA? Record phishing resistance and step-up requirements separately in Context.",
    source: { title: "NIST: authenticator guidance", href: "https://pages.nist.gov/800-63-4/sp800-63b/authenticators/" },
  },
} as const satisfies Record<Capability, CapabilityGuidance>;
