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
    <div className="rounded-xl border border-brand-100 bg-brand-50 p-5" data-testid="escalation-notice">
      <p className="text-sm font-medium text-ink">This has been handed to a human support agent.</p>
      {escalation.reason ? <p className="mt-1 text-sm text-ink-muted">{escalation.reason}</p> : null}
      <p className="mt-2 text-sm text-ink-muted">
        {escalation.assignedTeam ? `Routed to ${escalation.assignedTeam}. ` : ""}
        You do not need to do anything else here — track progress on the ticket below.
      </p>
    </div>
  );
}
