import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchGovernanceAuditEntries, fetchTimelineEntries, fetchToolRequestEntries } from "@/features/ailog/api";
import { ApiError } from "@/lib/apiError";
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
  const timelineQuery = useQuery({ queryKey: ["ai-log", "timeline", ticketId], queryFn: () => fetchTimelineEntries(ticketId) });
  const governanceQuery = useQuery({ queryKey: ["ai-log", "governance-audit", ticketId], queryFn: () => fetchGovernanceAuditEntries(ticketId) });
  const toolRequestQuery = useQuery({
    queryKey: ["ai-log", "tool-request", toolRequestId],
    queryFn: () => fetchToolRequestEntries(toolRequestId as string),
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
