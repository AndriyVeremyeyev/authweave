// A role in another ZITADEL project or organization is never an AuthWeave curator grant.
export type CuratorScope = { projectId: string; organizationId: string };

export const SENSITIVE_ACTION_REAUTH_SECONDS = 15 * 60;

export function curatorGrant(claims: unknown, scope: CuratorScope | null): CuratorScope | null {
  if (!scope || !claims || typeof claims !== "object" || Array.isArray(claims)) return null;
  const key = `urn:zitadel:iam:org:project:${scope.projectId}:roles`;
  if (!Object.hasOwn(claims, key)) return null;
  const roles = (claims as Record<string, unknown>)[key];
  if (!roles || typeof roles !== "object" || Array.isArray(roles)) return null;
  if (!Object.hasOwn(roles, "catalog_curator")) return null;
  const assignments = (roles as Record<string, unknown>).catalog_curator;
  if (!assignments || typeof assignments !== "object" || Array.isArray(assignments)) return null;
  if (!Object.hasOwn(assignments, scope.organizationId)) return null;
  const domain = (assignments as Record<string, unknown>)[scope.organizationId];
  return typeof domain === "string" && domain.length > 0 ? scope : null;
}

export function freshCuratorGrant(grant: CuratorScope | null | undefined, scope: CuratorScope | null,
                                  authenticatedAt: Date, now: Date = new Date()): boolean {
  if (!grant || !scope || grant.projectId !== scope.projectId ||
      grant.organizationId !== scope.organizationId) return false;
  const age = now.getTime() - authenticatedAt.getTime();
  return Number.isFinite(age) && age >= 0 && age <= SENSITIVE_ACTION_REAUTH_SECONDS * 1000;
}
