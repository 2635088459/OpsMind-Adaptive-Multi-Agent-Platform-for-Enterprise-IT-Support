import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL, TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { TicketApprovals } from "@/features/approval/TicketApprovals";

const TIMELINE_URL = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1/timeline`;
const AUDIT_URL = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/governance-audit-records`;
const APPROVAL_URL = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/appr-9`;

function timelineResponse() {
  return { ticketId: "ticket-1", displayId: "INC-1", viewType: "SUPPORT_PUBLIC_VIEW", items: [], page: { limit: 50, hasMore: false, nextCursor: null, snapshotAt: "x", consistency: "LIVE" }, sort: { version: 1, fields: [] } };
}

describe("TicketApprovals — SPEC-SC-008 (UC-SC-02 §3)", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    server.use(http.get(TIMELINE_URL, () => HttpResponse.json(timelineResponse())));
  });

  it("renders an ApprovalCard for a governance-audit record that carries an approvalRequestId", async () => {
    server.use(
      http.get(AUDIT_URL, () =>
        HttpResponse.json([
          { auditRecordId: "audit-1", action: "REQUESTED", actorId: "agent-1", recordedAt: "2026-01-01T00:05:00Z", reason: "high-risk action requires approval", approvalRequestId: "appr-9" },
        ]),
      ),
      http.get(APPROVAL_URL, () =>
        HttpResponse.json({
          approvalRequestId: "appr-9", requestKey: "k", sourceDomain: "agent-runtime", sourceRequestId: "sr-1", requestHash: "sha256:h",
          ticketId: "ticket-1", workflowInstanceId: null, toolRequestId: null, executorId: null, policyDecisionId: null,
          requestedBy: "agent-1", approvalType: "TICKET_ACTION", riskLevel: "HIGH", constraints: [],
          status: "REQUESTED", expiresAt: "2026-01-02T00:00:00Z", createdAt: "2026-01-01T00:05:00Z", updatedAt: "2026-01-01T00:05:00Z",
        }),
      ),
    );

    renderWithProviders(<TicketApprovals ticketId="ticket-1" />);

    await waitFor(() => expect(screen.getByTestId("approval-card")).toBeInTheDocument());
    expect(screen.getByTestId("ticket-approvals")).toBeInTheDocument();
  });

  it("shows the empty state when no governance-audit record links an approval", async () => {
    server.use(
      http.get(AUDIT_URL, () =>
        HttpResponse.json([
          { auditRecordId: "audit-2", action: "POLICY_EVALUATED", actorId: "agent-1", recordedAt: "2026-01-01T00:04:00Z", reason: null, approvalRequestId: null },
        ]),
      ),
    );

    renderWithProviders(<TicketApprovals ticketId="ticket-1" />);

    await waitFor(() => expect(screen.getByTestId("ticket-approvals-empty")).toBeInTheDocument());
    expect(screen.queryByTestId("approval-card")).not.toBeInTheDocument();
  });
});
