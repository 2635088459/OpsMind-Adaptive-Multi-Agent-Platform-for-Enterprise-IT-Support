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

/** `passwordLogin` failed because the IdP rejected the grant — the honest "wrong username or password," never a system fault. */
export class InvalidCredentialsError extends Error {
  constructor() {
    super("Invalid username or password.");
  }
}

/**
 * The inline-form login this app's own `LoginPage` submits — a real
 * Resource Owner Password Credentials grant run entirely server-side by
 * the BFF's own `PasswordLoginController` (this app never talks to
 * Keycloak directly, still true — SPEC-EP-001 §6's own "no OIDC client of
 * its own" non-goal). `registrationId` picks which of the two real
 * Keycloak client registrations to authenticate against: `"opsmind"` (this
 * app's own, least-privilege scope) or `"support-console"` (the Support
 * Console's — a different scope set, requested with the SAME just-verified
 * credentials when a signed-in user turns out to carry a support role; see
 * `authStore#loginWithPassword`).
 */
export async function passwordLogin(registrationId: "opsmind" | "support-console", username: string, password: string): Promise<BrowserSessionToken> {
  const response = await fetch(`${BFF_BASE_URL}/api/v1/session/password-login`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", Accept: "application/json", traceparent: newTraceparent() },
    body: JSON.stringify({ registrationId, username, password }),
  });

  if (response.status === 401) {
    throw new InvalidCredentialsError();
  }
  if (!response.ok) {
    throw new Error(`password-login request failed with status ${response.status}`);
  }

  const body = (await response.json()) as { accessToken: string; expiresInSeconds: number };
  return { accessToken: body.accessToken, expiresInSeconds: body.expiresInSeconds };
}

/**
 * Ends the real session the BFF's own `BrowserLogoutController` established —
 * revokes the domain session, forgets the authorized client, invalidates the
 * underlying `HttpSession`, and expires `OPSMIND_SESSION`. Lets someone sign
 * out and back in as a different account in the SAME browser window/tab,
 * without needing a separate private-browsing session (two private windows
 * from the same browser process share one cookie jar — not per-window
 * isolated — so that was never a real way to "switch accounts" anyway).
 * Best-effort from the caller's side too: a network failure here still
 * leaves the caller free to call `checkSession()` and re-render the login
 * screen — signing out of the UI never depends on this succeeding.
 */
export async function logout(): Promise<void> {
  await fetch(`${BFF_BASE_URL}/api/v1/session/logout`, {
    method: "POST",
    credentials: "include",
    headers: { traceparent: newTraceparent() },
  });
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
