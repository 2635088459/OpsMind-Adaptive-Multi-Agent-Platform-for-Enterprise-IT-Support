import { create } from "zustand";
import { beginLogin, fetchBrowserSessionToken } from "@/lib/authClient";

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
 * refresh.
 */
export type AuthStatus = "checking" | "unauthenticated" | "login_in_progress" | "authenticated";

interface AuthState {
  status: AuthStatus;
  accessToken: string | null;
  error: string | null;
  checkSession: () => Promise<void>;
  login: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  status: "checking",
  accessToken: null,
  error: null,

  checkSession: async () => {
    try {
      const token = await fetchBrowserSessionToken();
      if (token === null) {
        set({ status: "unauthenticated", accessToken: null, error: null });
        return;
      }
      set({ status: "authenticated", accessToken: token.accessToken, error: null });
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
}));
