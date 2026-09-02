import { useMutation } from "@tanstack/react-query";
import { declineAction } from "@/features/conversation/api";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";

/**
 * SPEC-EP-009: `AWAITING_CONFIRMATION -> IDLE` directly, zero execution
 * attempted. Unlike SPEC-EP-008, this never passes through
 * `ACTION_EXECUTING` — `declineClicked` is its own edge straight to `IDLE`
 * (turnState.ts), so a network failure here leaves the machine in
 * `AWAITING_CONFIRMATION` rather than a stuck `ACTION_EXECUTING` (§16: "the
 * card remains in AWAITING_CONFIRMATION until decline genuinely succeeds") —
 * achieved by only dispatching `declineClicked` in `onSuccess`, never
 * optimistically before the call.
 */
export function useDeclineAction(conversationId: string) {
  const dispatch = useTurnStore((state) => state.dispatch);
  const applyActionOutcome = useConversationStore((state) => state.applyActionOutcome);

  return useMutation({
    mutationFn: (actionId: string) => declineAction(conversationId, actionId),
    onSuccess: (outcome) => {
      applyActionOutcome(outcome);
      dispatch("declineClicked");
    },
  });
}
