import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { useConversationStore } from "@/features/conversation/conversationStore";
import { useTurnStore } from "@/features/conversation/turnStore";
import { AGENT_RUNTIME_BASE_URL, TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { MessageComposer } from "@/features/conversation/MessageComposer";

const CONV_BASE = `${AGENT_RUNTIME_BASE_URL}/api/v1/conversations`;
const TICKET_BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets`;

/** SPEC-EP-018: a genuine backend outage on send, distinct from a normal escalation response. */
describe("MessageComposer — SPEC-EP-018 agent unavailable fallback", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    useConversationStore.getState().reset();
    useConversationStore.setState({ conversationId: "conv-1" });
    useTurnStore.setState({ state: "IDLE" });
  });

  it.each([
    ["timeout", () => HttpResponse.error()],
    ["5xx", () => HttpResponse.json({ error: { code: "INTERNAL_ERROR", message: "boom" } }, { status: 500 })],
    ["network error", () => HttpResponse.error()],
  ])("a %s send failure preserves the draft and offers retry + manual-ticket fallback", async (_label, handler) => {
    server.use(http.post(`${CONV_BASE}/conv-1/messages`, handler));
    const user = userEvent.setup();
    renderWithProviders(<MessageComposer />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "my vpn keeps disconnecting");
    await user.click(screen.getByRole("button", { name: /^send$/i }));

    expect(await screen.findByTestId("agent-unavailable-banner")).toBeInTheDocument();
    expect(screen.getByTestId("agent-unavailable-banner")).toHaveTextContent("my vpn keeps disconnecting");
    expect(screen.getByRole("button", { name: /retry/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /create a ticket manually instead/i })).toBeInTheDocument();
  });

  it("the manual-ticket-fallback button calls the real, already-existing ticket-creation endpoint", async () => {
    server.use(
      http.post(`${CONV_BASE}/conv-1/messages`, () => HttpResponse.error()),
      http.post(TICKET_BASE, async ({ request }) => {
        const body = (await request.json()) as { title: string };
        expect(body.title).toContain("my vpn keeps disconnecting");
        return HttpResponse.json({ ticketId: "ticket-1", displayId: "INC-9001" }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessageComposer />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "my vpn keeps disconnecting");
    await user.click(screen.getByRole("button", { name: /^send$/i }));
    await screen.findByTestId("agent-unavailable-banner");

    await user.click(screen.getByRole("button", { name: /create a ticket manually instead/i }));

    expect(await screen.findByTestId("manual-ticket-created")).toHaveTextContent("INC-9001");
  });

  it("retry resends the same preserved text", async () => {
    let attempt = 0;
    server.use(http.post(`${CONV_BASE}/conv-1/messages`, () => {
      attempt += 1;
      return attempt === 1 ? HttpResponse.error() : HttpResponse.json({ type: "text", text: "Got it, let's take a look." });
    }));
    const user = userEvent.setup();
    renderWithProviders(<MessageComposer />);

    await user.type(screen.getByPlaceholderText(/describe your issue/i), "my vpn keeps disconnecting");
    await user.click(screen.getByRole("button", { name: /^send$/i }));
    await screen.findByTestId("agent-unavailable-banner");

    await user.click(screen.getByRole("button", { name: /^retry$/i }));

    await waitFor(() => expect(useTurnStore.getState().state).toBe("IDLE"));
    expect(useConversationStore.getState().transcript.filter((e) => e.author === "employee")).toHaveLength(2);
  });
});

/** SPEC-EP-019: the employee's own device losing connectivity, distinct from a backend outage. */
describe("MessageComposer — SPEC-EP-019 offline degradation", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    useConversationStore.getState().reset();
    useConversationStore.setState({ conversationId: "conv-1", draftText: "my vpn keeps disconnecting" });
    useTurnStore.setState({ state: "IDLE" });
  });

  it("shows a persistent offline banner and disables send while offline, preserving the draft", async () => {
    renderWithProviders(<MessageComposer />);
    expect(screen.queryByTestId("offline-banner")).not.toBeInTheDocument();

    vi.spyOn(window, "fetch");
    window.dispatchEvent(new Event("offline"));

    expect(await screen.findByTestId("offline-banner")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^send$/i })).toBeDisabled();
    expect(screen.getByPlaceholderText(/describe your issue/i)).toHaveValue("my vpn keeps disconnecting");
  });

  it("re-enables sending once back online (browser event + a real successful heartbeat)", async () => {
    server.use(http.get(`${AGENT_RUNTIME_BASE_URL}/health`, () => HttpResponse.json({ status: "UP" })));
    renderWithProviders(<MessageComposer />);
    window.dispatchEvent(new Event("offline"));
    await screen.findByTestId("offline-banner");

    window.dispatchEvent(new Event("online"));

    await waitFor(() => expect(screen.queryByTestId("offline-banner")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: /^send$/i })).not.toBeDisabled();
  });
});
