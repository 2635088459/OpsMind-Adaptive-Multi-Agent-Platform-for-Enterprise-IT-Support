import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchTicketTraceId } from "@/features/trace/api";
import { GRAFANA_BASE_URL } from "@/lib/env";

/**
 * SPEC-SC-014 / UC-SC-05: the ticket's own latest OpenTelemetry trace id
 * (`GET /api/v1/tickets/{id}/trace`), shown as copyable text plus an "Open in
 * Tempo" deep link. Surfacing the raw id — not just burying it in the link —
 * so an operator can paste it into the Observability page's trace field or a
 * `traceID=` TraceQL query. Renders nothing until the id resolves, and nothing
 * at all if the ticket has no trace-bearing audit row yet.
 */
export function TicketTraceLink({ ticketId }: { ticketId: string }) {
  const { data: traceId } = useQuery({
    queryKey: ["ticket-trace", ticketId],
    queryFn: () => fetchTicketTraceId(ticketId),
    enabled: ticketId.length > 0,
  });
  const [copied, setCopied] = useState(false);

  if (!traceId) {
    return null;
  }

  async function copy() {
    try {
      await navigator.clipboard?.writeText(traceId as string);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard blocked (insecure context / permissions) — the id is still
      // visible for a manual select-copy, so nothing else to do here.
    }
  }

  return (
    <span className="inline-flex items-center gap-1.5 rounded-md border border-border bg-surface-muted px-2 py-1 text-xs">
      <span className="text-ink-muted">trace</span>
      <code className="font-mono text-ink" data-testid="ticket-trace-id" title={traceId}>
        {traceId}
      </code>
      <button
        type="button"
        onClick={copy}
        data-testid="ticket-trace-copy"
        className="rounded px-1.5 py-0.5 font-medium text-ink-muted hover:bg-surface hover:text-ink"
      >
        {copied ? "copied" : "copy"}
      </button>
      <a
        href={`${GRAFANA_BASE_URL}/explore?traceID=${encodeURIComponent(traceId)}`}
        target="_blank"
        rel="noreferrer"
        data-testid="ticket-trace-link"
        className="rounded px-1.5 py-0.5 font-medium text-ink-muted hover:bg-surface hover:text-ink"
      >
        Open in Tempo ↗
      </a>
    </span>
  );
}
