import { useParams } from "react-router";
import { TicketQueueRail } from "@/features/queue/TicketQueueRail";
import { TicketDetailPane } from "@/features/ticket/TicketDetailPane";

/**
 * Concept C ("Queue-first split view"), picked 2026-09-11 to replace the old
 * pattern (a queue page you left, then a separate ticket-detail page you
 * navigated back from). One route family, two leaves at the same
 * `SupportDeskPage` element (`/` and `/tickets/:ticketId`) — react-router
 * reuses this component instance across that navigation rather than
 * remounting it, so `TicketQueueRail` stays mounted (no re-fetch, no
 * flicker) while only the detail half swaps.
 */
export function SupportDeskPage() {
  const { ticketId } = useParams();

  return (
    <div
      className="grid h-[75vh] min-h-[560px] grid-cols-[300px_1fr] overflow-hidden rounded-xl border border-border shadow-sm"
      data-testid="support-desk"
    >
      <TicketQueueRail filters={{}} selectedTicketId={ticketId} />
      <div className="overflow-y-auto bg-surface-muted">
        {ticketId ? (
          <TicketDetailPane ticketId={ticketId} />
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-1.5 px-8 py-16 text-center" data-testid="support-desk-empty">
            <p className="font-serif text-lg italic text-ink">Pick a ticket from the queue</p>
            <p className="max-w-[40ch] text-sm text-ink-muted">Its triage, assignment, and status all open in this pane — the queue stays put on the left.</p>
          </div>
        )}
      </div>
    </div>
  );
}
