import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL, TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { fetchGovernanceAuditEntries, fetchTimelineEntries } from "@/features/ailog/api";

describe("ailog api — SPEC-SC-006 real contracts", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("fetchTimelineEntries maps the real SupportTimelineResponse shape", async () => {
    server.use(http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1/timeline`, () => HttpResponse.json({
      ticketId: "ticket-1", displayId: "INC-1", viewType: "SUPPORT_PUBLIC_VIEW",
      items: [{ itemId: "item-1", itemType: "STATUS_CHANGE", occurredAt: "2026-01-01T00:00:00Z", actor: { type: "AGENT", displayLabel: "AI Agent", actorRef: null }, summary: "Escalated to network-support-team", content: null, metadata: {} }],
      page: { limit: 50, hasMore: false, nextCursor: null, snapshotAt: "2026-01-01T00:00:00Z", consistency: "LIVE" },
      sort: { version: 1, fields: [] },
    })));

    const entries = await fetchTimelineEntries("ticket-1");

    expect(entries).toEqual([{ id: "item-1", source: "timeline", occurredAt: "2026-01-01T00:00:00Z", summary: "Escalated to network-support-team" }]);
  });

  it("fetchGovernanceAuditEntries maps the real GovernanceAuditRecordResponse shape", async () => {
    server.use(http.get(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/governance-audit-records`, ({ request }) => {
      expect(new URL(request.url).searchParams.get("ticketId")).toBe("ticket-1");
      return HttpResponse.json([{ auditRecordId: "audit-1", action: "REQUESTED", actorId: "agent-1", recordedAt: "2026-01-01T00:05:00Z", reason: "high-risk action requires approval" }]);
    }));

    const entries = await fetchGovernanceAuditEntries("ticket-1");

    expect(entries).toEqual([{ id: "audit-1", source: "governance-audit", occurredAt: "2026-01-01T00:05:00Z", summary: "high-risk action requires approval" }]);
  });
});
