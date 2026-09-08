import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { usePortalViewStore } from "@/store/portalViewStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { NewTicketForm } from "@/features/ticket/NewTicketForm";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets`;

const CREATED = {
  ticketId: "11111111-1111-1111-1111-111111111111",
  displayId: "INC-3300",
  status: "NEW",
  createdAt: "2026-09-07T10:00:00Z",
  version: 0,
  resolutionCycleId: "22222222-2222-2222-2222-222222222222",
};

describe("NewTicketForm — deliberate manual ticket, straight to a human", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
    usePortalViewStore.setState({ view: "newTicket" });
  });

  it("keeps submit disabled until both summary and details are filled", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewTicketForm />);

    const submit = screen.getByRole("button", { name: /submit ticket/i });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/summary/i), "VPN keeps dropping");
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/details/i), "Every few minutes on the office wifi.");
    expect(submit).toBeEnabled();
  });

  it("posts the chosen category and pinned PORTAL source, then shows the real display id", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(http.post(BASE, async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(CREATED, { status: 201 });
    }));

    const user = userEvent.setup();
    renderWithProviders(<NewTicketForm />);

    await user.type(screen.getByLabelText(/summary/i), "VPN keeps dropping");
    await user.selectOptions(screen.getByLabelText(/category/i), "VPN");
    await user.type(screen.getByLabelText(/details/i), "Every few minutes on the office wifi.");
    await user.click(screen.getByRole("button", { name: /submit ticket/i }));

    const success = await screen.findByTestId("new-ticket-success");
    expect(success).toHaveTextContent("INC-3300");
    expect(success).toHaveTextContent(/human support agent will follow up/i);
    expect(body).toEqual({
      title: "VPN keeps dropping",
      description: "Every few minutes on the office wifi.",
      applicationCode: "VPN",
      source: "PORTAL",
    });
  });

  it("trims whitespace-only input so a blank summary can never be submitted", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewTicketForm />);

    await user.type(screen.getByLabelText(/summary/i), "   ");
    await user.type(screen.getByLabelText(/details/i), "real details here");

    expect(screen.getByRole("button", { name: /submit ticket/i })).toBeDisabled();
  });

  it("on a backend error stays on the form and shows the envelope message", async () => {
    server.use(http.post(BASE, () =>
      HttpResponse.json({ error: { code: "VALIDATION_ERROR", message: "title must not be blank" } }, { status: 400 }),
    ));

    const user = userEvent.setup();
    renderWithProviders(<NewTicketForm />);

    await user.type(screen.getByLabelText(/summary/i), "x");
    await user.type(screen.getByLabelText(/details/i), "y");
    await user.click(screen.getByRole("button", { name: /submit ticket/i }));

    expect(await screen.findByTestId("new-ticket-error")).toHaveTextContent("title must not be blank");
    expect(screen.getByLabelText(/summary/i)).toBeInTheDocument();
  });

  it("'Back to chat' returns the portal to the conversation view", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewTicketForm />);

    await user.click(screen.getByRole("button", { name: /back to chat/i }));

    await waitFor(() => expect(usePortalViewStore.getState().view).toBe("conversation"));
  });
});
