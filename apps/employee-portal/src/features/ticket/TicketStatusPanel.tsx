import { useState } from "react";
import { useConfirmResolution, useReopenTicket, useTicket } from "@/features/ticket/useTicket";
import { useTicketStatusStream } from "@/features/ticket/useTicketStatusStream";

const MIN_REOPEN_REASON_LENGTH = 10;

type StepState = "done" | "active" | "pending";

const STEPS = ["Created", "Triaged", "In progress", "Resolved"] as const;

/**
 * A real, honest 4-bucket read of ticket-workflow-service's own much richer
 * `TicketStatus` enum (NEW/TRIAGED/ASSIGNED/IN_PROGRESS/TRIAGING/
 * INVESTIGATING/WAITING_FOR_USER/WAITING_FOR_APPROVAL/EXECUTING/VERIFYING/
 * RESOLVED/CLOSED/ESCALATED/FAILED/CANCELLED — confirmed by reading that
 * enum directly) — a coarse progress view, never a fabricated one: the exact
 * real `status` string is always shown alongside this (see the status pill
 * below), so nothing this bucketing simplifies away is ever hidden from the
 * employee.
 */
function stepStates(status: string): StepState[] {
  const pastTriage = status !== "NEW";
  const resolved = status === "RESOLVED" || status === "CLOSED";
  const closed = status === "CLOSED";
  return [
    "done", // a ticket that can be fetched here always exists
    pastTriage || resolved ? "done" : "active",
    resolved ? "done" : pastTriage ? "active" : "pending",
    closed ? "done" : status === "RESOLVED" ? "active" : "pending",
  ];
}

/**
 * SPEC-EP-013/016/017 together: the view-only status panel plus, once the
 * real backend reports `RESOLVED`, the "did this fix it?" affordance. View-
 * only otherwise (§6 non-goals: no other ticket-mutation affordance for the
 * employee) — BI-EP-004 throughout: every field rendered is the real
 * backend value from the last successful fetch, never a client guess, and
 * a mutation's own optimistic UI never runs ahead of the real invalidated
 * refetch (useTicket.ts's own comment).
 *
 * `assignedTeam` (optional) comes from the conversation's own real
 * escalation response, not this endpoint — ticket-workflow-service's
 * `EmployeeTicketDetailResponse` deliberately never discloses internal
 * assignment to the employee (see TicketDetail's own type comment), so this
 * is the only real source for that label.
 */
export function TicketStatusPanel({ ticketId, assignedTeam }: { ticketId: string; assignedTeam?: string | null }) {
  const { data: ticket, isLoading, isError, refetch } = useTicket(ticketId);
  const confirmResolution = useConfirmResolution(ticketId);
  const reopenTicket = useReopenTicket(ticketId);
  const streamStatus = useTicketStatusStream(ticketId);
  const [reopening, setReopening] = useState(false);
  const [reopenReason, setReopenReason] = useState("");

  if (isLoading) {
    return (
      <div className="rounded-2xl border border-border bg-surface p-5" data-testid="ticket-status-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
        <div className="mt-2 h-4 w-1/2 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  if (isError || !ticket) {
    return (
      <div className="rounded-2xl border border-danger/30 bg-danger/5 p-5 text-sm text-ink" data-testid="ticket-status-error">
        <p>Could not load the ticket status.</p>
        <button type="button" onClick={() => refetch()} className="mt-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted">
          Retry
        </button>
      </div>
    );
  }

  const steps = stepStates(ticket.status);

  return (
    <div className="sticky top-5 rounded-2xl border border-border bg-surface p-5 text-sm" data-testid="ticket-status-panel">
      <p className="font-mono text-[10.5px] font-semibold tracking-wide text-faint uppercase">Ticket progress</p>
      <p className="mt-2 font-mono text-lg font-semibold text-ink">{ticket.displayId}</p>
      <p className="mt-0.5 text-ink-muted">{ticket.title}</p>

      <div className="mt-3 flex items-center justify-between border-t border-border py-2 text-[0.8rem]">
        <span className="text-faint">Category</span>
        <span className="font-semibold text-ink">{ticket.applicationCode}</span>
      </div>
      {assignedTeam ? (
        <div className="flex items-center justify-between border-t border-border py-2 text-[0.8rem]">
          <span className="text-faint">Assigned team</span>
          <span className="font-semibold text-ink">{assignedTeam}</span>
        </div>
      ) : null}
      <div className="flex items-center justify-between border-t border-border py-2 text-[0.8rem]">
        <span className="text-faint">Status</span>
        <span className="rounded font-mono text-[10.5px] font-semibold tracking-wide text-ink uppercase" data-testid="ticket-status-value">
          {ticket.status}
        </span>
      </div>
      <div className="flex items-center justify-between border-t border-border py-2 text-[0.8rem]">
        <span className="text-faint">Priority</span>
        <span className="rounded bg-warm-soft px-2 py-0.5 font-mono text-[10.5px] font-semibold text-warm-ink" data-testid="ticket-priority-value">
          {ticket.priority}
        </span>
      </div>
      {ticket.sla.resolutionDueAt ? (
        <div className="flex items-center justify-between border-t border-border py-2 text-[0.8rem]">
          <span className="text-faint">Resolution due</span>
          <span className="rounded bg-warm-soft px-2 py-0.5 font-mono text-[10.5px] font-semibold text-warm-ink">
            {new Date(ticket.sla.resolutionDueAt).toLocaleString()}
          </span>
        </div>
      ) : null}
      <div className="flex items-center justify-between border-t border-border py-2 text-[0.8rem]">
        <span className="text-faint">Last updated</span>
        <span className="font-semibold text-ink">{new Date(ticket.updatedAt).toLocaleString()}</span>
      </div>

      <div className="mt-4 pt-1">
        {STEPS.map((label, index) => {
          const state = steps[index];
          return (
            <div key={label} className="relative flex gap-3 pb-5 last:pb-0">
              {index < STEPS.length - 1 ? <span className="absolute top-[22px] bottom-0 left-[10px] w-[1.5px] bg-border" aria-hidden="true" /> : null}
              <span
                className={
                  state === "done"
                    ? "z-10 flex size-[21px] shrink-0 items-center justify-center rounded-full bg-ok-soft text-[11px] font-bold text-ok"
                    : state === "active"
                      ? "z-10 flex size-[21px] shrink-0 items-center justify-center rounded-full bg-brand-600 text-[11px] font-bold text-white"
                      : "z-10 flex size-[21px] shrink-0 items-center justify-center rounded-full border-[1.5px] border-border bg-surface-muted text-[11px] font-bold text-faint"
                }
              >
                {state === "done" ? "✓" : index + 1}
              </span>
              <span className={state === "active" ? "text-[0.85rem] font-semibold text-brand-700" : "text-[0.85rem] font-semibold text-ink"}>{label}</span>
            </div>
          );
        })}
      </div>

      {streamStatus === "reconnecting" ? (
        <p className="mt-2 text-xs text-ink-muted" data-testid="stream-reconnecting">
          Reconnecting live updates…
        </p>
      ) : null}
      {streamStatus === "failed" ? (
        <p className="mt-2 text-xs text-danger" data-testid="stream-failed">
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
