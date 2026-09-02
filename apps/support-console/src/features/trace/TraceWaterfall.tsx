import { useTraceWaterfall } from "@/features/trace/useTraceWaterfall";
import { buildWaterfallRows, traceTotalDurationMs } from "@/features/trace/waterfallLayout";
import { ApiError } from "@/lib/apiError";

const KIND_LABEL: Record<string, string> = {
  SPAN_KIND_SERVER: "server",
  SPAN_KIND_CLIENT: "client",
  SPAN_KIND_INTERNAL: "internal",
  SPAN_KIND_PRODUCER: "producer",
  SPAN_KIND_CONSUMER: "consumer",
};

/**
 * SPEC-SC-014: the trace-waterfall preview, sourced from the real,
 * authenticated Tempo proxy (see `TraceWaterfallController`'s own javadoc,
 * user-access-authentication-service). §16's own required honest states:
 * a genuine 404 ("trace no longer available", e.g. outside Tempo's
 * retention window) is rendered distinctly from a 503 (the trace store
 * itself unreachable, retryable) — never the same generic error, matching
 * this app's own established outage-vs-absence discipline (SPEC-SC-007/019).
 */
export function TraceWaterfall({ traceId }: { traceId: string }) {
  const { data, isLoading, isError, error, refetch } = useTraceWaterfall(traceId);

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4" data-testid="trace-waterfall-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  if (isError) {
    if (error instanceof ApiError && error.status === 404) {
      return (
        <div className="rounded-xl border border-border bg-surface p-4 text-sm text-ink-muted" data-testid="trace-not-found">
          This trace is no longer available (it may have aged out of the trace store's own retention window).
        </div>
      );
    }
    return (
      <div className="rounded-xl border border-border bg-danger/5 p-4 text-sm text-danger" data-testid="trace-unavailable">
        <p>The trace store is temporarily unavailable.</p>
        <button type="button" onClick={() => refetch()} className="mt-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium hover:bg-surface-muted">
          Retry
        </button>
      </div>
    );
  }

  if (!data || data.spans.length === 0) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4 text-sm text-ink-muted" data-testid="trace-empty">
        No spans were found for this trace.
      </div>
    );
  }

  const rows = buildWaterfallRows(data.spans);
  const totalMs = traceTotalDurationMs(data.spans);

  return (
    <div className="rounded-xl border border-border bg-surface p-4" data-testid="trace-waterfall">
      <h2 className="text-sm font-medium text-ink">Trace waterfall</h2>

      {data.unavailableTenants.length > 0 && (
        <p className="mt-2 rounded-md bg-surface-muted px-3 py-2 text-xs text-ink-muted" data-testid="trace-partial-outage">
          Some domains ({data.unavailableTenants.join(", ")}) could not be checked — this waterfall may be missing spans from them.
        </p>
      )}

      <div className="mt-3 flex flex-col gap-1">
        {rows.map(({ span, depth }) => {
          const leftPct = (span.startOffsetMs / totalMs) * 100;
          const widthPct = Math.max(0.5, (span.durationMs / totalMs) * 100);
          const isErrorSpan = span.statusCode === "STATUS_CODE_ERROR";
          return (
            <div key={span.spanId} className="flex items-center gap-2 text-xs" data-testid="waterfall-row" data-depth={depth}>
              <div className="w-48 shrink-0 truncate text-ink" style={{ paddingLeft: `${depth * 12}px` }} title={`${span.serviceName ?? "unknown"} · ${span.name}`}>
                <span className="text-ink-muted">{span.serviceName ?? "unknown"}</span> {span.name}
              </div>
              <div className="relative h-4 flex-1 rounded bg-surface-muted">
                <div
                  className={`absolute h-4 rounded ${isErrorSpan ? "bg-danger" : "bg-brand-500"}`}
                  style={{ left: `${leftPct}%`, width: `${widthPct}%` }}
                  title={`${span.durationMs}ms${KIND_LABEL[span.kind] ? ` · ${KIND_LABEL[span.kind]}` : ""}`}
                />
              </div>
              <div className="w-16 shrink-0 text-right text-ink-muted">{span.durationMs}ms</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
