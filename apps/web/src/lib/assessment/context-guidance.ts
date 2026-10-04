import type { Criticality } from "./capabilities.ts";
import type { EvaluationContextValues, complianceScopeStatuses, complianceTargets } from "./evaluation-context.ts";

export type ContextGuidance = {
  definition: string;
  example: string;
  limits: string;
  question: string;
  reference: { title: string; href: string } | null;
};

type SecurityField = keyof Pick<EvaluationContextValues, "dataResidency" | "browserTokenExposureMinimization"
  | "phishingResistance" | "nonExportableKeys" | "stepUpAuthentication">;

// Describes the existing scoped policies; not new rules, provider evidence or defaults.
export const securityLevelGuidance = {
  UNKNOWN: "Undecided. Clarify this requirement before relying on its check; Unknown is not an exemption.",
  REQUIRED: "A hard requirement. Recording it does not prove that a provider supports it or that your deployed application enforces it.",
  PREFERRED: "A recorded preference. These security preflights do not score it or use it to eliminate options.",
  NOT_REQUIRED: "Removes this particular requirement. It does not establish safety, assurance or compliance.",
  FORBIDDEN: "Needs clarification in these security preflights. It is not interpreted as a request for weaker authentication, browser token exposure or a country denylist.",
} as const satisfies Record<Criticality, string>;

const authenticatorReference = {
  title: "NIST: authenticator guidance",
  href: "https://pages.nist.gov/800-63-4/sp800-63b/authenticators/",
};

export const contextSecurityGuidance = {
  dataResidency: {
    definition: "Limit where selected categories of identity data are stored, including their replicas and recovery copies.",
    example: "If profiles may be stored in DE but backups also use FR, allowing only DE does not satisfy a required backups check.",
    limits: "This check covers storage at rest, not processing locations, remote support access or international transfers. A region name alone is not storage evidence. Empty countries or categories stay unrecorded.",
    question: "Which categories and countries are actually in scope? Check primary storage, replicas and backups separately with the responsible reviewer.",
    reference: null,
  },
  browserTokenExposureMinimization: {
    definition: "Reduce the OAuth tokens that browser code can access. A backend-for-frontend (BFF) can keep tokens on the server while the browser uses a session cookie.",
    example: "For a browser application, compare server-side token handling with a SPA that receives tokens in browser code.",
    limits: "Minimization is not a blanket token ban or proof that a BFF is secure. Session, CSRF and malicious-script defenses still matter. This browser criterion does not assess native or machine-to-machine token storage.",
    question: "Which tokens may browser code access, and what exposure can your application accept? Required still leaves the pattern's prerequisites to check.",
    reference: { title: "IETF: browser-based applications (Internet-Draft)", href: "https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/27/" },
  },
  phishingResistance: {
    definition: "Authentication is cryptographically bound to the legitimate service so an impostor cannot simply relay the user's proof.",
    example: "A properly implemented WebAuthn sign-in can provide this property; a manually entered one-time code alone does not.",
    limits: "MFA alone does not establish phishing resistance. Enrollment, recovery and the deployed flow need separate review; this field does not certify an assurance level.",
    question: "Which human clients and user populations must use phishing-resistant authentication, rather than merely have it available?",
    reference: authenticatorReference,
  },
  nonExportableKeys: {
    definition: "Authentication keys stay inside a protected, hardware-backed authenticator rather than being copied out for use elsewhere.",
    example: "A hardware security key or protected device authenticator may be suitable; confirm the actual key protection and export behavior.",
    limits: "Supporting passkeys, or disabling synchronization alone, does not establish non-exportability. Device loss and recovery still need planning.",
    question: "Do the selected human flows require this property, and how will users recover access without bypassing the intended protection?",
    reference: authenticatorReference,
  },
  stepUpAuthentication: {
    definition: "Request and verify stronger authentication before a sensitive action, beyond the strength of the current session.",
    example: "Require a stronger sign-in check before changing an organization's administrators or another high-impact setting.",
    limits: "A second prompt alone is not proof of stronger authentication. Provider support does not verify that your application enforces the check before the action.",
    question: "Which actions need stronger authentication, and where will the application enforce and verify that requirement?",
    reference: { title: "NIST: authentication and step-up concepts", href: "https://pages.nist.gov/800-63-4/sp800-63b/introduction/" },
  },
} as const satisfies Record<SecurityField, ContextGuidance>;

export const complianceScopeGuidance = {
  UNKNOWN: "Scope has not been established. You may retain tentative labels, but they are not a completed review.",
  NONE_IDENTIFIED: "No targets were identified after reviewing this assessment's scope. The target list must be empty. This is not a legal exemption or a compliance finding.",
  TARGETS_IDENTIFIED: "Record at least one target label. Detailed obligations, covered services and supporting evidence still need investigation.",
} as const satisfies Record<(typeof complianceScopeStatuses)[number], string>;

export const complianceTargetGuidance = {
  SOC_2: {
    definition: "An examination and report on a service organization's controls relevant to selected trust services criteria.",
    reference: { title: "AICPA: SOC reports", href: "https://www.aicpa-cima.com/resources/landing/system-and-organization-controls-soc-suite-of-services" },
  },
  ISO_27001: {
    definition: "A standard defining requirements for an information security management system. Confirm the relevant organizational and service scope.",
    reference: { title: "ISO: ISO/IEC 27001", href: "https://www.iso.org/standard/27001" },
  },
  HIPAA: {
    definition: "US health-information privacy and security requirements for covered entities and business associates. Applicability requires a separate scope review.",
    reference: { title: "HHS: HIPAA overview", href: "https://www.hhs.gov/hipaa/for-professionals/index.html" },
  },
  FEDRAMP: {
    definition: "A US federal program for assessing and authorizing cloud products and services used by agencies. Check the exact service and agency use in scope.",
    reference: { title: "FedRAMP: program scope", href: "https://www.fedramp.gov/2026/scope/" },
  },
  GDPR: {
    definition: "The EU General Data Protection Regulation concerns personal-data processing. A storage-country choice alone does not establish compliance.",
    reference: { title: "European Commission: data protection framework", href: "https://commission.europa.eu/law/law-topic/data-protection/legal-framework-eu-data-protection_en" },
  },
  OTHER: {
    definition: "Another target that still needs a specific name, scope and responsible reviewer. This label does not define those details for you.",
    reference: null,
  },
} as const satisfies Record<(typeof complianceTargets)[number], {
  definition: string; reference: ContextGuidance["reference"];
}>;
