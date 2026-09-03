import type { EscalationInfo } from "@/features/conversation/conversationStore";

interface EscalationNoticeProps {
  escalation: EscalationInfo;
}

/**
 * SPEC-EP-012: a distinct notice card, never a plain chat bubble (§9) — and
 * BI-EP-004/005: never claims the issue is resolved (it has only been
 * handed to a human) and never fabricates a resolution-time promise the
 * backend didn't actually provide. `reason`/`assignedTeam` render only when
 * the backend actually sent them. No client-side ticket-detail route exists
 * yet (SPEC-EP-013's own panel is rendered directly alongside this notice by
 * `ConversationView`, not linked to) — this component never promises a link
 * that would go nowhere.
 */
export function EscalationNotice({ escalation }: EscalationNoticeProps) {
  return (
    <div className="rounded-[10px] bg-warm-soft p-3.5 text-sm font-medium text-warm-ink" data-testid="escalation-notice">
      <p>
        This has been handed to a human support agent.
        {escalation.displayId ? (
          <>
            {" "}Ticket <span className="font-mono font-semibold">{escalation.displayId}</span> was created.
          </>
        ) : null}
      </p>
      {escalation.reason ? <p className="mt-1 font-normal">{escalation.reason}</p> : null}
      <p className="mt-2 font-normal">
        {escalation.assignedTeam ? `Routed to ${escalation.assignedTeam}. ` : ""}
        You do not need to do anything else here — track progress on the ticket {escalation.displayId ? "below" : "shortly"}.
      </p>
    </div>
  );
}
