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
import { useAuthStore } from "@/store/authStore";

/**
 * The real screen domain 09's employee lands on once authenticated — wires
 * together SPEC-EP-005/006/007/008/009/012/015's own pieces. No conversation-
 * history list here (SPEC-EP-015 §6 non-goals: out of scope for now).
 *
 * Visual design ported for real (2026-09-02) from the "Employee Portal"
 * mockup (an Artifact published 2026-09-01) — that was a static preview only,
 * never wired to this real app until now; every color/spacing choice below
 * traces back to that mockup's own `<style>` block, re-expressed as Tailwind
 * utility classes against src/index.css's own ported design tokens.
 */
export function ConversationView() {
  useResumeConversation();
  const conversationId = useConversationStore((state) => state.conversationId);
  const startedAt = useConversationStore((state) => state.startedAt);
  const transcript = useConversationStore((state) => state.transcript);
  const pendingAction = useConversationStore((state) => state.pendingAction);
  const escalation = useConversationStore((state) => state.escalation);
  const lastActionOutcome = useConversationStore((state) => state.lastActionOutcome);
  const turnState = useTurnStore((state) => state.state);
  const subject = useAuthStore((state) => state.lastKnownSubject);

  const confirmAction = useConfirmAction(conversationId ?? "");
  const declineAction = useDeclineAction(conversationId ?? "");

  const initials = (subject ?? "?").slice(0, 2).toUpperCase();

  return (
    <div className="mx-auto flex min-h-screen max-w-5xl flex-col px-5 py-7 sm:px-6">
      <header className="flex items-center justify-between gap-3 pb-5">
        <div className="flex items-center gap-2.5">
          <div className="flex size-8 shrink-0 items-center justify-center rounded-[9px] bg-brand-600 text-sm font-extrabold tracking-tight text-white">
            OM
          </div>
          <span className="text-base font-bold tracking-tight text-ink">OpsMind</span>
          <span className="border-l border-border pl-2 text-xs text-faint">IT Support</span>
        </div>
        {subject ? (
          <div className="flex items-center gap-2.5 text-sm text-ink-muted">
            <span>{subject}</span>
            <div className="flex size-[30px] items-center justify-center rounded-full bg-warm-soft text-xs font-bold text-warm-ink">
              {initials}
            </div>
          </div>
        ) : null}
      </header>

      <div className="grid flex-1 grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_300px]">
        <div className="flex min-h-[560px] flex-col overflow-hidden rounded-2xl border border-border bg-surface">
          <div className="flex items-center gap-2.5 border-b border-border px-5 py-4">
            <span className="inline-block size-2 rounded-full bg-ok shadow-[0_0_0_3px_var(--color-ok-soft)]" />
            <h1 className="text-[0.95rem] font-semibold text-ink">OpsMind Support</h1>
            {startedAt ? (
              <span className="ml-auto font-mono text-xs text-faint">
                {new Date(startedAt).toLocaleDateString(undefined, { month: "short", day: "numeric" })}{" "}
                {new Date(startedAt).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}
              </span>
            ) : null}
          </div>

          <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-5 py-6">
            {transcript.map((entry) => (
              <div key={entry.id} className={entry.author === "employee" ? "flex max-w-[78%] flex-row-reverse gap-2.5 self-end" : "flex max-w-[84%] gap-2.5 self-start"}>
                <div
                  className={
                    entry.author === "employee"
                      ? "mt-0.5 flex size-[26px] shrink-0 items-center justify-center rounded-full bg-warm-soft text-[11px] font-bold text-warm-ink"
                      : "mt-0.5 flex size-[26px] shrink-0 items-center justify-center rounded-full bg-brand-50 text-[11px] font-bold text-brand-700"
                  }
                >
                  {entry.author === "employee" ? initials : "OM"}
                </div>
                <div
                  className={
                    entry.author === "employee"
                      ? "rounded-tl-2xl rounded-tr-sm rounded-b-2xl bg-brand-600 px-4 py-3 text-sm text-white"
                      : "rounded-tl-sm rounded-tr-2xl rounded-b-2xl bg-brand-50 px-4 py-3 text-sm text-ink"
                  }
                >
                  {entry.turn.kind === "text" ? entry.turn.text : entry.author === "employee" ? "" : null}
                </div>
              </div>
            ))}

            {turnState === "SENDING" || turnState === "AWAITING_AGENT" ? (
              <div className="flex max-w-[84%] items-center gap-1.5 self-start text-xs text-faint" data-testid="agent-thinking-indicator">
                <span>Thinking</span>
                <span className="inline-flex gap-[3px]">
                  <span className="size-[5px] animate-[thinking-pulse_1.2s_ease-in-out_infinite] rounded-full bg-faint" />
                  <span className="size-[5px] animate-[thinking-pulse_1.2s_ease-in-out_infinite] rounded-full bg-faint [animation-delay:150ms]" />
                  <span className="size-[5px] animate-[thinking-pulse_1.2s_ease-in-out_infinite] rounded-full bg-faint [animation-delay:300ms]" />
                </span>
              </div>
            ) : null}

            {turnState === "AWAITING_CONFIRMATION" && pendingAction ? (
              <ProposedActionCard
                action={pendingAction}
                onConfirm={(actionId) => confirmAction.mutate(actionId)}
                onDecline={(actionId) => declineAction.mutate(actionId)}
                disabled={confirmAction.isPending || declineAction.isPending}
              />
            ) : null}

            {turnState === "ACTION_EXECUTING" ? (
              <div className="flex max-w-[84%] items-center gap-1.5 self-start text-xs text-faint" data-testid="agent-thinking-indicator">
                <span>Working on it</span>
                <span className="inline-flex gap-[3px]">
                  <span className="size-[5px] animate-[thinking-pulse_1.2s_ease-in-out_infinite] rounded-full bg-faint" />
                  <span className="size-[5px] animate-[thinking-pulse_1.2s_ease-in-out_infinite] rounded-full bg-faint [animation-delay:150ms]" />
                  <span className="size-[5px] animate-[thinking-pulse_1.2s_ease-in-out_infinite] rounded-full bg-faint [animation-delay:300ms]" />
                </span>
              </div>
            ) : null}

            {turnState === "ESCALATED" && escalation ? <EscalationNotice escalation={escalation} /> : null}

            {turnState === "IDLE" && lastActionOutcome ? <ActionExecutionStatus outcome={lastActionOutcome} /> : null}
          </div>

          {turnState !== "ESCALATED" ? (
            <div className="border-t border-border px-4 py-3.5">
              <MessageComposer />
            </div>
          ) : null}
        </div>

        {turnState === "ESCALATED" && escalation ? <TicketStatusPanel ticketId={escalation.ticketId} assignedTeam={escalation.assignedTeam} /> : null}
      </div>
    </div>
  );
}
