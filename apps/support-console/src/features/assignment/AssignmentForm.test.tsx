import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { AssignmentForm } from "@/features/assignment/AssignmentForm";

const BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1`;

describe("AssignmentForm — SPEC-SC-011", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("an unassigned ticket calls the real assign endpoint and shows only an Assign button", async () => {
    server.use(
      http.post(`${BASE}/assign`, async ({ request }) => {
        expect(request.headers.get("If-Match")).toBe("2");
        return HttpResponse.json({ ticketId: "ticket-1", status: "ASSIGNED", assignee: { id: "agent-9", displayName: "Agent Nine" }, assignedAt: "2026-09-02T00:00:00Z", version: 3 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AssignmentForm ticketId="ticket-1" initialVersion={2} initiallyAssigned={false} />);

    expect(screen.queryByRole("button", { name: "Unassign" })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("Assignee ID"), "agent-9");
    await user.type(screen.getByLabelText("Reason"), "picking this up");
    await user.click(screen.getByRole("button", { name: "Assign" }));

    expect(await screen.findByTestId("assignment-success")).toHaveTextContent("Agent Nine");
    expect(screen.getByRole("button", { name: "Unassign" })).toBeInTheDocument();
  });

  it("an already-assigned ticket calls the real reassign endpoint and offers Unassign too", async () => {
    server.use(
      http.post(`${BASE}/reassign`, () =>
        HttpResponse.json({ ticketId: "ticket-1", status: "ASSIGNED", assignee: { id: "agent-2", displayName: "Agent Two" }, assignedAt: "2026-09-02T00:00:00Z", version: 5 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AssignmentForm ticketId="ticket-1" initialVersion={4} initiallyAssigned={true} />);

    expect(screen.getByRole("button", { name: "Reassign" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("Assignee ID"), "agent-2");
    await user.type(screen.getByLabelText("Reason"), "rebalancing load");
    await user.click(screen.getByRole("button", { name: "Reassign" }));

    expect(await screen.findByTestId("assignment-success")).toHaveTextContent("Agent Two");
  });

  it("unassign calls the real unassign endpoint and renders the real null assignee, not omitted fields", async () => {
    server.use(
      http.post(`${BASE}/unassign`, () => HttpResponse.json({ ticketId: "ticket-1", status: "TRIAGED", assignee: null, assignedAt: null, version: 6 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<AssignmentForm ticketId="ticket-1" initialVersion={4} initiallyAssigned={true} />);

    await user.type(screen.getByLabelText("Reason"), "assignee left the team");
    await user.click(screen.getByRole("button", { name: "Unassign" }));

    expect(await screen.findByTestId("assignment-success")).toHaveTextContent("Unassigned.");
    expect(screen.queryByRole("button", { name: "Unassign" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Assign" })).toBeInTheDocument();
  });

  it("SPEC-SC-013: a real 412 VERSION_CONFLICT on assign renders the shared conflict banner", async () => {
    server.use(
      http.post(`${BASE}/assign`, () =>
        HttpResponse.json({ error: { code: "VERSION_CONFLICT", message: "stale", details: { currentVersion: 11 } } }, { status: 412 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AssignmentForm ticketId="ticket-1" initialVersion={2} initiallyAssigned={false} />);

    await user.type(screen.getByLabelText("Assignee ID"), "agent-9");
    await user.type(screen.getByLabelText("Reason"), "picking this up");
    await user.click(screen.getByRole("button", { name: "Assign" }));

    expect(await screen.findByTestId("version-conflict")).toHaveTextContent("version 11");
    await user.click(screen.getByRole("button", { name: "Reload" }));
    await waitFor(() => expect(screen.queryByTestId("version-conflict")).not.toBeInTheDocument());
  });
});
