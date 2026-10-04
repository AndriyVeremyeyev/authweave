import type { AuditCriterion } from "./auditability.ts";
import type { Criticality } from "./capabilities.ts";

export type AuditGuidance = {
  group: "events" | "records";
  example: string;
  limits: string;
  question: string;
};

// Educational copy for the existing partial policy, not defaults or provider evidence.
export const auditCriticalityGuidance = {
  UNKNOWN: "The requirement level is undecided. Selecting events does not decide it for you.",
  REQUIRED: "The selected criteria are hard requirements in the separate capability check. Empty scope or missing usable evidence remains unknown, not a match.",
  PREFERRED: "Record a preference, but the current auditability capability check does not score it or eliminate options for it.",
  NOT_REQUIRED: "This check imposes no auditability constraint. It does not establish safety, a logging exemption or compliance.",
  FORBIDDEN: "The intent needs clarification. This check does not interpret it as an instruction to disable logging.",
} as const satisfies Record<Criticality, string>;

export const auditGuidance = {
  AUTHENTICATION_SUCCESS_EVENTS: {
    group: "events",
    example: "Investigate a reported account takeover by asking whether the identity provider records successful sign-ins.",
    limits: "A successful sign-in event is not proof of what the user later did in the application. The selected clients, users and record details still need review.",
    question: "Which sign-in flows must leave a useful record, and what will your investigator need to establish?",
  },
  AUTHENTICATION_FAILURE_EVENTS: {
    group: "events",
    example: "Investigate a burst of failed sign-ins separately from successful access.",
    limits: "Having failure events does not establish alerting, attack detection or account protection. Record coverage and the response process need separate verification.",
    question: "Who will review failures, and which authentication flows must be covered?",
  },
  ADMINISTRATIVE_CHANGE_EVENTS: {
    group: "events",
    example: "Ask for records of changes to an identity-provider application's sign-in settings or administrator permissions.",
    limits: "This concerns identity-provider administration, not every business action inside your application or AuthWeave's own assessment history.",
    question: "Which identity configuration changes must be traceable, and who may view those records?",
  },
  PROVISIONING_CHANGE_EVENTS: {
    group: "events",
    example: "Ask whether identity account creation, updates and deactivation have provider-side records.",
    limits: "Provisioning logs do not imply SCIM support or prove that downstream access was removed. Lifecycle execution and its destination records remain separate.",
    question: "Which lifecycle transitions matter, and where will you verify their actual outcome?",
  },
  AUDIT_LOG_EXPORT: {
    group: "records",
    example: "Ask for provider-supported export so a security team can investigate identity events in its own logging system.",
    limits: "Export capability is not verified delivery, retrieval, complete event coverage or an operating monitoring pipeline. A receiving system's retention is not provider retention.",
    question: "Which records need to reach which destination, and who will test delivery and retrieval?",
  },
  AUDIT_LOG_RETENTION: {
    group: "records",
    example: "If an investigation may start weeks later, ask your team how long the provider's identity records must remain available.",
    limits: "This is a requested minimum at the provider. A configurable maximum, source publication date or external sink duration cannot supply its documented minimum.",
    question: "What duration does your scope need, and how will the team verify availability in the exact provider plan, region and configuration?",
  },
} as const satisfies Record<AuditCriterion, AuditGuidance>;

export const auditRetentionGuidance = {
  example: "For an illustrative request of 90 days, a documented provider minimum of 30 is below the request; 90 or more meets only the duration check. The example does not fill the input.",
  limits: "Missing documented duration remains unknown even if retention is supported. Meeting a minimum does not verify deployed storage, retrieval or compliance; longer is not automatically better.",
  question: "Agree the investigation window and applicable retention/disposal policy with the responsible reviewers. This form does not set a deletion deadline or configure the provider.",
} as const;

export const auditConceptReferences = [
  { title: "NIST SP 800-53: AU-2 event scope and AU-11 retention (PDF)", href: "https://csrc.nist.gov/CSRC/media/Projects/risk-management/800-53%20Downloads/800-53r5/SP_800-53_v5_1-derived-OSCAL.pdf" },
  { title: "OWASP: logging design and verification", href: "https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html" },
] as const;
