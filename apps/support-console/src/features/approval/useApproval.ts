import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { decideApproval, getApprovalRequest } from "@/features/approval/api";
import type { DecideApprovalRequest } from "@/features/approval/types";

function approvalRequestKey(approvalRequestId: string) {
  return ["approval-request", approvalRequestId] as const;
}

/** SPEC-SC-008: the real pending-request detail, always the live backend value (BI-SC fidelity) — never locally cached-as-current. */
export function useApprovalRequest(approvalRequestId: string) {
  return useQuery({ queryKey: approvalRequestKey(approvalRequestId), queryFn: () => getApprovalRequest(approvalRequestId) });
}

/**
 * SPEC-SC-009: grant/deny. Deliberately invalidates the detail query
 * `onSettled` — success OR failure — rather than only on success: the
 * spec's own §9 requires that a 409 "already decided differently" response
 * (SPEC-PG-011's real 3-way replay check) re-render the ACTUAL current
 * decision rather than the one just attempted, and the only honest source
 * of that is a fresh `GET`, not the client's own guess at what the other
 * decision must have been.
 */
export function useDecideApproval(approvalRequestId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ decision, body }: { decision: "grant" | "deny"; body: DecideApprovalRequest }) =>
      decideApproval(approvalRequestId, decision, body),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: approvalRequestKey(approvalRequestId) });
    },
  });
}
