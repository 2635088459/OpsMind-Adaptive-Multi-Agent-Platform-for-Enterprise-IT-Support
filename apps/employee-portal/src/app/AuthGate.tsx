import { useEffect } from "react";
import { useAuthStore } from "@/store/authStore";
import { LoginPage } from "@/pages/LoginPage";
import { HomePage } from "@/pages/HomePage";

/**
 * The real `03-state-machine` §3.3 gate: runs the session check exactly
 * once per mount (on every fresh load/refresh, including the moment the
 * browser lands back from Keycloak's real redirect — SPEC-EP-001 §9) and
 * renders purely off `AuthStatus`, never off a route path — this app has no
 * route that is reachable while unauthenticated other than the login
 * screen itself.
 */
export function AuthGate() {
  const status = useAuthStore((state) => state.status);
  const checkSession = useAuthStore((state) => state.checkSession);

  useEffect(() => {
    void checkSession();
  }, [checkSession]);

  if (status === "checking") {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <p className="text-sm text-ink-muted">Checking your session…</p>
      </div>
    );
  }

  if (status === "authenticated") {
    return <HomePage />;
  }

  return <LoginPage />;
}
