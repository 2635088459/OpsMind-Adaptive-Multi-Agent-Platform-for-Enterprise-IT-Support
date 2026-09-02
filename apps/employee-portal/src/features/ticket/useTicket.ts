import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { confirmResolution, getTicket, reopenTicket } from "@/features/ticket/api";

function ticketQueryKey(ticketId: string) {
  return ["ticket", ticketId] as const;
}

/** SPEC-EP-013: fetch-and-render the real ticket state; TanStack Query's own loading/error states back the panel's skeleton/retry affordance. */
export function useTicket(ticketId: string | null) {
  return useQuery({
    queryKey: ticketQueryKey(ticketId ?? ""),
    queryFn: () => getTicket(ticketId as string),
    enabled: ticketId !== null,
  });
}

/** SPEC-EP-016: on success, invalidates the ticket query so the panel re-renders the real, backend-confirmed CLOSED state — never an optimistic local guess (BI-EP-004). */
export function useConfirmResolution(ticketId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (expectedVersion: number) => confirmResolution(ticketId, expectedVersion),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketQueryKey(ticketId) }),
  });
}

/** SPEC-EP-017: same invalidate-and-refetch pattern as SPEC-EP-016. */
export function useReopenTicket(ticketId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ expectedVersion, reopenReason }: { expectedVersion: number; reopenReason: string }) =>
      reopenTicket(ticketId, expectedVersion, reopenReason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketQueryKey(ticketId) }),
  });
}
