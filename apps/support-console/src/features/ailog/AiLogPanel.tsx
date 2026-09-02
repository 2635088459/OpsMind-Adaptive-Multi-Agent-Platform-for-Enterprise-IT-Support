import { useAiLog } from "@/features/ailog/useAiLog";
import type { SourceName } from "@/features/ailog/types";

const SOURCE_LABEL: Record<SourceName, string> = {
  timeline: "Ticket timeline",
  governanceAudit: "Governance audit data",
  toolRequest: "Tool execution data",
};

/**
 * SPEC-SC-006: the unified AI-log timeline. SPEC-SC-007/SPEC-SC-019: each
 * source that isn't `ok` gets its own distinct, honestly-worded banner
 * (BI-SC-003 — a partial result is always labeled as partial, never
 * presented as if it were complete) — "unavailable" offers a scoped retry
 * (SPEC-SC-007 §9), "forbidden" deliberately does not (SPEC-SC-019 §9:
 * retrying cannot help a permission gap).
 */
export function AiLogPanel({ ticketId, toolRequestId }: { ticketId: string; toolRequestId: string | null }) {
  const { isLoading, entries, sourceStatus } = useAiLog(ticketId, toolRequestId);

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4" data-testid="ai-log-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  const degradedSources = (Object.entries(sourceStatus) as Array<[SourceName, (typeof sourceStatus)[SourceName]]>).filter(
    ([, source]) => source.status.kind !== "ok",
  );

  return (
    <div className="rounded-xl border border-border bg-surface p-4" data-testid="ai-log-panel">
      <h2 className="text-sm font-medium text-ink">AI processing log</h2>

      {degradedSources.map(([name, source]) => (
        <div
          key={name}
          className={`mt-2 rounded-md px-3 py-2 text-sm ${source.status.kind === "forbidden" ? "bg-surface-muted text-ink-muted" : "bg-danger/5 text-danger"}`}
          data-testid={source.status.kind === "forbidden" ? "source-forbidden" : "source-unavailable"}
          data-source={name}
        >
          {source.status.kind === "forbidden" ? (
            <p>You don&apos;t have permission to view {SOURCE_LABEL[name].toLowerCase()}.</p>
          ) : (
            <>
              <p>{SOURCE_LABEL[name]} is temporarily unavailable.</p>
              <button type="button" onClick={source.refetch} className="mt-1 rounded-md border border-border bg-surface px-2 py-1 text-xs font-medium hover:bg-surface-muted">
                Retry
              </button>
            </>
          )}
        </div>
      ))}

      {entries.length === 0 ? (
        <p className="mt-3 text-sm text-ink-muted" data-testid="ai-log-empty">
          No AI processing activity recorded for this ticket.
        </p>
      ) : (
        <ol className="mt-3 flex flex-col gap-2">
          {entries.map((entry) => (
            <li key={entry.id} className="text-sm text-ink" data-testid="ai-log-entry" data-source={entry.source}>
              <span className="text-xs text-ink-muted">{new Date(entry.occurredAt).toLocaleString()}</span> — {entry.summary}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
