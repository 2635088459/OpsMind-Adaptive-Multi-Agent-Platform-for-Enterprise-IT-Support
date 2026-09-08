import { useQuery } from "@tanstack/react-query";
import { fetchTicketTraceId } from "@/features/trace/api";
import { GRAFANA_BASE_URL } from "@/lib/env";

/**
 * SPEC-SC-014 / UC-SC-05: an "Open trace in Tempo" deep link on the ticket
 * detail view, from the ticket's own latest trace id
 * (`GET /api/v1/tickets/{id}/trace`). Per UC-SC-05 the console does not
 * render the waterfall interaction here — that is the Observability page's
 * preview and, for real work, Tempo itself. Renders nothing until the id
 * resolves, and nothing at all if the ticket has no trace yet.
 */
export function TicketTraceLink({ ticketId }: { ticketId: string }) {
  const { data: traceId } = useQuery({
    queryKey: ["ticket-trace", ticketId],
    queryFn: () => fetchTicketTraceId(ticketId),
    enabled: ticketId.length > 0,
  });

  if (!traceId) {
    return null;
  }

  return (
    <a
      href={`${GRAFANA_BASE_URL}/explore?traceID=${encodeURIComponent(traceId)}`}
      target="_blank"
      rel="noreferrer"
      data-testid="ticket-trace-link"
      className="inline-flex items-center gap-1 rounded-md border border-border px-2.5 py-1 text-xs font-medium text-ink-muted hover:bg-surface-muted"
    >
      Open trace in Tempo ↗
    </a>
  );
}
