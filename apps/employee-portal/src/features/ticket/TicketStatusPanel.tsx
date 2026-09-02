import { useState } from "react";
import { useConfirmResolution, useReopenTicket, useTicket } from "@/features/ticket/useTicket";
import { useTicketStatusStream } from "@/features/ticket/useTicketStatusStream";

const MIN_REOPEN_REASON_LENGTH = 10;

/**
 * SPEC-EP-013/016/017 together: the view-only status panel plus, once the
 * real backend reports `RESOLVED`, the "did this fix it?" affordance. View-
 * only otherwise (§6 non-goals: no other ticket-mutation affordance for the
 * employee) — BI-EP-004 throughout: every field rendered is the real
 * backend value from the last successful fetch, never a client guess, and
 * a mutation's own optimistic UI never runs ahead of the real invalidated
 * refetch (useTicket.ts's own comment).
 */
export function TicketStatusPanel({ ticketId }: { ticketId: string }) {
  const { data: ticket, isLoading, isError, refetch } = useTicket(ticketId);
  const confirmResolution = useConfirmResolution(ticketId);
  const reopenTicket = useReopenTicket(ticketId);
  const streamStatus = useTicketStatusStream(ticketId);
  const [reopening, setReopening] = useState(false);
  const [reopenReason, setReopenReason] = useState("");

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4" data-testid="ticket-status-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
        <div className="mt-2 h-4 w-1/2 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  if (isError || !ticket) {
    return (
      <div className="rounded-xl border border-danger/30 bg-danger/5 p-4 text-sm text-ink" data-testid="ticket-status-error">
        <p>Could not load the ticket status.</p>
        <button type="button" onClick={() => refetch()} className="mt-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted">
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className="rounded-xl border border-border bg-surface p-4 text-sm" data-testid="ticket-status-panel">
      <div className="flex items-center justify-between">
        <span className="font-medium text-ink">{ticket.displayId}</span>
        <span className="rounded-full bg-surface-muted px-2 py-0.5 text-xs font-medium uppercase tracking-wide text-ink-muted" data-testid="ticket-status-value">
          {ticket.status}
        </span>
      </div>
      <p className="mt-1 text-ink-muted">Priority: {ticket.priority}</p>
      <p className="mt-1 text-ink-muted">Last updated: {new Date(ticket.updatedAt).toLocaleString()}</p>

      {streamStatus === "reconnecting" ? (
        <p className="mt-1 text-xs text-ink-muted" data-testid="stream-reconnecting">
          Reconnecting live updates…
        </p>
      ) : null}
      {streamStatus === "failed" ? (
        <p className="mt-1 text-xs text-danger" data-testid="stream-failed">
          Unable to get live updates. Refresh to check for the latest status.
        </p>
      ) : null}

      {ticket.status === "RESOLVED" && !reopening ? (
        <div className="mt-3 border-t border-border pt-3">
          <p className="text-ink">Did this fix your issue?</p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              disabled={confirmResolution.isPending}
              onClick={() => confirmResolution.mutate(ticket.version)}
              className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-60"
            >
              Yes, this fixed it
            </button>
            <button
              type="button"
              disabled={confirmResolution.isPending}
              onClick={() => setReopening(true)}
              className="rounded-md border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-muted"
            >
              No, still an issue
            </button>
          </div>
        </div>
      ) : null}

      {reopening ? (
        <div className="mt-3 border-t border-border pt-3">
          <label htmlFor="reopen-reason" className="text-ink">
            What&apos;s still wrong?
          </label>
          <textarea
            id="reopen-reason"
            value={reopenReason}
            onChange={(event) => setReopenReason(event.target.value)}
            rows={3}
            className="mt-1 w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink"
          />
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              disabled={reopenTicket.isPending || reopenReason.trim().length < MIN_REOPEN_REASON_LENGTH}
              onClick={() => {
                reopenTicket.mutate(
                  { expectedVersion: ticket.version, reopenReason: reopenReason.trim() },
                  { onSuccess: () => setReopening(false) },
                );
              }}
              className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              Reopen ticket
            </button>
            <button type="button" onClick={() => setReopening(false)} className="rounded-md border border-border px-3 py-1.5 text-sm font-medium hover:bg-surface-muted">
              Cancel
            </button>
          </div>
          {reopenReason.trim().length > 0 && reopenReason.trim().length < MIN_REOPEN_REASON_LENGTH ? (
            <p className="mt-1 text-xs text-ink-muted">At least {MIN_REOPEN_REASON_LENGTH} characters.</p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
