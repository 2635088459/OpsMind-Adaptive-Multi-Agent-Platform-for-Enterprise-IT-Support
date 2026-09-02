import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL, TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { AiLogPanel } from "@/features/ailog/AiLogPanel";

const TIMELINE_URL = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1/timeline`;
const AUDIT_URL = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/governance-audit-records`;

function timelineResponse(items: unknown[] = []) {
  return { ticketId: "ticket-1", displayId: "INC-1", viewType: "SUPPORT_PUBLIC_VIEW", items, page: { limit: 50, hasMore: false, nextCursor: null, snapshotAt: "x", consistency: "LIVE" }, sort: { version: 1, fields: [] } };
}

function timelineItem(occurredAt: string, summary: string) {
  return { itemId: crypto.randomUUID(), itemType: "STATUS_CHANGE", occurredAt, actor: { type: "AGENT", displayLabel: "AI", actorRef: null }, summary, content: null, metadata: {} };
}

describe("AiLogPanel — SPEC-SC-006/007/019", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("SPEC-SC-006: merges all 3 sources into one chronologically-ordered timeline", async () => {
    server.use(
      http.get(TIMELINE_URL, () => HttpResponse.json(timelineResponse([timelineItem("2026-01-01T00:10:00Z", "second event")]))),
      http.get(AUDIT_URL, () => HttpResponse.json([{ auditRecordId: "a1", action: "REQUESTED", actorId: "x", recordedAt: "2026-01-01T00:00:00Z", reason: "first event" }])),
    );

    renderWithProviders(<AiLogPanel ticketId="ticket-1" toolRequestId={null} />);

    const entries = await screen.findAllByTestId("ai-log-entry");
    expect(entries).toHaveLength(2);
    expect(entries[0]).toHaveTextContent("first event");
    expect(entries[1]).toHaveTextContent("second event");
  });

  it("SPEC-SC-007: one source failing renders the other two merged, with one unavailable banner and a working scoped retry", async () => {
    let auditCallCount = 0;
    server.use(
      http.get(TIMELINE_URL, () => HttpResponse.json(timelineResponse([timelineItem("2026-01-01T00:00:00Z", "real timeline event")]))),
      http.get(AUDIT_URL, () => {
        auditCallCount += 1;
        return auditCallCount === 1
          ? HttpResponse.json({ error: { code: "INTERNAL_ERROR", message: "boom" } }, { status: 500 })
          : HttpResponse.json([{ auditRecordId: "a1", action: "REQUESTED", actorId: "x", recordedAt: "2026-01-01T00:05:00Z", reason: "recovered event" }]);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AiLogPanel ticketId="ticket-1" toolRequestId={null} />);

    expect(await screen.findByTestId("source-unavailable")).toHaveTextContent(/governance audit data is temporarily unavailable/i);
    expect(await screen.findByTestId("ai-log-entry")).toHaveTextContent("real timeline event");

    await user.click(screen.getByRole("button", { name: /retry/i }));

    await waitFor(() => expect(screen.queryByTestId("source-unavailable")).not.toBeInTheDocument());
    expect(await screen.findAllByTestId("ai-log-entry")).toHaveLength(2);
  });

  it("SPEC-SC-019: a real 403 renders a distinct 'access restricted' notice with no retry button", async () => {
    server.use(
      http.get(TIMELINE_URL, () => HttpResponse.json(timelineResponse())),
      http.get(AUDIT_URL, () => HttpResponse.json({ error: { code: "FORBIDDEN", message: "denied" } }, { status: 403 })),
    );

    renderWithProviders(<AiLogPanel ticketId="ticket-1" toolRequestId={null} />);

    const banner = await screen.findByTestId("source-forbidden");
    expect(banner).toHaveTextContent(/don't have permission/i);
    expect(banner).not.toHaveTextContent(/temporarily unavailable/i);
    expect(screen.queryByRole("button", { name: /retry/i })).not.toBeInTheDocument();
  });

  it("all sources failing renders 3 distinct notices and an honest empty body, not a broken panel", async () => {
    server.use(
      http.get(TIMELINE_URL, () => HttpResponse.json({ error: { code: "INTERNAL_ERROR", message: "boom" } }, { status: 500 })),
      http.get(AUDIT_URL, () => HttpResponse.json({ error: { code: "INTERNAL_ERROR", message: "boom" } }, { status: 500 })),
    );

    renderWithProviders(<AiLogPanel ticketId="ticket-1" toolRequestId={null} />);

    expect(await screen.findAllByTestId("source-unavailable")).toHaveLength(2);
    expect(screen.getByTestId("ai-log-empty")).toBeInTheDocument();
  });

  it("an empty but fully successful result is a genuine empty state, distinct from any failure", async () => {
    server.use(
      http.get(TIMELINE_URL, () => HttpResponse.json(timelineResponse())),
      http.get(AUDIT_URL, () => HttpResponse.json([])),
    );

    renderWithProviders(<AiLogPanel ticketId="ticket-1" toolRequestId={null} />);

    expect(await screen.findByTestId("ai-log-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("source-unavailable")).not.toBeInTheDocument();
    expect(screen.queryByTestId("source-forbidden")).not.toBeInTheDocument();
  });
});
