import { describe, it, expect, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { QueueTable } from "@/features/queue/QueueTable";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/support/tickets`;

function queueRow(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    ticketId: "ticket-1", displayId: "INC-1", title: "VPN down", applicationCode: "VPN", status: "TRIAGED",
    priority: "HIGH", requesterRef: "req-1",
    assignment: { teamId: "network-support-team", agentId: null, unassigned: true },
    sla: { state: "ACTIVE", responseDueAt: null, resolutionDueAt: "2026-01-01T17:00:00Z", urgencyRank: 1 },
    createdAt: "2026-01-01T12:00:00Z", updatedAt: "2026-01-01T12:00:00Z", version: 0,
    ...overrides,
  };
}

function queueResponse(items: unknown[]) {
  return {
    items,
    page: { limit: 25, hasMore: false, nextCursor: null, evaluationTime: "2026-01-01T12:05:00Z", consistency: "LIVE" },
    sort: { version: 1, fields: ["slaRank:asc"] },
    appliedFilters: {
      status: [], priority: [], applicationCode: [], assignedTeam: [], assignedAgent: null,
      unassignedOnly: false, slaState: [], createdFrom: null, createdTo: null,
    },
  };
}

describe("QueueTable", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("SPEC-SC-003: renders real queue rows with their real fields", async () => {
    server.use(http.get(BASE, () => HttpResponse.json(queueResponse([queueRow()]))));

    renderWithProviders(<QueueTable filters={{}} />);

    expect(await screen.findByText("INC-1")).toBeInTheDocument();
    expect(screen.getByText("VPN down")).toBeInTheDocument();
    expect(screen.getByText("TRIAGED")).toBeInTheDocument();
  });

  it("shows a distinct empty state, not a loading/error look, for a real empty queue", async () => {
    server.use(http.get(BASE, () => HttpResponse.json(queueResponse([]))));

    renderWithProviders(<QueueTable filters={{}} />);

    expect(await screen.findByTestId("queue-empty")).toBeInTheDocument();
  });

  it("shows a retry affordance on a real fetch failure", async () => {
    server.use(http.get(BASE, () => HttpResponse.json({ error: { code: "INTERNAL_ERROR", message: "boom" } }, { status: 500 })));

    renderWithProviders(<QueueTable filters={{}} />);

    expect(await screen.findByTestId("queue-error")).toBeInTheDocument();
  });

  it("SPEC-SC-004: renders a distinct severity chip per priority level", async () => {
    server.use(http.get(BASE, () => HttpResponse.json(queueResponse([queueRow({ priority: "CRITICAL" })]))));

    renderWithProviders(<QueueTable filters={{}} />);

    expect(await screen.findByTestId("priority-chip")).toHaveTextContent("CRITICAL");
  });

  it("SPEC-SC-004: a ticket past its ACTIVE deadline renders overdue", async () => {
    server.use(http.get(BASE, () => HttpResponse.json(queueResponse([
      queueRow({ sla: { state: "ACTIVE", responseDueAt: null, resolutionDueAt: "2020-01-01T00:00:00Z", urgencyRank: 1 } }),
    ]))));

    renderWithProviders(<QueueTable filters={{}} />);

    const slaCell = await screen.findByTestId("sla-display");
    expect(slaCell).toHaveAttribute("data-sla-state", "overdue");
  });

  it("SPEC-SC-004: a missing SLA deadline renders gracefully, never a broken countdown", async () => {
    server.use(http.get(BASE, () => HttpResponse.json(queueResponse([
      queueRow({ sla: { state: "ACTIVE", responseDueAt: null, resolutionDueAt: null, urgencyRank: 1 } }),
    ]))));

    renderWithProviders(<QueueTable filters={{}} />);

    const slaCell = await screen.findByTestId("sla-display");
    expect(slaCell).toHaveAttribute("data-sla-state", "missing");
    expect(slaCell).toHaveTextContent("—");
  });
});
