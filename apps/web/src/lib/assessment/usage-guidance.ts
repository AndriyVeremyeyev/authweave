import type { UsageMetric, UsageQuantity } from "./usage-planning.ts";

export type UsageGuidance = {
  unitLabel: string;
  example: string;
  limits: string;
  question: string;
};

// These are AuthWeave planning definitions, not vendor billing mappings or forecasts.
export const usageGuidance = {
  MONTHLY_ACTIVE_USERS: {
    unitLabel: "users / month",
    example: "If 100 distinct people each sign in ten times during your planning month, record 100 users, not 1,000 sign-ins.",
    limits: "Registered but inactive accounts do not supply this quantity. A provider may define a billable active user differently; this form does not map that definition.",
    question: "Which environment, people and month does the count cover, and is it a forecast or an observation?",
  },
  ENTERPRISE_SSO_CONNECTIONS: {
    unitLabel: "configured connections",
    example: "Two configured upstream enterprise IdP connections count as two, regardless of how many users sign in through them.",
    limits: "Customer organization count does not establish connection count. This is a configured inventory in the stated scope, not a monthly event total or proof of billable connections.",
    question: "How many upstream connections are configured or planned, and what does each connection serve?",
  },
  MONTHLY_M2M_TOKEN_ISSUANCES: {
    unitLabel: "token issuances / month",
    example: "In a fictional flow with one issued machine token used for 100 API calls, count one issuance, not 100 requests.",
    limits: "Do not substitute API traffic or the number of machine clients. Token lifetimes, reuse and renewals can change issuance volume; no behavior or provider limit is inferred here.",
    question: "Which machine flows issue tokens, and how will their issuance count be estimated or measured for the month?",
  },
  PEAK_HUMAN_LOGINS_PER_SECOND: {
    unitLabel: "successful logins / second",
    example: "If the busiest one-second interval contains 40 successful human logins, record 40, not the monthly total divided into an average.",
    limits: "This does not count failed attempts or machine tokens. A recorded peak does not verify capacity, latency, provider quotas or a load-test result.",
    question: "What burst or measurement supports this peak, and which environment and observation window does it cover?",
  },
} as const satisfies Record<UsageMetric, UsageGuidance>;

export const usageBasisGuidance = {
  UNKNOWN: "No quantity is recorded. Choose Unknown and leave Value blank; a missing answer is not zero.",
  ASSUMED: "Your estimate or forecast. Enter a whole non-negative number and describe its assumptions; it is not an observed measurement.",
  OBSERVED: "A measurement stated by the owner. Enter a whole non-negative number and describe its scope; AuthWeave does not independently verify it.",
} as const satisfies Record<"UNKNOWN" | UsageQuantity["basis"], string>;

export const usageScopeGuidance = {
  example: "For example: one fictional production tenant, monthly volumes expected during year one, connection inventory planned for launch and peak sign-ins during the launch window.",
  limits: "Do not silently mix development and production, unrelated months or average and peak traffic. The scope is your description, not an automatically verified environment or reporting period.",
  question: "Which environment and planning horizon apply, and where do the inventory date and peak window differ from the monthly volumes?",
} as const;

export const usageAssumptionGuidance = {
  example: "For example: pilot traffic only; the launch burst is an estimate; machine clients are not planned in this scope. These examples do not fill any field.",
  limits: "Use one field per distinct assumption. Partial inputs can be saved. The separate input check asks for at least one assumption when any quantity is Assumed; all-Observed inputs need no invented assumption.",
} as const;
