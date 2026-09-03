import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { TicketStatusPanel } from "@/features/ticket/TicketStatusPanel";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets`;

function ticketDetail(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    ticketId: "ticket-1", displayId: "INC-2048", title: "VPN disconnects", description: "...",
    applicationCode: "VPN", source: "PORTAL", status: "IN_PROGRESS", priority: "MEDIUM",
    createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-02T00:00:00Z", version: 3,
    sla: { state: "ON_TRACK", responseDueAt: null, resolutionDueAt: null },
    links: { self: "x", timeline: "y", messages: "z" },
    ...overrides,
  };
}

describe("TicketStatusPanel", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("SPEC-EP-013: renders the real fetched status/priority once loaded", async () => {
    server.use(http.get(`${BASE}/ticket-1`, () => HttpResponse.json(ticketDetail({ status: "IN_PROGRESS", priority: "HIGH" }))));

    renderWithProviders(<TicketStatusPanel ticketId="ticket-1" />);

    expect(await screen.findByTestId("ticket-status-value")).toHaveTextContent("IN_PROGRESS");
    expect(screen.getByTestId("ticket-priority-value")).toHaveTextContent("HIGH");
  });

  it("shows a retry affordance on a real fetch failure, never a stale/fabricated status", async () => {
    server.use(http.get(`${BASE}/ticket-1`, () => HttpResponse.json({ error: { code: "INTERNAL_ERROR", message: "boom" } }, { status: 500 })));

    renderWithProviders(<TicketStatusPanel ticketId="ticket-1" />);

    expect(await screen.findByTestId("ticket-status-error")).toBeInTheDocument();
  });

  it("SPEC-EP-016: confirming a RESOLVED ticket calls the real endpoint and re-renders the real CLOSED state", async () => {
    let getCallCount = 0;
    server.use(
      http.get(`${BASE}/ticket-1`, () => {
        getCallCount += 1;
        return HttpResponse.json(ticketDetail({ status: getCallCount === 1 ? "RESOLVED" : "CLOSED", version: getCallCount === 1 ? 3 : 4 }));
      }),
      http.post(`${BASE}/ticket-1/resolution-confirmation`, () => HttpResponse.json({ ticketId: "ticket-1", status: "CLOSED", version: 4 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<TicketStatusPanel ticketId="ticket-1" />);

    await screen.findByText(/did this fix your issue/i);
    await user.click(screen.getByRole("button", { name: /yes, this fixed it/i }));

    await waitFor(() => expect(screen.getByTestId("ticket-status-value")).toHaveTextContent("CLOSED"));
  });

  it("SPEC-EP-017: declining resolution reveals the reopen prompt, requires a real minimum-length note, and calls the real reopen endpoint", async () => {
    let getCallCount = 0;
    let receivedBody: unknown = null;
    server.use(
      http.get(`${BASE}/ticket-1`, () => {
        getCallCount += 1;
        return HttpResponse.json(ticketDetail({ status: getCallCount === 1 ? "RESOLVED" : "IN_PROGRESS", version: getCallCount === 1 ? 3 : 4 }));
      }),
      http.post(`${BASE}/ticket-1/reopen-request`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json({ ticketId: "ticket-1", status: "IN_PROGRESS", version: 4 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<TicketStatusPanel ticketId="ticket-1" />);

    await screen.findByText(/did this fix your issue/i);
    await user.click(screen.getByRole("button", { name: /no, still an issue/i }));

    const reopenButton = screen.getByRole("button", { name: /reopen ticket/i });
    expect(reopenButton).toBeDisabled();

    await user.type(screen.getByLabelText(/what's still wrong/i), "short");
    expect(reopenButton).toBeDisabled();

    await user.clear(screen.getByLabelText(/what's still wrong/i));
    await user.type(screen.getByLabelText(/what's still wrong/i), "Still disconnecting every few minutes.");
    expect(reopenButton).toBeEnabled();

    await user.click(reopenButton);

    await waitFor(() => expect(receivedBody).toMatchObject({ reopenReasonCode: "REQUESTER_REPORTED_NOT_FIXED" }));
    await waitFor(() => expect(screen.getByTestId("ticket-status-value")).toHaveTextContent("IN_PROGRESS"));
  });
});
