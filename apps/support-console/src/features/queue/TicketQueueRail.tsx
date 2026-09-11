import { Link } from "react-router";
import { useQueue } from "@/features/queue/useQueue";
import { computeSlaDisplay, formatRemaining } from "@/features/queue/slaDisplay";
import { agentLabel } from "@/features/catalog/catalog";
import type { QueueFilters } from "@/features/queue/types";

const STATUS_BADGE_CLASS: Record<string, string> = {
  NEW: "bg-danger-soft text-danger",
  TRIAGED: "bg-brand-50 text-brand-600",
  ASSIGNED: "bg-brand-50 text-brand-600",
  IN_PROGRESS: "bg-brand-50 text-brand-600",
  WAITING_FOR_APPROVAL: "bg-warn-soft text-warn",
  RESOLVED: "bg-ok-soft text-ok",
  CLOSED: "bg-ok-soft text-ok",
};

const PRIORITY_CLASS: Record<string, string> = {
  CRITICAL: "bg-danger-soft text-danger",
  HIGH: "bg-danger-soft text-danger",
  MEDIUM: "bg-surface-muted text-ink-muted",
  LOW: "bg-surface-muted text-ink-muted",
};

const SLA_CLASS: Record<string, string> = {
  overdue: "text-danger font-semibold",
  urgent: "text-warn font-medium",
  comfortable: "text-ink-muted",
  inactive: "text-ink-muted",
  missing: "text-ink-muted",
};

/**
 * Concept C ("Queue-first split view"): the queue as a persistent, always-
 * mounted rail rather than a page you navigate away from and back to.
 * `selectedTicketId` highlights whichever ticket `SupportDeskPage` is
 * currently showing; clicking a different row is plain `<Link>` navigation
 * to `/tickets/:id` — since both routes render through the same
 * `SupportDeskPage`, react-router reuses that component instance rather
 * than remounting it, so this rail itself never re-fetches or flickers when
 * you move between tickets (SPEC-SC-003/004/005 still own the real data:
 * this only changes how it's presented).
 */
export function TicketQueueRail({ filters, selectedTicketId }: { filters: QueueFilters; selectedTicketId?: string }) {
  const { data, isLoading, isError, refetch } = useQueue(filters);

  return (
    <aside className="flex h-full flex-col border-r border-border bg-surface" data-testid="queue-rail">
      <div className="border-b border-border px-4 py-3.5">
        <h2 className="text-sm font-semibold text-ink">Queue</h2>
      </div>

      {isLoading ? (
        <div className="p-4" data-testid="queue-skeleton">
          <div className="h-4 w-2/3 animate-pulse rounded bg-surface-muted" />
          <div className="mt-2 h-4 w-1/2 animate-pulse rounded bg-surface-muted" />
        </div>
      ) : isError || !data ? (
        <div className="p-4 text-sm text-ink" data-testid="queue-error">
          <p>Could not load the queue.</p>
          <button
            type="button"
            onClick={() => refetch()}
            className="mt-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted"
          >
            Retry
          </button>
        </div>
      ) : data.items.length === 0 ? (
        <div className="p-5 text-center text-sm text-ink-muted" data-testid="queue-empty">
          No tickets match this view.
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto" data-testid="queue-rows">
          {data.items.map((row) => {
            const sla = computeSlaDisplay(row.sla.state, row.sla.resolutionDueAt);
            const selected = row.ticketId === selectedTicketId;
            const slaText =
              sla.state === "urgent" || sla.state === "overdue"
                ? `${sla.state === "overdue" ? "overdue " : "due "}${sla.remainingMs !== null ? formatRemaining(sla.remainingMs) : ""}`
                : sla.state === "comfortable" && sla.remainingMs !== null
                  ? `due ${formatRemaining(sla.remainingMs)}`
                  : null;
            return (
              <Link
                key={row.ticketId}
                to={`/tickets/${row.ticketId}`}
                data-testid="queue-row"
                aria-current={selected ? "true" : undefined}
                className={`block border-b border-border px-4 py-3 no-underline ${
                  selected ? "border-l-[3px] border-l-brand-600 bg-brand-50 pl-[13px]" : "hover:bg-surface-muted"
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-sm font-semibold text-ink">{row.displayId}</span>
                  <span
                    className={`rounded-full px-2 py-0.5 text-[.68rem] font-semibold uppercase tracking-wide ${STATUS_BADGE_CLASS[row.status] ?? "bg-surface-muted text-ink-muted"}`}
                  >
                    {row.status}
                  </span>
                </div>
                <div className="mt-0.5 truncate text-sm text-ink-muted">{row.title}</div>
                <div className="mt-1.5 flex items-center gap-2 text-xs text-ink-muted">
                  <span
                    className={`rounded-full px-1.5 py-px text-[.65rem] font-semibold uppercase ${PRIORITY_CLASS[row.priority] ?? "bg-surface-muted text-ink-muted"}`}
                    data-testid="priority-chip"
                  >
                    {row.priority}
                  </span>
                  <span>{row.assignment.unassigned ? "Unassigned" : agentLabel(row.assignment.agentId)}</span>
                  {slaText ? (
                    <span className={`ml-auto ${SLA_CLASS[sla.state]}`} data-testid="sla-display" data-sla-state={sla.state}>
                      {slaText}
                    </span>
                  ) : (
                    <span className="ml-auto" data-testid="sla-display" data-sla-state={sla.state}>
                      —
                    </span>
                  )}
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </aside>
  );
}
