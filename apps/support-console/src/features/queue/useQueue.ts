import { useQuery } from "@tanstack/react-query";
import { getQueue } from "@/features/queue/api";
import type { QueueFilters } from "@/features/queue/types";

/** A reasonable, deliberate starting point (not load-tested — no real production traffic exists yet to tune against), matching this project's own established "honest starting default, not a fabricated figure" convention. */
const REFETCH_INTERVAL_MS = 15_000;

function filtersKey(filters: QueueFilters) {
  return JSON.stringify(filters, Object.keys(filters).sort());
}

/**
 * SPEC-SC-003 + SPEC-SC-005 together: fetch-and-render the real queue, kept
 * fresh via TanStack Query's own native `refetchInterval` — deliberately
 * NOT a hand-rolled `setInterval` + Page Visibility listener: TanStack
 * Query's own default (`refetchIntervalInBackground: false`) already skips
 * the actual network refetch while the tab is hidden, which is exactly
 * SPEC-SC-005 §9's own "pause polling when the tab is backgrounded"
 * requirement, achieved by configuration rather than new client logic.
 * §16: a transient poll failure never clears `data` — TanStack Query's own
 * `data` stays at its last successful value while `isError` reflects the
 * latest attempt, so the existing view remains visible while a background
 * retry is in flight.
 */
export function useQueue(filters: QueueFilters) {
  return useQuery({
    queryKey: ["support-queue", filtersKey(filters)],
    queryFn: () => getQueue(filters),
    refetchInterval: REFETCH_INTERVAL_MS,
  });
}
