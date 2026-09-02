/**
 * SPEC-SC-002: reads the real `realm_access.roles` claim already parsed by
 * `useAuthStore` — UI-convenience only, never a security boundary. A token
 * with unexpected/missing role claims defaults to the most restrictive
 * rendering (§16: `isAdmin` is false unless the role is explicitly present;
 * there is no "assume admin" fallback anywhere in this module).
 */
export function isSupportAgent(roles: string[]): boolean {
  return roles.includes("support_agent") || roles.includes("support_admin");
}

export function isSupportAdmin(roles: string[]): boolean {
  return roles.includes("support_admin");
}
