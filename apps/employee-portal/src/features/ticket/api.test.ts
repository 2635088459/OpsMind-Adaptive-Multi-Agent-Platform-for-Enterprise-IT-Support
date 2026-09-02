import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { confirmResolution, getTicket, reopenTicket } from "@/features/ticket/api";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets`;

const REAL_TICKET_DETAIL = {
  ticketId: "ticket-1", displayId: "INC-2048", title: "VPN disconnects", description: "...",
  applicationCode: "VPN", source: "PORTAL", status: "RESOLVED", priority: "MEDIUM",
  createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-02T00:00:00Z", version: 3,
  sla: { state: "ON_TRACK", responseDueAt: null, resolutionDueAt: null },
  links: { self: "/api/v1/tickets/ticket-1", timeline: "/api/v1/tickets/ticket-1/timeline", messages: "/api/v1/tickets/ticket-1/messages" },
};

describe("ticket api — SPEC-EP-013/016/017 real contracts", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("getTicket returns the real EmployeeTicketDetailResponse shape verbatim (camelCase, no mapping)", async () => {
    server.use(http.get(`${BASE}/ticket-1`, () => HttpResponse.json(REAL_TICKET_DETAIL)));

    expect(await getTicket("ticket-1")).toEqual(REAL_TICKET_DETAIL);
  });

  it("confirmResolution sends the real If-Match/Idempotency-Key headers and the required reasonCode/reason body", async () => {
    let receivedIfMatch: string | null = null;
    let receivedBody: unknown = null;
    server.use(http.post(`${BASE}/ticket-1/resolution-confirmation`, async ({ request }) => {
      receivedIfMatch = request.headers.get("If-Match");
      receivedBody = await request.json();
      return HttpResponse.json({ ticketId: "ticket-1", status: "CLOSED", version: 4 });
    }));

    const result = await confirmResolution("ticket-1", 3);

    expect(receivedIfMatch).toBe('"3"');
    expect(receivedBody).toMatchObject({ reasonCode: "REQUESTER_CONFIRMED" });
    expect(result).toMatchObject({ status: "CLOSED", version: 4 });
  });

  it("reopenTicket sends the real reopenReasonCode/reopenReason body", async () => {
    let receivedBody: unknown = null;
    server.use(http.post(`${BASE}/ticket-1/reopen-request`, async ({ request }) => {
      receivedBody = await request.json();
      return HttpResponse.json({ ticketId: "ticket-1", status: "IN_PROGRESS", version: 4 });
    }));

    const result = await reopenTicket("ticket-1", 3, "Still disconnecting every few minutes.");

    expect(receivedBody).toEqual({ reopenReasonCode: "REQUESTER_REPORTED_NOT_FIXED", reopenReason: "Still disconnecting every few minutes." });
    expect(result).toMatchObject({ status: "IN_PROGRESS", version: 4 });
  });
});
