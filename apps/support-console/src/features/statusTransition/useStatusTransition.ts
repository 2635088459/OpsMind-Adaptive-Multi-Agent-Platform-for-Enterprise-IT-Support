import { resolveTicket, transitionTicketStatus } from "@/features/statusTransition/api";
import type { ResolutionCode, ResolveTicketResponse, TransitionTarget, TransitionTicketStatusResponse } from "@/features/statusTransition/types";
import { useVersionedMutation } from "@/features/ticketOps/useVersionedMutation";

export type StatusAction =
  | { kind: "transition"; targetStatus: TransitionTarget; reason: string; approvalReference?: string }
  | { kind: "resolve"; resolutionCode: ResolutionCode; resolutionSummary: string };

/**
 * SPEC-SC-012, built on SPEC-SC-013's shared optimistic-concurrency
 * wrapper. One hook fronting 2 real, distinct endpoints — the generic
 * `status-transitions` endpoint (only `IN_PROGRESS`/`WAITING_FOR_APPROVAL`
 * reachable from it) and the dedicated `resolution` endpoint (the only
 * path to `RESOLVED`) — never inventing a single generic transition the
 * real backend doesn't actually expose.
 */
export function useStatusTransition(ticketId: string, initialVersion: number) {
  return useVersionedMutation<StatusAction, TransitionTicketStatusResponse | ResolveTicketResponse>(initialVersion, (expectedVersion, action) => {
    const idempotencyKey = crypto.randomUUID();
    if (action.kind === "transition") {
      return transitionTicketStatus(ticketId, expectedVersion, idempotencyKey, {
        targetStatus: action.targetStatus,
        reason: action.reason,
        approvalReference: action.approvalReference,
      });
    }
    return resolveTicket(ticketId, expectedVersion, idempotencyKey, {
      resolutionCode: action.resolutionCode,
      resolutionSummary: action.resolutionSummary,
    });
  });
}
