/** Mirrors `com.opsmind.policygovernance.domain.approval.ApprovalStatus` exactly — see that enum's own javadoc for the full state machine. Only `REQUESTED` is decidable; every other value is final. */
export type ApprovalStatus =
  | "REQUESTED"
  | "APPROVED"
  | "DENIED"
  | "EXPIRED"
  | "CANCELLED"
  | "SUPERSEDED"
  | "USED"
  | "REVOKED";

/** Mirrors `ApprovalType`. */
export type ApprovalType =
  | "TOOL_EXECUTION"
  | "TICKET_ACTION"
  | "WORKFLOW_ACTION"
  | "POLICY_OVERRIDE"
  | "TICKET_SLA_EXCEPTION"
  | "TICKET_CLOSURE_OVERRIDE"
  | "TICKET_ESCALATION_EXCEPTION"
  | "GENERIC";

/** Mirrors `RiskLevel`. */
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export interface ConstraintDto {
  type: string;
  detail: string;
}

/**
 * Mirrors `ApprovalRequestResponse` (policy-approval-governance-service)
 * field-for-field, camelCase carrying over as-is — including `requestHash`,
 * the field SPEC-SC-009's own backend grounding found missing and had added
 * for real (see project memory) since grant/deny/cancel/use/revoke all
 * require the caller to echo it back.
 */
export interface ApprovalRequestResponse {
  approvalRequestId: string;
  requestKey: string;
  sourceDomain: string;
  sourceRequestId: string;
  requestHash: string;
  ticketId: string | null;
  workflowInstanceId: string | null;
  toolRequestId: string | null;
  executorId: string | null;
  policyDecisionId: string | null;
  requestedBy: string;
  approvalType: ApprovalType;
  riskLevel: RiskLevel;
  constraints: ConstraintDto[];
  status: ApprovalStatus;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors `DecideApprovalRequest` (the real body `:grant`/`:deny` both require). */
export interface DecideApprovalRequest {
  sourceRequestId: string;
  requestHash: string;
  reason: string;
  conditions: ConstraintDto[];
  commandIdempotencyKey: string;
  sessionId?: string;
  deviceId?: string;
  stepUpVerified: boolean;
}
