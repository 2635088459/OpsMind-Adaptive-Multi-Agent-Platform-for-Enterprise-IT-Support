import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/mswServer";
import { TICKET_WORKFLOW_BASE_URL, POLICY_APPROVAL_GOVERNANCE_BASE_URL } from "@/lib/env";
import { useAuthStore } from "@/store/authStore";
import type { SupportTicketDetail } from "@/features/ticket/api";
import { SupportDeskPage } from "@/pages/SupportDeskPage";

const TICKET_ID = "01a09175-575a-7821-a900-9bc002286603";
const QUEUE_BASE = `${TICKET_WORKFLOW_BASE_URL}/api/v1/support/tickets`;

/** Real shape of `SupportTicketDetail` — team-routed by triage but with no individual agent yet. */
const TEAM_ROUTED_ONLY_TICKET: SupportTicketDetail = {
  ticketId: TICKET_ID,
  displayId: "INC-3",
  title: "New conversation",
  description: "Started via the employee portal chat.",
  applicationCode: "OTHER",
  source: "PORTAL",
  status: "TRIAGED",
  priority: "MEDIUM",
  requesterRef: "hmac-sha256:deadbeef",
  assignment: { teamId: "network-support-team", agentId: null, queue: "OTHER" },
  resolutionCycle: { cycleNumber: 1, status: "ACTIVE" },
  sla: { state: "ACTIVE", policyId: "DEFAULT", responseDueAt: null, resolutionDueAt: null },
  createdAt: "2026-09-11T17:13:01.493416Z",
  updatedAt: "2026-09-11T17:15:28.853188Z",
  version: 1,
};

function stubTicketDetailDependencies(ticket: typeof TEAM_ROUTED_ONLY_TICKET) {
  server.use(
    http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}`, () => HttpResponse.json(ticket)),
    http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}/timeline`, () => HttpResponse.json({ items: [] })),
    http.get(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/governance-audit-records`, () => HttpResponse.json([])),
    http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}/trace`, () => HttpResponse.json({ traceId: null })),
    // Concept C: the queue rail is mounted alongside the detail pane now, not a separate page.
    http.get(QUEUE_BASE, () =>
      HttpResponse.json({
        items: [], page: { limit: 25, hasMore: false, nextCursor: null, evaluationTime: "2026-01-01T00:00:00Z", consistency: "LIVE" },
      }),
    ),
  );
}

