// Profile claims are display metadata, never the workspace identity or a role grant.
export function identityProfile(claims: { sub: string; name?: unknown; email?: unknown; email_verified?: unknown },
                                userInfo: unknown): { displayName: string | null; email: string | null } {
  // openid-client validates this subject too; do not accept an unbound fallback at this boundary.
  const profile = userInfo && typeof userInfo === "object" && !Array.isArray(userInfo) &&
    "sub" in userInfo && userInfo.sub === claims.sub ? userInfo as Record<string, unknown> : null;
  const emailClaims = typeof claims.email === "string" ? claims : profile;
  return {
    displayName: typeof claims.name === "string" ? claims.name :
      typeof profile?.name === "string" ? profile.name : null,
    email: emailClaims?.email_verified === true && typeof emailClaims.email === "string" ? emailClaims.email : null,
  };
}
