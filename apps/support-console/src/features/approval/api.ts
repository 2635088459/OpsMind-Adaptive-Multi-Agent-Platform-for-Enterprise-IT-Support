import { authedFetch, newIdempotencyKey } from "@/lib/httpClient";
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
 *
 * `X-Correlation-Id` is REQUIRED by every governance command endpoint
 * (GovernanceRequestContext#correlationId throws a 400 VALIDATION_ERROR
 * "X-Correlation-Id header is required" without it) — found live 2026-09-08;
 * the admin policy-draft/review/publish calls in this same app already send
 * it. The GET above needs no correlation id (it is a query, not a command).
 */
export async function decideApproval(
  approvalRequestId: string,
  decision: "grant" | "deny",
  body: DecideApprovalRequest,
): Promise<ApprovalRequestResponse> {
  const response = await authedFetch(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/${approvalRequestId}:${decision}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Correlation-Id": newIdempotencyKey() },
    idempotencyKey: body.commandIdempotencyKey,
    body: JSON.stringify(body),
  });
  return (await response.json()) as ApprovalRequestResponse;
}
