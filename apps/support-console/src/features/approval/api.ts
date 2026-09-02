import { authedFetch } from "@/lib/httpClient";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL } from "@/lib/env";
import type { ApprovalRequestResponse, DecideApprovalRequest } from "@/features/approval/types";

/** SPEC-SC-008: real, already-implemented `GET /api/v1/approval-requests/{id}` (ApprovalController#findById, SPEC-PG-010). */
export async function getApprovalRequest(approvalRequestId: string): Promise<ApprovalRequestResponse> {
  const response = await authedFetch(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/${approvalRequestId}`, {
    method: "GET",
  });
  return (await response.json()) as ApprovalRequestResponse;
}

/**
 * SPEC-SC-009: real, already-implemented `POST /api/v1/approval-requests/
 * {id}:grant` / `:deny` (ApprovalController#grant/#deny, SPEC-PG-011). The
 * colon is a literal path-segment character here, not a URL scheme
 * separator — Spring's own `@PostMapping` route is written the same way.
 */
export async function decideApproval(
  approvalRequestId: string,
  decision: "grant" | "deny",
  body: DecideApprovalRequest,
): Promise<ApprovalRequestResponse> {
  const response = await authedFetch(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/${approvalRequestId}:${decision}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    idempotencyKey: body.commandIdempotencyKey,
    body: JSON.stringify(body),
  });
  return (await response.json()) as ApprovalRequestResponse;
}
