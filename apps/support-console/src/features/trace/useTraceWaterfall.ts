import { useQuery } from "@tanstack/react-query";
import { fetchTraceWaterfall } from "@/features/trace/api";

/** SPEC-SC-014: a read-only visualization — no polling (unlike the queue's own SPEC-SC-005), a completed trace's own spans never change. */
export function useTraceWaterfall(traceId: string) {
  return useQuery({ queryKey: ["trace-waterfall", traceId], queryFn: () => fetchTraceWaterfall(traceId) });
}
