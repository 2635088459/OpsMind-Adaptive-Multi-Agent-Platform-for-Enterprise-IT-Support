import { useEffect } from "react";
import { useAuthStore } from "@/store/authStore";
import { LoginPage } from "@/pages/LoginPage";
import { QueuePage } from "@/pages/QueuePage";

/**
 * SPEC-SC-001: the console's own session gate — same real `03-state-machine`
 * pattern as domain 09's own `AuthGate` (see that app's own file for the
 * full reasoning on `checking`/`token_refreshing`). No draft-preservation
 * concern here (this domain has no SPEC-SC analogue to SPEC-EP-003 — an
 * agent's in-progress triage/assign forms are short-lived, not a chat
 * draft worth surviving a session expiry).
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

  if (status === "authenticated" || status === "token_refreshing") {
    return <QueuePage />;
  }

  return <LoginPage />;
}
