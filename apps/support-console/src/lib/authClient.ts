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

/** {@code passwordLogin} failed because the IdP rejected the grant — the honest "wrong username or password," never a system fault. */
export class InvalidCredentialsError extends Error {
  constructor() {
    super("Invalid username or password.");
  }
}

/**
 * The inline-form login this app's own `LoginPage` submits — a real
 * Resource Owner Password Credentials grant run entirely server-side by the
 * BFF's own `PasswordLoginController`, against this app's own real,
 * distinct Keycloak client registration ("support-console" — confirmed
 * live: a real human login as `support.agent` lands with
 * `realm_access.roles: ["support_agent"]` and the full ticket-write plus
 * `governance:audit:read` scope set). Replaces the top-level navigation to
 * Keycloak's own hosted login page domain 09's own sibling app also
 * replaced — no navigation, this app never talks to Keycloak directly
 * either.
 */
export async function passwordLogin(username: string, password: string): Promise<BrowserSessionToken> {
  const response = await fetch(`${BFF_BASE_URL}/api/v1/session/password-login`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", Accept: "application/json", traceparent: newTraceparent() },
    body: JSON.stringify({ registrationId: "support-console", username, password }),
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

/**
 * Ends the real session the BFF's own `BrowserLogoutController` established
 * — same mechanism domain 09's own sibling app uses (see its `authClient.ts`
 * for the full reasoning). Best-effort from this caller's side: a network
 * failure here still leaves the caller free to call `checkSession()` and
 * re-render the login screen.
 */
export async function logout(): Promise<void> {
  await fetch(`${BFF_BASE_URL}/api/v1/session/logout`, {
    method: "POST",
    credentials: "include",
    headers: { traceparent: newTraceparent() },
  });
}
