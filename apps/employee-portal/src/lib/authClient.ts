import { BFF_BASE_URL } from "@/lib/env";
import { newTraceparent } from "@/lib/trace";

/**
 * The real access token relayed by user-access-authentication-service's own
 * BrowserSessionTokenController — never minted here, this app has no OIDC
 * client of its own (SPEC-EP-001 §6 Non-goals: "reuses 01-user-access-
 * authentication's already-real, already-verified flow").
 */
export interface BrowserSessionToken {
  accessToken: string;
  expiresInSeconds: number;
}

/**
 * `GET /oauth2/authorization/opsmind` is a real top-level navigation, not a
 * fetch — the browser must physically leave this app and land on Keycloak's
 * own hosted login UI, then get redirected back by Keycloak itself (never
 * through this app's own router). `window.location.assign` (not
 * `history.pushState`/`<Link>`) is the only correct way to trigger that.
 */
export function beginLogin(): void {
  window.location.assign(`${BFF_BASE_URL}/oauth2/authorization/opsmind`);
}

/**
 * SPEC-EP-001 §9/§10: the only real way to detect "is `OPSMIND_SESSION`
 * present and valid" from JS is to make an authenticated call and see
 * whether it succeeds — the cookie itself is `HttpOnly`, deliberately
 * unreadable by `document.cookie` (§14 Security). A 200 here doubles as both
 * that presence check AND the real access token this app forwards onward to
 * ticket-workflow-service/agent-runtime-service as its own `Authorization:
 * Bearer` header. `credentials: "include"` is required for the session
 * cookie to cross this app's own origin to the BFF's — see that service's
 * own CorsConfigurationSource/`same-site: none` cookie config.
 *
 * Returns `null` for a genuinely unauthenticated caller (401) — anything
 * else (network failure, 5xx) is thrown, since those are not "please log
 * in," they are "something is actually broken."
 *
 * SPEC-EP-023: a real bug found live during that spec's own audit — this
 * was the one real network call site in the app not going through
 * `authedFetch` (it runs before any access token exists at all) and so
 * never got a `traceparent`. "No network call in this app is untraceable"
 * is an absolute invariant, not conditioned on being authenticated yet.
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
