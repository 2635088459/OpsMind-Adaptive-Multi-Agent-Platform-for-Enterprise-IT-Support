import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { StatusTransitionControl } from "@/features/statusTransition/StatusTransitionControl";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1`;

describe("StatusTransitionControl — SPEC-SC-012", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("transitioning to IN_PROGRESS calls the real status-transitions endpoint and updates the display", async () => {
    server.use(
      http.post(`${BASE}/status-transitions`, async ({ request }) => {
        expect(request.headers.get("If-Match")).toBe("2");
        const body = (await request.json()) as Record<string, unknown>;
        expect(body).toMatchObject({ targetStatus: "IN_PROGRESS", reason: "starting work" });
        return HttpResponse.json({
          ticketId: "ticket-1", previousStatus: "ASSIGNED", status: "IN_PROGRESS", reason: "starting work",
          waitingForRequesterSince: null, approvalReference: null, transitionedAt: "2026-09-02T00:00:00Z", version: 3,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<StatusTransitionControl ticketId="ticket-1" initialVersion={2} />);

    await user.type(screen.getByLabelText("Reason"), "starting work");
    await user.click(screen.getByRole("button", { name: "Start work" }));

    expect(await screen.findByTestId("status-transition-success")).toHaveTextContent("Now IN_PROGRESS");
  });

  it("resolving calls the real dedicated resolution endpoint, not the generic transition endpoint", async () => {
    server.use(
      http.post(`${BASE}/resolution`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        expect(body).toMatchObject({ resolutionCode: "FIXED", resolutionSummary: "Replaced the network cable." });
        return HttpResponse.json({
          ticketId: "ticket-1", previousStatus: "IN_PROGRESS", status: "RESOLVED", resolutionCode: "FIXED",
          resolutionSummary: "Replaced the network cable.", resolvedBy: "support.agent", resolvedAt: "2026-09-02T00:00:00Z",
          resolutionCycleId: "cycle-1", autoCloseDueAt: "2026-09-05T00:00:00Z", version: 4,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<StatusTransitionControl ticketId="ticket-1" initialVersion={3} />);

    await user.type(screen.getByLabelText("Resolution summary"), "Replaced the network cable.");
    await user.click(screen.getByRole("button", { name: "Resolve" }));

    expect(await screen.findByTestId("status-transition-success")).toHaveTextContent("Resolved as FIXED");
  });

  it("SPEC-SC-012 §17 / SPEC-SC-013: an invalid-transition rejection is surfaced honestly, not silently accepted", async () => {
    server.use(
      http.post(`${BASE}/status-transitions`, () =>
        HttpResponse.json(
          { error: { code: "INVALID_STATUS_TRANSITION", message: "The requested ticket status transition is not allowed.", details: { currentStatus: "RESOLVED", targetStatus: "IN_PROGRESS" } } },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<StatusTransitionControl ticketId="ticket-1" initialVersion={5} />);

    await user.type(screen.getByLabelText("Reason"), "trying to reopen");
    await user.click(screen.getByRole("button", { name: "Start work" }));

    expect(await screen.findByTestId("status-transition-error")).toBeInTheDocument();
    expect(screen.queryByTestId("status-transition-success")).not.toBeInTheDocument();
  });

  it("SPEC-SC-013: a real 412 VERSION_CONFLICT renders the shared conflict banner", async () => {
    server.use(
      http.post(`${BASE}/status-transitions`, () =>
        HttpResponse.json({ error: { code: "VERSION_CONFLICT", message: "stale", details: { currentVersion: 8 } } }, { status: 412 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<StatusTransitionControl ticketId="ticket-1" initialVersion={2} />);

    await user.type(screen.getByLabelText("Reason"), "starting work");
    await user.click(screen.getByRole("button", { name: "Start work" }));

    expect(await screen.findByTestId("version-conflict")).toHaveTextContent("version 8");
    await user.click(screen.getByRole("button", { name: "Reload" }));
    await waitFor(() => expect(screen.queryByTestId("version-conflict")).not.toBeInTheDocument());
  });
});
