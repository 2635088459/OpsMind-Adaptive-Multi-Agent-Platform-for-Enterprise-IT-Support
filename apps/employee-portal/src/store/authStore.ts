import { create } from "zustand";
import { beginLogin, fetchBrowserSessionToken } from "@/lib/authClient";
import { decodeJwtPayload } from "@/lib/jwt";

/**
 * `03-state-machine` §3.3's own 3 named states (`UNAUTHENTICATED`,
 * `LOGIN_IN_PROGRESS`, `AUTHENTICATED`) plus one this spec's own text never
 * names: `checking`, for the real gap between "the app just (re)mounted" and
 * "we know which of the other 3 states is true" — reading `OPSMIND_SESSION`'s
 * presence is itself an async call (authClient.ts's own doc), so some state
 * must exist for that in-flight moment. A self-caught addition, not a
 * silent deviation: the alternative (defaulting straight to
 * `unauthenticated` while the check is in flight) would flash a real,
 * already-authenticated employee's login page for no reason on every
 * refresh. SPEC-EP-002 adds its own `token_refreshing`/`session_expired`
 * sub-machine layered on top of `authenticated`.
 */
export type AuthStatus = "checking" | "unauthenticated" | "login_in_progress" | "authenticated" | "token_refreshing" | "session_expired";

/**
 * How far before real expiry a refresh attempt fires. Deliberately
 * conservative (60s) — this backend has no real `OAuth2AuthorizedClientManager`
 * refresh-token-grant wired yet (BrowserSessionTokenView's own javadoc): a
 * "refresh" call today returns the SAME underlying Keycloak access token,
 * just re-read with its own real remaining lifetime — genuinely useful for
 * detecting the token has actually gone stale, not yet a real rotation.
 */
const REFRESH_LEAD_SECONDS = 60;

let refreshTimer: ReturnType<typeof setTimeout> | null = null;

interface AuthState {
  status: AuthStatus;
  accessToken: string | null;
  error: string | null;
  /**
   * SPEC-EP-003 (BI-EP-006 §4 "subject-prefixed keys"): kept across a
   * `session_expired` transition specifically so the draft-preservation
   * store still knows whose draft it just wrote, even once `accessToken`
   * itself is cleared — never re-derived from a stale token after that
   * point.
   */
  lastKnownSubject: string | null;
  checkSession: () => Promise<void>;
  login: () => void;
  refresh: () => Promise<void>;
}

function subjectFrom(accessToken: string): string | null {
  const claims = decodeJwtPayload(accessToken);
  return typeof claims?.sub === "string" ? claims.sub : null;
}

function clearScheduledRefresh() {
  if (refreshTimer !== null) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
}

export const useAuthStore = create<AuthState>((set, get) => {
  function scheduleRefresh(expiresInSeconds: number) {
    clearScheduledRefresh();
    const delayMs = Math.max(0, (expiresInSeconds - REFRESH_LEAD_SECONDS) * 1000);
    refreshTimer = setTimeout(() => {
      void get().refresh();
    }, delayMs);
  }

  return {
    status: "checking",
    accessToken: null,
    error: null,
    lastKnownSubject: null,

    checkSession: async () => {
      // Real bug found live 2026-09-03: user-access-authentication-service's own
      // OAuth2 login-failure redirect used to point at ITS OWN bare `/login?error`
      // path (a pure JSON API with no such page — a real failed login crashed
      // with a 500 instead of landing anywhere useful). Now redirected here
      // instead (`?login_error=true`), so a real failure — most commonly Keycloak's
      // own `authorization_request_not_found` on a stale/replayed callback —
      // surfaces as an honest, retryable message rather than either a backend
      // crash or a silent redirect to a blank login screen. The query param is
      // stripped immediately so a later refresh of this same tab doesn't keep
      // re-showing a failure that already happened.
      const url = new URL(window.location.href);
      if (url.searchParams.get("login_error") === "true") {
        url.searchParams.delete("login_error");
        window.history.replaceState(null, "", url.pathname + url.search + url.hash);
        set({ status: "unauthenticated", accessToken: null, error: "Sign-in failed. Please try again." });
        return;
      }

      try {
        const token = await fetchBrowserSessionToken();
        if (token === null) {
          set({ status: "unauthenticated", accessToken: null, error: null });
          return;
        }
        set({ status: "authenticated", accessToken: token.accessToken, error: null, lastKnownSubject: subjectFrom(token.accessToken) });
        scheduleRefresh(token.expiresInSeconds);
      } catch (cause) {
        // A real failure (network/5xx), not "please log in" — SPEC-EP-001 §16 only
        // describes the Keycloak-side failure path; a BFF-unreachable failure is
        // rendered the same honest way LoginPage already shows Keycloak failures,
        // rather than silently treated as unauthenticated.
        set({
          status: "unauthenticated",
          accessToken: null,
          error: cause instanceof Error ? cause.message : "Unable to reach the sign-in service.",
        });
      }
    },

    login: () => {
      set({ status: "login_in_progress", error: null });
      beginLogin();
    },

    /**
     * SPEC-EP-002: `AUTHENTICATED -> TOKEN_REFRESHING -> AUTHENTICATED |
     * SESSION_EXPIRED`. A real 401 (the session itself is gone) is one honest
     * failure signal; a 200 whose own `expiresInSeconds` has already reached
     * zero is the OTHER — this backend relays whatever token it already
     * holds even past that token's own real expiry (a known, documented
     * limitation, not a bug this store works around silently), so a
     * genuinely stale-but-200 response is treated the same as a 401 rather
     * than left `authenticated` with a token that will fail the very next
     * real API call.
     */
    refresh: async () => {
      set({ status: "token_refreshing" });
      try {
        const token = await fetchBrowserSessionToken();
        if (token === null || token.expiresInSeconds <= 0) {
          clearScheduledRefresh();
          set({ status: "session_expired", accessToken: null });
          return;
        }
        set({ status: "authenticated", accessToken: token.accessToken, error: null, lastKnownSubject: subjectFrom(token.accessToken) });
        scheduleRefresh(token.expiresInSeconds);
      } catch {
        clearScheduledRefresh();
        set({ status: "session_expired", accessToken: null });
      }
    },
  };
});
