import { useQueue } from "@/features/queue/useQueue";
import { computeSlaDisplay, formatRemaining } from "@/features/queue/slaDisplay";
import type { QueueFilters } from "@/features/queue/types";

const PRIORITY_CLASS: Record<string, string> = {
  CRITICAL: "bg-danger/10 text-danger",
  HIGH: "bg-danger/10 text-danger",
  MEDIUM: "bg-surface-muted text-ink",
  LOW: "bg-surface-muted text-ink-muted",
  UNASSIGNED: "bg-surface-muted text-ink-muted",
};

const SLA_CLASS: Record<string, string> = {
  overdue: "text-danger font-medium",
  urgent: "text-danger",
  comfortable: "text-ink-muted",
  inactive: "text-ink-muted",
  missing: "text-ink-muted",
};

/** SPEC-SC-003 (queue table) + SPEC-SC-004 (severity/SLA display) + SPEC-SC-005 (kept fresh via useQueue's own polling). */
export function QueueTable({ filters }: { filters: QueueFilters }) {
  const { data, isLoading, isError, refetch } = useQueue(filters);

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4" data-testid="queue-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
        <div className="mt-2 h-4 w-1/2 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div className="rounded-xl border border-danger/30 bg-danger/5 p-4 text-sm text-ink" data-testid="queue-error">
        <p>Could not load the queue.</p>
        <button type="button" onClick={() => refetch()} className="mt-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted">
          Retry
        </button>
      </div>
    );
  }

  if (data.items.length === 0) {
    return (
      <div className="rounded-xl border border-border bg-surface p-8 text-center text-sm text-ink-muted" data-testid="queue-empty">
        No tickets match this view.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-border bg-surface" data-testid="queue-table">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-border text-xs uppercase tracking-wide text-ink-muted">
          <tr>
            <th className="px-4 py-2">Ticket</th>
            <th className="px-4 py-2">Status</th>
            <th className="px-4 py-2">Priority</th>
            <th className="px-4 py-2">Assignee</th>
            <th className="px-4 py-2">SLA</th>
            <th className="px-4 py-2">Updated</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((row) => {
            const sla = computeSlaDisplay(row.sla.state, row.sla.resolutionDueAt);
            return (
              <tr key={row.ticketId} className="border-b border-border last:border-0" data-testid="queue-row">
                <td className="px-4 py-2">
                  <div className="font-medium text-ink">{row.displayId}</div>
                  <div className="text-ink-muted">{row.title}</div>
                </td>
                <td className="px-4 py-2 text-ink-muted">{row.status}</td>
                <td className="px-4 py-2">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium uppercase ${PRIORITY_CLASS[row.priority] ?? "bg-surface-muted text-ink"}`} data-testid="priority-chip">
                    {row.priority}
                  </span>
                </td>
                <td className="px-4 py-2 text-ink-muted">{row.assignment.unassigned ? "Unassigned" : row.assignment.agentId}</td>
                <td className={`px-4 py-2 ${SLA_CLASS[sla.state]}`} data-testid="sla-display" data-sla-state={sla.state}>
                  {sla.state === "urgent" || sla.state === "overdue"
                    ? `${sla.state === "overdue" ? "Overdue by " : ""}${sla.remainingMs !== null ? formatRemaining(sla.remainingMs) : ""}`
                    : sla.state === "comfortable" && sla.remainingMs !== null
                      ? formatRemaining(sla.remainingMs)
                      : "—"}
                </td>
                <td className="px-4 py-2 text-ink-muted">{new Date(row.updatedAt).toLocaleString()}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
