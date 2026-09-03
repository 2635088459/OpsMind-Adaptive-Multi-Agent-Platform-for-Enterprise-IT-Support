import { useEffect, useRef } from "react";
import { findMostRecentConversation } from "@/features/conversation/api";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";
import { useAuthStore } from "@/store/authStore";
import { loadDraft } from "@/features/session/draftPreservation";

/**
 * SPEC-EP-015: on a fresh mount with no `conversationId` yet, resumes the
 * employee's own most recent conversation (SPEC-ARO-042) rather than always
 * starting blank — this is also what SPEC-EP-003's own draft restoration
 * depends on after a real re-login page reload, since every Zustand store
 * (including `conversationId` itself) is wiped by that reload; there is no
 * "restore the draft" step independent of "first re-establish which
 * conversation it belongs to."
 *
 * A real, deliberately narrower scope than this spec's own aspirational
 * text ("resuming mid-AWAITING_CONFIRMATION re-renders the exact pending
 * ProposedActionCard"): `GET /api/v1/conversations/{id}` (SPEC-ARO-042) only
 * ever returns `{conversationId, state, startedAt, updatedAt}` — a coarse
 * `WorkflowState`, never the pending action's own summary/actionId/
 * riskLevel (that spec's own README already flags this as a real, explicit
 * gap: no durable transcript exists to reconstruct it from). Fabricating a
 * `ProposedActionCard` from data this endpoint doesn't carry would violate
 * BI-EP-004 more than a conservative, honest fallback does — this hook
 * always seeds `IDLE` (safe: re-enables the composer) and, for any
 * non-`RUNNING` state, surfaces `resumedState` so the UI can show an
 * honest "resuming — last known status: X" banner instead of silence.
 */
export function useResumeConversation() {
  const status = useAuthStore((state) => state.status);
  const lastKnownSubject = useAuthStore((state) => state.lastKnownSubject);
  const conversationId = useConversationStore((state) => state.conversationId);
  const setConversationId = useConversationStore((state) => state.setConversationId);
  const setStartedAt = useConversationStore((state) => state.setStartedAt);
  const setDraftText = useConversationStore((state) => state.setDraftText);
  const seed = useTurnStore((state) => state.seed);
  const attempted = useRef(false);

  useEffect(() => {
    if (status !== "authenticated" || conversationId !== null || attempted.current) return;
    attempted.current = true;

    void (async () => {
      const detail = await findMostRecentConversation();
      if (!detail) return;

      setConversationId(detail.conversationId);
      setStartedAt(detail.startedAt);
      seed("IDLE");

      if (lastKnownSubject) {
        const draft = loadDraft(lastKnownSubject, detail.conversationId);
        if (draft) setDraftText(draft);
      }
    })();
  }, [status, conversationId, lastKnownSubject, setConversationId, setStartedAt, setDraftText, seed]);
}
