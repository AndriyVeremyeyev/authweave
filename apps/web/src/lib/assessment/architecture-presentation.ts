import type { ArchitecturePatternPreflightSummary, ArchitecturePatternSummary } from "../auth/core-client.ts";

export const architectureStatusText = {
  MATCHES_CHECKED_REQUIREMENTS: "Matches the applied checks only",
  NEEDS_INFORMATION: "Needs more information",
  NOT_APPLICABLE: "Client type not selected",
} as const;
export const architectureTokenLocation = {
  SERVER_SIDE: "Application server", BROWSER: "Browser code", NATIVE_APP: "Native app", WORKLOAD: "Workload",
} as const;
export const architectureOutcomeText = {
  PASS: "Matches this check only", UNKNOWN: "Needs clarification", NOT_APPLIED: "Not applied — not a pass",
} as const;

const patternIds = ["BFF_SESSION", "SERVER_SIDE_SESSION", "SPA_CODE_PKCE", "NATIVE_CODE_PKCE", "M2M_CLIENT_CREDENTIALS"];
const checkedPaths = ["application.clients", "security.browserTokenExposureMinimization"] as const;
export type ArchitectureOverviewRow = {
  anchor: string;
  displayName: string;
  clientType: ArchitecturePatternSummary["clientType"];
  clientScope: "Selected client type" | "Client type not selected" | "Client types not recorded";
  tokenLocation: string;
  status: ArchitecturePatternSummary["status"];
  checks: { label: string; outcome: string }[];
};

/** Display projection only: reuse Core outcomes, never recompute applicability, rank or verify a design. */
export function architectureOverview(preview: ArchitecturePatternPreflightSummary): ArchitectureOverviewRow[] | null {
  if (preview.patterns.length !== patternIds.length || new Set(preview.patterns.map(p => p.patternId)).size !== patternIds.length) return null;
  const rows: ArchitectureOverviewRow[] = [];
  for (const [index, pattern] of preview.patterns.entries()) {
    if (!patternIds.includes(pattern.patternId) || !["BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"].includes(pattern.clientType) ||
        !Object.hasOwn(architectureTokenLocation, pattern.tokenHandling) || !Object.hasOwn(architectureStatusText, pattern.status) ||
        pattern.checks.length !== checkedPaths.length || new Set(pattern.checks.map(c => c.profilePath)).size !== checkedPaths.length ||
        pattern.checks.some(c => !checkedPaths.includes(c.profilePath as typeof checkedPaths[number]) || !Object.hasOwn(architectureOutcomeText, c.outcome))) return null;
    rows.push({ anchor: `architecture-pattern-${index}`, displayName: pattern.displayName, clientType: pattern.clientType,
      clientScope: preview.selectedClients.length === 0 ? "Client types not recorded"
        : preview.selectedClients.includes(pattern.clientType) ? "Selected client type" : "Client type not selected",
      tokenLocation: architectureTokenLocation[pattern.tokenHandling], status: pattern.status,
      checks: checkedPaths.map(path => ({ label: path === "application.clients" ? "Client selection" : "Browser token minimization",
        outcome: architectureOutcomeText[pattern.checks.find(c => c.profilePath === path)!.outcome] })) });
  }
  return rows;
}
