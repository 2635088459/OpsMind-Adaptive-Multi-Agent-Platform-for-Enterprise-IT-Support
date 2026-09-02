import { useEffect, useRef } from "react";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { saveDraft } from "@/features/session/draftPreservation";
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
  const lastKnownSubject = useAuthStore((state) => state.lastKnownSubject);
  const previousStatus = useRef(status);

  useEffect(() => {
    void checkSession();
  }, [checkSession]);

  // SPEC-EP-003: fires the instant `status` first reaches `session_expired`
  // — deliberately living here, not inside HomePage/ConversationView, since
  // THIS component is what actually decides to unmount them the same
  // render; a save effect living inside the tree being torn down would
  // race its own unmount.
  useEffect(() => {
    if (previousStatus.current !== "session_expired" && status === "session_expired") {
      const { conversationId, draftText } = useConversationStore.getState();
      if (conversationId && lastKnownSubject) {
        saveDraft(lastKnownSubject, conversationId, draftText);
      }
    }
    previousStatus.current = status;
  }, [status, lastKnownSubject]);

  if (status === "checking") {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <p className="text-sm text-ink-muted">Checking your session…</p>
      </div>
    );
  }

  // SPEC-EP-002: `token_refreshing` is a brief background attempt, not a
  // reason to kick an already-authenticated employee back to the login
  // screen — the composer/transcript stay mounted throughout.
  if (status === "authenticated" || status === "token_refreshing") {
    return <HomePage />;
  }

  return <LoginPage />;
}
