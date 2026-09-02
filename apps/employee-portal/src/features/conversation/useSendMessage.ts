import { useMutation } from "@tanstack/react-query";
import { sendMessage, startConversation } from "@/features/conversation/api";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";
import type { TurnState } from "@/features/conversation/turnState";

interface SendMessageInput {
  text: string;
  attachmentRefs: string[];
}

/**
 * SPEC-EP-005: drives the full `IDLE -> SENDING -> AWAITING_AGENT -> {IDLE |
 * AWAITING_CONFIRMATION | ESCALATED}` turn per `03-state-machine` §3.1.
 * SPEC-EP-004's own conversation-creation trigger is folded in here (rather
 * than requiring every caller to check for a conversationId first) since
 * "on first message composition" is precisely this hook's own call site.
 *
 * SPEC-EP-018 (BI-EP-006): the draft is cleared only in `onSuccess` — never
 * eagerly in the composer's own click handler — so a real send failure
 * leaves the typed text preserved and retryable, not silently emptied
 * before the network call is even known to have failed.
 *
 * Retry-with-backoff before finally reaching `AGENT_UNAVAILABLE`
 * (`10-error-handling-and-reconciliation` §2.1) is out of this hook's own
 * scope per SPEC-EP-018 §6 (no backend retry/circuit-breaker logic; the
 * employee retries explicitly) — the failure path dispatches
 * `agentUnavailable` directly on the first infrastructure-level failure.
 */
export function useSendMessage() {
  const dispatch = useTurnStore((state) => state.dispatch);
  const conversationId = useConversationStore((state) => state.conversationId);
  const setConversationId = useConversationStore((state) => state.setConversationId);
  const appendEmployeeMessage = useConversationStore((state) => state.appendEmployeeMessage);
  const applyAgentTurn = useConversationStore((state) => state.applyAgentTurn);
  const setDraftText = useConversationStore((state) => state.setDraftText);
  const setLastFailedText = useConversationStore((state) => state.setLastFailedText);

  return useMutation({
    mutationFn: async ({ text, attachmentRefs }: SendMessageInput) => {
      // SPEC-EP-018's own retry re-enters this same mutationFn from
      // AGENT_UNAVAILABLE, not IDLE — a real bug caught by its own test:
      // dispatching "sendMessage" (IDLE's own entry event) while already in
      // SENDING (from the composer's own prior "retry" dispatch) is an
      // illegal transition that silently no-ops, leaving the machine stuck
      // in SENDING forever. Each starting state gets its own correct entry
      // event into SENDING instead.
      const entryState: TurnState = useTurnStore.getState().state;
      dispatch(entryState === "AGENT_UNAVAILABLE" ? "retry" : "sendMessage");
      appendEmployeeMessage(text);
      dispatch("requestSent");

      let id = conversationId;
      if (!id) {
        id = (await startConversation()).conversationId;
        setConversationId(id);
      }
      return sendMessage(id, text, attachmentRefs);
    },
    onSuccess: (turn) => {
      setDraftText("");
      setLastFailedText(null);
      applyAgentTurn(turn);
      if (turn.kind === "text") dispatch("receivedText");
      if (turn.kind === "proposedAction") dispatch("receivedProposedAction");
      if (turn.kind === "escalation") dispatch("receivedEscalation");
    },
    onError: (_error, variables) => {
      setLastFailedText(variables.text);
      dispatch("agentUnavailable");
    },
  });
}
