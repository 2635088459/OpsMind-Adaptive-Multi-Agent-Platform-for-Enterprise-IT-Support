import { assignTicket, reassignTicket, unassignTicket } from "@/features/assignment/api";
import { useVersionedMutation } from "@/features/ticketOps/useVersionedMutation";

export type AssignmentAction =
  | { mode: "assign" | "reassign"; assigneeId: string; reason: string }
  | { mode: "unassign"; reason: string };

/** SPEC-SC-011, built on SPEC-SC-013's shared optimistic-concurrency wrapper. One hook, 3 real endpoints — the assignee-picker decides which at submit time, never a 4th client-invented operation. */
export function useAssignTicket(ticketId: string, initialVersion: number) {
  return useVersionedMutation(initialVersion, (expectedVersion, action: AssignmentAction) => {
    const idempotencyKey = crypto.randomUUID();
    if (action.mode === "assign") {
      return assignTicket(ticketId, expectedVersion, idempotencyKey, { assigneeId: action.assigneeId, reason: action.reason });
    }
    if (action.mode === "reassign") {
      return reassignTicket(ticketId, expectedVersion, idempotencyKey, { assigneeId: action.assigneeId, reason: action.reason });
    }
    return unassignTicket(ticketId, expectedVersion, idempotencyKey, { reason: action.reason });
  });
}
