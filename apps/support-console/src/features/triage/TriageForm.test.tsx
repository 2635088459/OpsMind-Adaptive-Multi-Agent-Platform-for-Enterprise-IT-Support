import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { TriageForm } from "@/features/triage/TriageForm";

const TRIAGE_URL = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1/triage`;

async function fillRequiredFields(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("Category ID"), "category-1");
  await user.type(screen.getByLabelText("Support queue ID"), "queue-1");
  await user.type(screen.getByLabelText("Reason"), "misfiled ticket");
}

describe("TriageForm — SPEC-SC-010", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("submits with the real If-Match header and reflects the real resulting state on success", async () => {
    server.use(
      http.post(TRIAGE_URL, async ({ request }) => {
        expect(request.headers.get("If-Match")).toBe("3");
        const body = (await request.json()) as Record<string, unknown>;
        expect(body).toMatchObject({ categoryId: "category-1", supportQueueId: "queue-1", priority: "HIGH", reason: "misfiled ticket" });
        return HttpResponse.json({
          ticketId: "ticket-1", status: "TRIAGED", categoryId: "category-1", subcategoryId: null,
          priority: "HIGH", supportQueueId: "queue-1", triagedBy: "support.agent", triagedAt: "2026-09-02T00:00:00Z", version: 4,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<TriageForm ticketId="ticket-1" initialVersion={3} />);

    await fillRequiredFields(user);
    await user.click(screen.getByRole("button", { name: "Submit triage" }));

    expect(await screen.findByTestId("triage-success")).toHaveTextContent("HIGH");
  });

  it("SPEC-SC-013: a real 412 VERSION_CONFLICT renders the shared conflict banner instead of a generic error", async () => {
    server.use(
      http.post(TRIAGE_URL, () =>
        HttpResponse.json(
          { error: { code: "VERSION_CONFLICT", message: "the ticket was changed by another operation", details: { currentVersion: 9 } } },
          { status: 412 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<TriageForm ticketId="ticket-1" initialVersion={3} />);

    await fillRequiredFields(user);
    await user.click(screen.getByRole("button", { name: "Submit triage" }));

    expect(await screen.findByTestId("version-conflict")).toHaveTextContent("version 9");
    expect(screen.queryByTestId("triage-error")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Reload" }));
    await waitFor(() => expect(screen.queryByTestId("version-conflict")).not.toBeInTheDocument());
  });
});
