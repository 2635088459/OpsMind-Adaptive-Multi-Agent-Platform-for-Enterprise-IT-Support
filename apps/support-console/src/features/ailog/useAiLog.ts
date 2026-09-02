import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchGovernanceAuditEntries, fetchTimelineEntries, fetchToolRequestEntries } from "@/features/ailog/api";
import { ApiError } from "@/lib/apiError";
import { newTraceContext } from "@/lib/trace";
import type { AiLogEntry, SourceName, SourceStatus } from "@/features/ailog/types";

/**
 * SPEC-SC-006 + SPEC-SC-007 + SPEC-SC-019 built together (one real
 * mechanism, not 3 separate ones — same discipline as domain 09's own
 * SPEC-EP-014+020 pair). 3 independent `useQuery` calls, not one combined
 * aggregate query: SPEC-SC-007 §9 requires "a manual retry button scoped to
 * just that source" — only achievable if each source's own fetch/retry is
 * genuinely independent, never re-triggering the other two sources' own
 * (possibly-fine) data. The merge into one chronological list happens here,
 * client-side, over whichever sources currently have data — never via
 * `Promise.all`, which would let one rejection blank the whole panel (§9).
 *
 * SPEC-SC-020: a real finding self-caught during that spec's own audit —
 * `authedFetch` generates a brand-new, unrelated trace for every call by
 * default, so these 3 concurrent fan-out calls produced 3 disconnected
 * traces rather than one followable "load this ticket's AI log" operation.
 * One `TraceContext` is created per `ticketId` (stable across re-renders and
 * scoped retries alike) and its `traceparent()` is passed to all 3 sources,
 * so a real backend now sees one shared trace-id with 3 sibling spans.
 */
function classifyError(error: unknown): SourceStatus {
  if (error instanceof ApiError && error.status === 403) {
    return { kind: "forbidden" }; // SPEC-SC-019: retrying cannot help — distinct from an outage.
  }
  return { kind: "unavailable" }; // SPEC-SC-007: a real outage — retry may help.
}

export interface AiLogSourceQuery {
  status: SourceStatus;
  refetch: () => void;
}

export function useAiLog(ticketId: string, toolRequestId: string | null) {
  // eslint-disable-next-line react-hooks/exhaustive-deps -- ticketId isn't read inside newTraceContext(), but a new ticket is deliberately a new logical operation/trace; the lint rule can't see that intent from the callback body alone.
  const traceContext = useMemo(() => newTraceContext(), [ticketId]);

  const timelineQuery = useQuery({ queryKey: ["ai-log", "timeline", ticketId], queryFn: () => fetchTimelineEntries(ticketId, traceContext.traceparent()) });
  const governanceQuery = useQuery({ queryKey: ["ai-log", "governance-audit", ticketId], queryFn: () => fetchGovernanceAuditEntries(ticketId, traceContext.traceparent()) });
  const toolRequestQuery = useQuery({
    queryKey: ["ai-log", "tool-request", toolRequestId],
    queryFn: () => fetchToolRequestEntries(toolRequestId as string, traceContext.traceparent()),
    enabled: toolRequestId !== null,
  });

  const isLoading = timelineQuery.isLoading || governanceQuery.isLoading || (toolRequestId !== null && toolRequestQuery.isLoading);

  const entries = useMemo<AiLogEntry[]>(() => {
    const all = [...(timelineQuery.data ?? []), ...(governanceQuery.data ?? []), ...(toolRequestQuery.data ?? [])];
    return all.sort((a, b) => new Date(a.occurredAt).getTime() - new Date(b.occurredAt).getTime());
  }, [timelineQuery.data, governanceQuery.data, toolRequestQuery.data]);

  const sourceStatus: Record<SourceName, AiLogSourceQuery> = {
    timeline: {
      status: timelineQuery.isError ? classifyError(timelineQuery.error) : { kind: "ok" },
      refetch: () => void timelineQuery.refetch(),
    },
    governanceAudit: {
      status: governanceQuery.isError ? classifyError(governanceQuery.error) : { kind: "ok" },
      refetch: () => void governanceQuery.refetch(),
    },
    toolRequest: {
      status: toolRequestId === null ? { kind: "ok" } : toolRequestQuery.isError ? classifyError(toolRequestQuery.error) : { kind: "ok" },
      refetch: () => void toolRequestQuery.refetch(),
    },
  };

  return { isLoading, entries, sourceStatus };
}
