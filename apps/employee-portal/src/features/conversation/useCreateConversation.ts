import { useMutation } from "@tanstack/react-query";
import { startConversation } from "@/features/conversation/api";
import { useConversationStore } from "@/features/conversation/conversationStore";

/**
 * SPEC-EP-004: called once, on first message composition, when no
 * `conversationId` exists yet. `POST /api/v1/conversations` is real
 * (SPEC-ARO-038) — no MSW mock needed in production; api.test.ts covers the
 * contract with one.
 */
export function useCreateConversation() {
  const setConversationId = useConversationStore((state) => state.setConversationId);

  return useMutation({
    mutationFn: startConversation,
    onSuccess: (conversation) => setConversationId(conversation.conversationId),
  });
}
