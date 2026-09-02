import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";
import { useConfirmAction } from "@/features/conversation/useConfirmAction";
import { useDeclineAction } from "@/features/conversation/useDeclineAction";
import { useResumeConversation } from "@/features/conversation/useResumeConversation";
import { ProposedActionCard } from "@/features/conversation/ProposedActionCard";
import { EscalationNotice } from "@/features/conversation/EscalationNotice";
import { ActionExecutionStatus } from "@/features/conversation/ActionExecutionStatus";
import { MessageComposer } from "@/features/conversation/MessageComposer";
import { TicketStatusPanel } from "@/features/ticket/TicketStatusPanel";

/**
 * The real screen domain 09's employee lands on once authenticated — wires
 * together SPEC-EP-005/006/007/008/009/012/015's own pieces. No conversation-
 * history list here (SPEC-EP-015 §6 non-goals: out of scope for now).
 */
export function ConversationView() {
  useResumeConversation();
  const conversationId = useConversationStore((state) => state.conversationId);
  const transcript = useConversationStore((state) => state.transcript);
  const pendingAction = useConversationStore((state) => state.pendingAction);
  const escalation = useConversationStore((state) => state.escalation);
  const lastActionOutcome = useConversationStore((state) => state.lastActionOutcome);
  const turnState = useTurnStore((state) => state.state);

  const confirmAction = useConfirmAction(conversationId ?? "");
  const declineAction = useDeclineAction(conversationId ?? "");

  return (
    <div className="mx-auto flex min-h-screen max-w-2xl flex-col gap-4 px-6 py-10">
      <h1 className="text-xl font-semibold text-ink">OpsMind Support</h1>

      <div className="flex flex-1 flex-col gap-3">
        {transcript.map((entry) => (
          <div
            key={entry.id}
            className={entry.author === "employee" ? "self-end rounded-xl bg-brand-600 px-4 py-2 text-sm text-white" : "self-start rounded-xl bg-surface-muted px-4 py-2 text-sm text-ink"}
          >
            {entry.turn.kind === "text" ? entry.turn.text : entry.author === "employee" ? "" : null}
          </div>
        ))}

        {turnState === "AWAITING_CONFIRMATION" && pendingAction ? (
          <ProposedActionCard
            action={pendingAction}
            onConfirm={(actionId) => confirmAction.mutate(actionId)}
            onDecline={(actionId) => declineAction.mutate(actionId)}
            disabled={confirmAction.isPending || declineAction.isPending}
          />
        ) : null}

        {turnState === "ESCALATED" && escalation ? (
          <>
            <EscalationNotice escalation={escalation} />
            <TicketStatusPanel ticketId={escalation.ticketId} />
          </>
        ) : null}

        {turnState === "IDLE" && lastActionOutcome ? <ActionExecutionStatus outcome={lastActionOutcome} /> : null}
      </div>

      {turnState !== "ESCALATED" ? <MessageComposer /> : null}
    </div>
  );
}
