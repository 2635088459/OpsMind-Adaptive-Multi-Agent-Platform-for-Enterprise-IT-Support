import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { TicketTraceLink } from "@/features/trace/TicketTraceLink";

const TRACE_URL = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1/trace`;

describe("TicketTraceLink — SPEC-SC-014 (UC-SC-05)", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("renders a Tempo deep link when the ticket has a trace id", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json({ traceId: "04c2885e94ba49bbdd7c187f1ff5a3a8" })));

    renderWithProviders(<TicketTraceLink ticketId="ticket-1" />);

    const link = await screen.findByTestId("ticket-trace-link");
    expect(link).toHaveAttribute("href", expect.stringContaining("traceID=04c2885e94ba49bbdd7c187f1ff5a3a8"));
    expect(link).toHaveAttribute("target", "_blank");
  });

  it("renders nothing when the ticket has no trace id yet", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json({ traceId: null })));

    const { container } = renderWithProviders(<TicketTraceLink ticketId="ticket-1" />);

    await waitFor(() => expect(screen.queryByTestId("ticket-trace-link")).not.toBeInTheDocument());
    expect(container).toBeEmptyDOMElement();
  });
});