function renderSupportDesk() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/tickets/${TICKET_ID}`]}>
        <Routes>
          <Route path="/tickets/:ticketId" element={<SupportDeskPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("SupportDeskPage — queue-first split view (Concept C)", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("keeps the queue rail mounted alongside the ticket detail pane", async () => {
    stubTicketDetailDependencies(TEAM_ROUTED_ONLY_TICKET);
    renderSupportDesk();

    expect(await screen.findByTestId("queue-rail")).toBeInTheDocument();
    expect(await screen.findByTestId("ticket-title")).toHaveTextContent("New conversation");
  });

  /**
   * Real bug caught live 2026-09-11: `initiallyAssigned` used to be
   * `agentId !== null || teamId !== null`. Every triaged ticket has a
   * `teamId` (routing) long before any individual `agentId` is picked, so
   * that condition treated a merely team-routed ticket as "already has an
   * owner" and defaulted the Assignment panel to "Reassign" — which calls
   * `POST /reassign`. `Ticket.reassign(...)` requires a real current
   * assignee and throws `TicketNotAssignedException` (409 TICKET_NOT_ASSIGNED)
   * when there isn't one, which is exactly what blocked every ticket action
   * downstream (assign, then resolve) until this was fixed to read only
   * `agentId !== null`. A TRIAGED-but-unassigned ticket also computes
   * "assign" as its current step, so the Assignment panel is what's open
   * by default — no extra click needed to reach it.
   */
  it("offers Assign (not Reassign) for a ticket routed only to a team, with no individual agent yet", async () => {
    stubTicketDetailDependencies(TEAM_ROUTED_ONLY_TICKET);
    renderSupportDesk();

    const assignButton = await screen.findByRole("button", { name: "Assign" });
    expect(assignButton).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reassign" })).not.toBeInTheDocument();
  });

  it("calls POST /assign (not /reassign) when submitting the Assignment form for a team-only-routed ticket", async () => {
    stubTicketDetailDependencies(TEAM_ROUTED_ONLY_TICKET);
    let assignCalled = false;
    let reassignCalled = false;
    server.use(
      http.post(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}/assign`, () => {
        assignCalled = true;
        return HttpResponse.json({
          ticketId: TICKET_ID, status: "ASSIGNED",
          assignee: { id: "e85c3314-64e6-48bb-b494-26d75fee189d", displayName: "Support Agent" },
          assignedAt: "2026-09-11T18:00:00Z", version: 2,
        });
      }),
      http.post(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}/reassign`, () => {
        reassignCalled = true;
        return HttpResponse.json(
          { error: { code: "TICKET_NOT_ASSIGNED", message: "The ticket has no current assignee." } },
          { status: 409 },
        );
      }),
    );
    const user = userEvent.setup();
    renderSupportDesk();

    await screen.findByRole("button", { name: "Assign" });
    const assignmentForm = within(screen.getByTestId("assignment-form"));
    await user.selectOptions(assignmentForm.getByLabelText("Assignee"), "e85c3314-64e6-48bb-b494-26d75fee189d");
    await user.type(assignmentForm.getByLabelText("Reason"), "picking this up");
    await user.click(assignmentForm.getByRole("button", { name: "Assign" }));

    await screen.findByTestId("assignment-success");
    expect(assignCalled).toBe(true);
    expect(reassignCalled).toBe(false);
  });

  it("still offers Reassign for a ticket that already has a real individual agent", async () => {
    // TRIAGED + already assigned is an edge case (assigning normally moves
    // status to ASSIGNED) — its current step is "start", so the Assignment
    // panel isn't the default view; jump to it via the trail, same as an
    // operator would.
    stubTicketDetailDependencies({
      ...TEAM_ROUTED_ONLY_TICKET,
      assignment: { teamId: "network-support-team", agentId: "e85c3314-64e6-48bb-b494-26d75fee189d", queue: "OTHER" },
    });
    renderSupportDesk();

    const user = userEvent.setup();
    await user.click(await screen.findByTestId("progress-step-assign"));

    expect(await screen.findByRole("button", { name: "Reassign" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Assign" })).not.toBeInTheDocument();
  });

  /**
   * Real gap caught live 2026-09-11: a successful Assignment submit used to
   * leave the Status panel's own `useVersionedMutation` instance still
   * holding the version from page load — a real desync the very next action
   * in ANY other panel would blindly race. Fixed by having each mutation
   * hook invalidate the shared ticket-detail query on success, paired with
   * `key={ticket.version}` on `TicketActionArea` so the resulting re-fetch
   * actually resets it (see `TicketDetailPane.tsx`'s own comment). The trail
   * itself advancing to "start" as current — not just the old panel
   * disappearing — is the proof the whole desk saw the real update.
   */
  it("assigning the ticket re-fetches ticket detail so the trail advances to the real next step", async () => {
    let getTicketCalls = 0;
    server.use(
      http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}`, () => {
        getTicketCalls += 1;
        return HttpResponse.json(
          getTicketCalls === 1
            ? TEAM_ROUTED_ONLY_TICKET
            : { ...TEAM_ROUTED_ONLY_TICKET, assignment: { teamId: "network-support-team", agentId: "e85c3314-64e6-48bb-b494-26d75fee189d", queue: "OTHER" }, version: 2 },
        );
      }),
      http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}/timeline`, () => HttpResponse.json({ items: [] })),
      http.get(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/governance-audit-records`, () => HttpResponse.json([])),
      http.get(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}/trace`, () => HttpResponse.json({ traceId: null })),
      http.get(QUEUE_BASE, () =>
        HttpResponse.json({ items: [], page: { limit: 25, hasMore: false, nextCursor: null, evaluationTime: "2026-01-01T00:00:00Z", consistency: "LIVE" } }),
      ),
      http.post(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${TICKET_ID}/assign`, () =>
        HttpResponse.json({
          ticketId: TICKET_ID, status: "ASSIGNED",
          assignee: { id: "e85c3314-64e6-48bb-b494-26d75fee189d", displayName: "Support Agent" },
          assignedAt: "2026-09-11T18:00:00Z", version: 2,
        }),
      ),
    );
    const user = userEvent.setup();
    renderSupportDesk();

    await screen.findByRole("button", { name: "Assign" });
    expect(getTicketCalls).toBe(1);

    const assignmentForm = within(screen.getByTestId("assignment-form"));
    await user.selectOptions(assignmentForm.getByLabelText("Assignee"), "e85c3314-64e6-48bb-b494-26d75fee189d");
    await user.type(assignmentForm.getByLabelText("Reason"), "picking this up");
    await user.click(assignmentForm.getByRole("button", { name: "Assign" }));

    // The GET fires again off the invalidated query; once the page has the
    // fresh (now-agentId-carrying) ticket, "assign" reads done and "start"
    // becomes current — proof the whole desk, not just the panel that
    // submitted, saw the real update.
    await screen.findByTestId("progress-step-start");
    expect(await screen.findByTestId("progress-step-start")).toHaveAttribute("data-state", "current");
    expect(await screen.findByTestId("progress-step-assign")).toHaveAttribute("data-state", "done");
    expect(getTicketCalls).toBeGreaterThan(1);
  });
});
