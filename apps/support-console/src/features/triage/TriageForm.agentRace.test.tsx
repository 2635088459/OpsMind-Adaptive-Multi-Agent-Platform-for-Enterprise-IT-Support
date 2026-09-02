import { describe, it, expect, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { TriageForm } from "@/features/triage/TriageForm";

const TRIAGE_URL = `${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/ticket-1/triage`;

/**
 * SPEC-SC-016: hardens SPEC-SC-010's triage form against the specific
 * scenario where `agent-runtime-service` itself (SPEC-ARO-041's own
 * escalation-triage call), not another human, wins the race for the same
 * ticket's `If-Match` version.
 *
 * A real finding worth recording, confirmed by reading
 * `TriageTicketController`/`GlobalRestExceptionHandler` directly: the wire
 * contract for `VERSION_CONFLICT` carries only `currentVersion` — no actor
 * kind, no "who changed it" field at all. The backend cannot and does not
 * distinguish "a human beat you to it" from "the agent beat you to it" in
 * this response; both look identical on the wire. SPEC-SC-016 §19's own
 * conclusion — "no special-casing needed beyond what SPEC-SC-013 already
 * provides" — is therefore not just a design choice, it's the only thing
 * the real contract allows: this test proves the existing generic
 * conflict-handling path, with no agent-aware branch added, is already
 * correct for this race.
 */
describe("TriageForm — SPEC-SC-016 agent-vs-human triage race", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("a version conflict caused by agent-runtime-service's own concurrent SPEC-ARO-041 triage call renders the same conflict banner as any other concurrent writer", async () => {
    server.use(
      http.post(TRIAGE_URL, () =>
        HttpResponse.json(
          { error: { code: "VERSION_CONFLICT", message: "the ticket was changed by another operation", details: { currentVersion: 6 } } },
          { status: 412 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<TriageForm ticketId="ticket-1" initialVersion={5} />);

    await user.type(screen.getByLabelText("Category ID"), "category-1");
    await user.type(screen.getByLabelText("Support queue ID"), "queue-1");
    await user.type(screen.getByLabelText("Reason"), "human triage attempt");
    await user.click(screen.getByRole("button", { name: "Submit triage" }));

    // No agent-vs-human distinction is rendered because the backend gives
    // this client no such signal to render — the honest, correctly-scoped
    // behavior per this spec's own Definition of Done.
    expect(await screen.findByTestId("version-conflict")).toHaveTextContent("version 6");
  });
});
