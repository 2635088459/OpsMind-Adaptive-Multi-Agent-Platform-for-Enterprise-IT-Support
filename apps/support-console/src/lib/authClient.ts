import { BFF_BASE_URL } from "@/lib/env";
import { newTraceparent } from "@/lib/trace";

/**
 * The real access token relayed by user-access-authentication-service's own
 * BrowserSessionTokenController — never minted here, this app has no OIDC
 * client of its own (SPEC-SC-001 §6 Non-goals). Reused directly from
 * domain 09's own proven BFF mechanism (BrowserSessionTokenController.
 * browserToken() reads the registration id off the principal itself, not a
 * hardcoded one) — the only difference is which registration this app logs
 * in through.
 */
export interface BrowserSessionToken {
  accessToken: string;
  expiresInSeconds: number;
}

/**
 * `GET /oauth2/authorization/support-console` — SPEC-SC-001's own real,
 * distinct Keycloak client registration (confirmed live 2026-09-02: a real
 * human login as `support.agent` lands with `realm_access.roles:
 * ["support_agent"]`, `actor_type: IT_SUPPORT`, and the full ticket-write
 * plus governance:audit:read scope set). A real top-level navigation, not a
 * fetch — same reasoning as domain 09's own `beginLogin`.
 */
export function beginLogin(): void {
  window.location.assign(`${BFF_BASE_URL}/oauth2/authorization/support-console`);
}

/**
 * SPEC-SC-001 §9/§10: identical mechanism to domain 09's own SPEC-EP-001 —
 * see that app's own `authClient.ts` for the full reasoning on why a 200
 * here doubles as both the session-presence check and the real token relay.
 */
export async function fetchBrowserSessionToken(): Promise<BrowserSessionToken | null> {
  const response = await fetch(`${BFF_BASE_URL}/api/v1/session/browser-token`, {
    method: "GET",
    credentials: "include",
    headers: { Accept: "application/json", traceparent: newTraceparent() },
  });

  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`browser-token request failed with status ${response.status}`);
  }

  const body = (await response.json()) as { accessToken: string; expiresInSeconds: number };
  return { accessToken: body.accessToken, expiresInSeconds: body.expiresInSeconds };
}
