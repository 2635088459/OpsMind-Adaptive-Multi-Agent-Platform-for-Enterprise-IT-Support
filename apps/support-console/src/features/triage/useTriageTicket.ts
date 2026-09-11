import { useQueryClient } from "@tanstack/react-query";
import { triageTicket } from "@/features/triage/api";
import type { TriageInput } from "@/features/triage/types";
import { useVersionedMutation } from "@/features/ticketOps/useVersionedMutation";

/**
 * SPEC-SC-010, built on SPEC-SC-013's shared optimistic-concurrency wrapper.
 * Invalidates the shared ticket-detail query on success so the Assignment
 * and Status panels (each their own `useVersionedMutation` instance) pick up
 * the version this triage just advanced, instead of finding out only when
 * their own next submit conflicts — see `useVersionedMutation`'s own doc.
 */
export function useTriageTicket(ticketId: string, initialVersion: number) {
  const queryClient = useQueryClient();
  return useVersionedMutation(
    initialVersion,
    (expectedVersion, input: TriageInput) => triageTicket(ticketId, expectedVersion, crypto.randomUUID(), input),
    () => queryClient.invalidateQueries({ queryKey: ["support-ticket", ticketId] }),
  );
}
