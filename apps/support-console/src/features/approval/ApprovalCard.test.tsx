import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL } from "@/lib/env";
import { ApprovalCard } from "@/features/approval/ApprovalCard";
import { approvalRequestFixture } from "@/features/approval/testFixtures";

const DETAIL_URL = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1`;

describe("ApprovalCard — SPEC-SC-008/009", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("SPEC-SC-008: renders a pending request's action, target, and requester from the real backend shape", async () => {
    server.use(http.get(DETAIL_URL, () => HttpResponse.json(approvalRequestFixture())));

    renderWithProviders(<ApprovalCard approvalRequestId="approval-1" />);

    expect(await screen.findByTestId("approval-card")).toHaveTextContent("TICKET_ACTION");
    expect(screen.getByTestId("approval-card")).toHaveTextContent("agent-runtime-service");
    expect(screen.getByTestId("approval-card")).toHaveTextContent("decision-1");
    expect(screen.getByTestId("approval-status")).toHaveTextContent("Awaiting decision");
    expect(screen.getByRole("button", { name: "Grant" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Deny" })).toBeInTheDocument();
  });

  it("SPEC-SC-009: granting updates the card to the granted state", async () => {
    let granted = false;
    server.use(
      http.get(DETAIL_URL, () => HttpResponse.json(approvalRequestFixture(granted ? { status: "APPROVED" } : {}))),
      http.post(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1:grant`, () => {
        granted = true;
        return HttpResponse.json(approvalRequestFixture({ status: "APPROVED" }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApprovalCard approvalRequestId="approval-1" />);

    await screen.findByTestId("approval-card");
    await user.type(screen.getByLabelText("Reason"), "looks fine");
    await user.click(screen.getByRole("button", { name: "Grant" }));

    await waitFor(() => expect(screen.getByTestId("approval-status")).toHaveTextContent("Granted"));
    expect(screen.getByTestId("approval-decided")).toBeInTheDocument();
  });

  it("SPEC-SC-009 §16: a 409 already-decided-differently response re-fetches and renders the actual current decision, not a generic error", async () => {
    let getCallCount = 0;
    server.use(
      http.get(DETAIL_URL, () => {
        getCallCount += 1;
        return HttpResponse.json(approvalRequestFixture(getCallCount === 1 ? {} : { status: "APPROVED" }));
      }),
      http.post(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1:deny`, () =>
        HttpResponse.json(
          { error: { code: "APPROVAL_ALREADY_DECIDED", message: "The approval request already has a final decision that does not match this request." } },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApprovalCard approvalRequestId="approval-1" />);

    await screen.findByTestId("approval-card");
    await user.type(screen.getByLabelText("Reason"), "too risky");
    await user.click(screen.getByRole("button", { name: "Deny" }));

    expect(await screen.findByTestId("approval-conflict")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("approval-status")).toHaveTextContent("Granted"));
  });

  it("a request that has already reached a final state renders no grant/deny buttons", async () => {
    server.use(http.get(DETAIL_URL, () => HttpResponse.json(approvalRequestFixture({ status: "DENIED" }))));

    renderWithProviders(<ApprovalCard approvalRequestId="approval-1" />);

    expect(await screen.findByTestId("approval-decided")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Grant" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Deny" })).not.toBeInTheDocument();
  });
});
