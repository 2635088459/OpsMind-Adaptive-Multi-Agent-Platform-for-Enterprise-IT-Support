import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { getQueue } from "@/features/queue/api";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/support/tickets`;

const REAL_QUEUE_RESPONSE = {
  items: [
    {
      ticketId: "ticket-1", displayId: "INC-1", title: "VPN down", applicationCode: "VPN", status: "TRIAGED",
      priority: "HIGH", requesterRef: "req-1",
      assignment: { teamId: "network-support-team", agentId: null, unassigned: true },
      sla: { state: "ACTIVE", responseDueAt: "2026-01-01T13:00:00Z", resolutionDueAt: "2026-01-01T17:00:00Z", urgencyRank: 1 },
      createdAt: "2026-01-01T12:00:00Z", updatedAt: "2026-01-01T12:00:00Z", version: 0,
    },
  ],
  page: { limit: 25, hasMore: false, nextCursor: null, evaluationTime: "2026-01-01T12:05:00Z", consistency: "LIVE" },
  sort: { version: 1, fields: ["slaRank:asc"] },
  appliedFilters: {
    status: [], priority: [], applicationCode: [], assignedTeam: [], assignedAgent: null,
    unassignedOnly: false, slaState: [], createdFrom: null, createdTo: null,
  },
};

describe("queue api — SPEC-SC-003 real contract", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("getQueue returns the real SupportQueueResponse shape verbatim", async () => {
    server.use(http.get(BASE, () => HttpResponse.json(REAL_QUEUE_RESPONSE)));

    expect(await getQueue({})).toEqual(REAL_QUEUE_RESPONSE);
  });

  it("encodes multi-value filters as repeated query params", async () => {
    let receivedUrl = "";
    server.use(http.get(BASE, ({ request }) => {
      receivedUrl = request.url;
      return HttpResponse.json(REAL_QUEUE_RESPONSE);
    }));

    await getQueue({ status: ["TRIAGED", "IN_PROGRESS"], unassignedOnly: true });

    const url = new URL(receivedUrl);
    expect(url.searchParams.getAll("status")).toEqual(["TRIAGED", "IN_PROGRESS"]);
    expect(url.searchParams.get("unassignedOnly")).toBe("true");
  });
});
