import { useMutation } from "@tanstack/react-query";
import { confirmAction } from "@/features/conversation/api";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";

/**
 * SPEC-EP-008: `AWAITING_CONFIRMATION -> ACTION_EXECUTING -> IDLE`. Renders
 * whichever real outcome SPEC-ARO-040 returns — `done`/`still-processing`/
 * `awaiting-approval` — honestly (BI-EP-005: never fabricate `done`); the
 * outcome itself is read back via `useConversationStore((s) =>
 * s.lastActionOutcome)` by the status-card component, not returned directly
 * from this hook's own call site, so a re-render after a page refresh still
 * shows the last real outcome.
 */
export function useConfirmAction(conversationId: string) {
  const dispatch = useTurnStore((state) => state.dispatch);
  const applyActionOutcome = useConversationStore((state) => state.applyActionOutcome);

  return useMutation({
    mutationFn: (actionId: string) => {
      dispatch("confirmClicked");
      return confirmAction(conversationId, actionId);
    },
    onSuccess: (outcome) => {
      applyActionOutcome(outcome);
      dispatch("actionOutcomeReceived");
    },
  });
}
