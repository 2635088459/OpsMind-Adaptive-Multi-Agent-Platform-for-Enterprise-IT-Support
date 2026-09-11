import { create } from "zustand";
import { InvalidCredentialsError, fetchBrowserSessionToken, logout, passwordLogin } from "@/lib/authClient";
import { decodeJwtPayload } from "@/lib/jwt";

/**
 * Identical shape to domain 09's own `AuthStatus` (see that app's own
 * authStore.ts for the full reasoning behind each state, including the
 * `checking` addition and the `token_refreshing`/`session_expired`
 * sub-machine) — this domain's own `03-state-machine` names the same
 * pattern for SPEC-SC-001.
 */
export type AuthStatus = "checking" | "unauthenticated" | "login_in_progress" | "authenticated" | "token_refreshing" | "session_expired";

const REFRESH_LEAD_SECONDS = 60;

let refreshTimer: ReturnType<typeof setTimeout> | null = null;

interface AuthState {
  status: AuthStatus;
  accessToken: string | null;
  error: string | null;
  lastKnownSubject: string | null;
  /**
   * SPEC-SC-002: the real `realm_access.roles` claim (support_agent/
   * support_admin) — UI-convenience only, never a security boundary (this
   * spec's own explicit framing, reiterated in every component that reads
   * this array). Empty (not null) when absent, so `.includes(...)` checks
   * never need a null guard at every call site.
   */
  roles: string[];
  checkSession: () => Promise<void>;
  loginWithPassword: (username: string, password: string) => Promise<void>;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
}

function clearScheduledRefresh() {
  if (refreshTimer !== null) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
}

function subjectFrom(accessToken: string): string | null {
  const claims = decodeJwtPayload(accessToken);
  return typeof claims?.sub === "string" ? claims.sub : null;
}

function rolesFrom(accessToken: string): string[] {
  const claims = decodeJwtPayload(accessToken);
  const realmAccess = claims?.realm_access;
  if (realmAccess && typeof realmAccess === "object" && "roles" in realmAccess) {
    const roles = (realmAccess as { roles: unknown }).roles;
    if (Array.isArray(roles)) return roles.filter((r): r is string => typeof r === "string");
  }
  return [];
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
    roles: [],

    checkSession: async () => {
      try {
        const token = await fetchBrowserSessionToken();
        if (token === null) {
          set({ status: "unauthenticated", accessToken: null, error: null, roles: [] });
          return;
        }
        set({
          status: "authenticated", accessToken: token.accessToken, error: null,
          lastKnownSubject: subjectFrom(token.accessToken), roles: rolesFrom(token.accessToken),
        });
        scheduleRefresh(token.expiresInSeconds);
      } catch (cause) {
        set({
          status: "unauthenticated", accessToken: null, roles: [],
          error: cause instanceof Error ? cause.message : "Unable to reach the sign-in service.",
        });
      }
    },

    /** The inline-form login `LoginPage` submits — see `authClient.passwordLogin`'s own javadoc. */
    loginWithPassword: async (username: string, password: string) => {
      set({ status: "login_in_progress", error: null });
      try {
        const token = await passwordLogin(username, password);
        set({
          status: "authenticated", accessToken: token.accessToken, error: null,
          lastKnownSubject: subjectFrom(token.accessToken), roles: rolesFrom(token.accessToken),
        });
        scheduleRefresh(token.expiresInSeconds);
      } catch (cause) {
        set({
          status: "unauthenticated", accessToken: null, roles: [],
          error: cause instanceof InvalidCredentialsError ? cause.message : "Unable to reach the sign-in service.",
        });
      }
    },

    refresh: async () => {
      set({ status: "token_refreshing" });
      try {
        const token = await fetchBrowserSessionToken();
        if (token === null || token.expiresInSeconds <= 0) {
          clearScheduledRefresh();
          set({ status: "session_expired", accessToken: null });
          return;
        }
        set({
          status: "authenticated", accessToken: token.accessToken, error: null,
          lastKnownSubject: subjectFrom(token.accessToken), roles: rolesFrom(token.accessToken),
        });
        scheduleRefresh(token.expiresInSeconds);
      } catch {
        clearScheduledRefresh();
        set({ status: "session_expired", accessToken: null });
      }
    },

    /** The real end of the session — see domain 09's own sibling app's `signOut` javadoc for the full reasoning. */
    signOut: async () => {
      clearScheduledRefresh();
      try {
        await logout();
      } catch {
        // Best-effort — a network failure never blocks the UI from clearing its own
        // local state and showing the login screen.
      }
      set({ status: "unauthenticated", accessToken: null, error: null, roles: [], lastKnownSubject: null });
    },
  };
});
