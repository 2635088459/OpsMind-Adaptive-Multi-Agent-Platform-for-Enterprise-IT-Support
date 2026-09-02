/**
 * Unverified, display-only decode of the access token's payload — mirrors
 * agent-runtime-service's own `interfaces/conversation/security.py` precedent
 * (domain 03, SPEC-ARO-038): this app never verifies a signature itself and
 * never uses a claim read this way for any authorization decision — the
 * token is opaque to this app except as a Bearer header it forwards
 * onward; every real check happens at the resource server. This exists only
 * so the UI can show "Signed in as {name}" without an extra network call.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const parts = token.split(".");
  if (parts.length !== 3) {
    return null;
  }
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), "=");
    const json = atob(padded);
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}
