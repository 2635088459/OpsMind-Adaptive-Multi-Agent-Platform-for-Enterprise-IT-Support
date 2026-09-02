import { describe, it, expect, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";
import { AGENT_RUNTIME_BASE_URL, TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { ConversationView } from "@/features/conversation/ConversationView";

const BASE = `${AGENT_RUNTIME_BASE_URL}/api/v1/conversations`;
const TICKET_BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets`;

/**
 * E2E-EP-01/E2E-EP-02's own component-level equivalent (`14-testing-
 * strategy` §3.2): drives the real turnStore/conversationStore/hooks
 * together through MSW, exercising SPEC-EP-004/005/006/007/008/009/012 as
 * one coherent flow rather than each in isolation.
 */
describe("ConversationView — full turn flows", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    useConversationStore.getState().reset();
    useConversationStore.setState({ conversationId: "conv-1" });
    useTurnStore.setState({ state: "IDLE" });
  });

  it("send a message and render a plain text reply (E2E-EP-01)", async () => {
    server.use(http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({ type: "text", text: "Try restarting your VPN client." })));
    const user = userEvent.setup();
    renderWithProviders(<ConversationView />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "my vpn is down");
    await user.click(screen.getByRole("button", { name: /^send$/i }));

    expect(await screen.findByText("Try restarting your VPN client.")).toBeInTheDocument();
    expect(useTurnStore.getState().state).toBe("IDLE");
  });

  it("send a message, receive a proposed action, confirm it, and see a real 'done' outcome (E2E-EP-02)", async () => {
    server.use(
      http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({
        type: "proposedAction", action_id: "action-1", summary: "Reset your VPN client configuration.", risk_level: "LOW",
      })),
      http.post(`${BASE}/conv-1/actions/action-1/confirm`, () => HttpResponse.json({ outcome: "done" })),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConversationView />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "my vpn is broken");
    await user.click(screen.getByRole("button", { name: /^send$/i }));

    const card = await screen.findByTestId("proposed-action-card");
    expect(card).toHaveTextContent("Reset your VPN client configuration.");

    await user.click(screen.getByRole("button", { name: /confirm/i }));

    expect(await screen.findByTestId("action-execution-status")).toHaveTextContent(/completed successfully/i);
    expect(useTurnStore.getState().state).toBe("IDLE");
  });

  it("decline a proposed action produces zero execution and returns straight to IDLE", async () => {
    server.use(
      http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({
        type: "proposedAction", action_id: "action-1", summary: "Reset your VPN client configuration.", risk_level: "LOW",
      })),
      http.post(`${BASE}/conv-1/actions/action-1/decline`, () => HttpResponse.json({ outcome: "declined" })),
    );
    let confirmCallCount = 0;
    server.use(http.post(`${BASE}/conv-1/actions/action-1/confirm`, () => {
      confirmCallCount += 1;
      return HttpResponse.json({ outcome: "done" });
    }));

    const user = userEvent.setup();
    renderWithProviders(<ConversationView />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "my vpn is broken");
    await user.click(screen.getByRole("button", { name: /^send$/i }));
    await screen.findByTestId("proposed-action-card");

    await user.click(screen.getByRole("button", { name: /not now/i }));

    expect(await screen.findByTestId("action-execution-status")).toHaveTextContent(/chose not to proceed/i);
    expect(confirmCallCount).toBe(0);
    expect(useTurnStore.getState().state).toBe("IDLE");
  });

  it("an escalation response renders the notice and hides the composer", async () => {
    server.use(
      http.post(`${BASE}/conv-1/messages`, () => HttpResponse.json({
        type: "escalation", ticket_id: "ticket-1", display_id: "TCK-100", reason: "needs hardware replacement", assigned_team: "Desktop Support",
      })),
      // SPEC-EP-013: ConversationView renders TicketStatusPanel alongside the notice.
      http.get(`${TICKET_BASE}/ticket-1`, () => HttpResponse.json({
        ticketId: "ticket-1", displayId: "TCK-100", title: "x", description: "x", applicationCode: "HARDWARE", source: "PORTAL",
        status: "TRIAGED", priority: "HIGH", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z", version: 0,
        sla: { state: "ON_TRACK", responseDueAt: null, resolutionDueAt: null }, links: { self: "x", timeline: "y", messages: "z" },
      })),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConversationView />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "my laptop is dead");
    await user.click(screen.getByRole("button", { name: /^send$/i }));

    expect(await screen.findByTestId("escalation-notice")).toBeInTheDocument();
    expect(await screen.findByTestId("ticket-status-panel")).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/describe your issue/i)).not.toBeInTheDocument();
  });
});
