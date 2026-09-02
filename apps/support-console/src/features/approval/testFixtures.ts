import type { ApprovalRequestResponse } from "@/features/approval/types";

/** Shared real-shape fixture for SPEC-SC-008/009 tests — one source of truth, not a copy per test file. */
export function approvalRequestFixture(overrides: Partial<ApprovalRequestResponse> = {}): ApprovalRequestResponse {
  return {
    approvalRequestId: "approval-1",
    requestKey: "key-1",
    sourceDomain: "agent-runtime",
    sourceRequestId: "src-1",
    requestHash: "hash-abc",
    ticketId: "ticket-1",
    workflowInstanceId: "workflow-1",
    toolRequestId: null,
    executorId: "agent-runtime-service",
    policyDecisionId: "decision-1",
    requestedBy: "agent-runtime-service",
    approvalType: "TICKET_ACTION",
    riskLevel: "HIGH",
    constraints: [{ type: "SCOPE", detail: "network-support-team only" }],
    status: "REQUESTED",
    expiresAt: "2026-09-02T12:00:00Z",
    createdAt: "2026-09-02T10:00:00Z",
    updatedAt: "2026-09-02T10:00:00Z",
    ...overrides,
  };
}
