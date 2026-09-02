import { triageTicket } from "@/features/triage/api";
import type { TriageInput } from "@/features/triage/types";
import { useVersionedMutation } from "@/features/ticketOps/useVersionedMutation";

/** SPEC-SC-010, built on SPEC-SC-013's shared optimistic-concurrency wrapper. */
export function useTriageTicket(ticketId: string, initialVersion: number) {
  return useVersionedMutation(initialVersion, (expectedVersion, input: TriageInput) =>
    triageTicket(ticketId, expectedVersion, crypto.randomUUID(), input),
  );
}
