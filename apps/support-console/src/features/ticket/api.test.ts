import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { getSupportTicket } from "@/features/ticket/api";

const DETAIL = {
  ticketId: "t-1", displayId: "INC-9", title: "VPN drops", description: "every few minutes",
  applicationCode: "VPN", source: "PORTAL", status: "TRIAGED", priority: "HIGH", requesterRef: "req-xyz",
  assignment: { teamId: "network-support", agentId: null, queue: "VPN" },
  resolutionCycle: { cycleNumber: 1, status: "OPEN" },
  sla: { state: "ACTIVE", policyId: "p-1", responseDueAt: null, resolutionDueAt: "2026-01-01T17:00:00Z" },
  createdAt: "2026-01-01T12:00:00Z", updatedAt: "2026-01-01T12:30:00Z", version: 3,
};

describe("support ticket detail api", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("returns the SupportTicketDetailResponse shape verbatim from GET /api/v1/tickets/{id}", async () => {
    server.use(http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/t-1`, () => HttpResponse.json(DETAIL)));
    expect(await getSupportTicket("t-1")).toEqual(DETAIL);
  });

  it("throws a typed ApiError on a 404", async () => {
    server.use(
      http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/missing`, () =>
        HttpResponse.json({ error: { code: "TICKET_NOT_FOUND", message: "no such ticket" } }, { status: 404 }),
      ),
    );
    await expect(getSupportTicket("missing")).rejects.toMatchObject({ status: 404, code: "TICKET_NOT_FOUND" });
  });
});
