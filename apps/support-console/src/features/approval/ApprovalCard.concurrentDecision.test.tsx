import { describe, it, expect, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { useAuthStore } from "@/store/authStore";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL } from "@/lib/env";
import { ApprovalCard } from "@/features/approval/ApprovalCard";
import { approvalRequestFixture } from "@/features/approval/testFixtures";

const DETAIL_URL = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1`;
const GRANT_URL = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1:grant`;
const DENY_URL = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1:deny`;

/**
 * SPEC-SC-017: hardens SPEC-SC-009 against `ApprovalService#decide`'s real
 * 3-way replay check (SPEC-PG-011), read directly from
 * `policy-approval-governance-service` for this spec's own grounding.
 *
 * A real finding worth recording: SPEC-SC-017 §5's own text describes 3
 * outcomes, including "same-decision-different-key (should still succeed
 * per the backend's replay semantics)". That is NOT what the real code
 * does — `decide()`'s `sameAttempt` check is a strict AND of
 * `commandIdempotencyKey`, `decision` (outcome), AND `decidedBy` all
 * matching the existing decision; a mismatch on ANY one of the three is a
 * conflict (`ApprovalAlreadyDecidedException`, real 409
 * `APPROVAL_ALREADY_DECIDED`) even when the attempted OUTCOME happens to
 * agree with the existing one. There are really only 2 outcomes, not 3:
 * an exact 3-way match (idempotent replay, existing state returned
 * unchanged) or anything else (conflict). This file tests the real 2, not
 * the spec's imagined 3rd.
 */
describe("ApprovalCard — SPEC-SC-017 concurrent decision", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("an exact idempotent replay (same key/outcome/actor) returns the existing decision quietly, with no conflict banner", async () => {
    let granted = false;
    server.use(
      http.get(DETAIL_URL, () => HttpResponse.json(approvalRequestFixture(granted ? { status: "APPROVED" } : {}))),
      http.post(GRANT_URL, () => {
        granted = true;
        return HttpResponse.json(approvalRequestFixture({ status: "APPROVED" }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApprovalCard approvalRequestId="approval-1" />);

    await screen.findByTestId("approval-card");
    await user.type(screen.getByLabelText("Reason"), "looks fine");
    await user.click(screen.getByRole("button", { name: "Grant" }));

    expect(await screen.findByTestId("approval-status")).toHaveTextContent("Granted");
    expect(screen.queryByTestId("approval-conflict")).not.toBeInTheDocument();
  });

  it("a second actor's differing decision (deny after a grant already landed) is rejected as APPROVAL_ALREADY_DECIDED and shows the real winning decision", async () => {
    let getCallCount = 0;
    server.use(
      http.get(DETAIL_URL, () => {
        getCallCount += 1;
        return HttpResponse.json(approvalRequestFixture(getCallCount === 1 ? {} : { status: "APPROVED" }));
      }),
      http.post(DENY_URL, () =>
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

    expect(await screen.findByTestId("approval-conflict")).toHaveTextContent("already has a final decision");
    expect(await screen.findByTestId("approval-status")).toHaveTextContent("Granted");
  });

  it("a matching outcome (grant vs. the real winning grant) submitted with a different idempotency key is STILL rejected as a conflict, not silently treated as success — correcting SPEC-SC-017 §5's own text against the real 3-way check", async () => {
    let getCallCount = 0;
    server.use(
      // The card's own GET is stale (fetched before the concurrent grant
      // landed elsewhere), so it still shows REQUESTED and offers Grant/Deny.
      http.get(DETAIL_URL, () => {
        getCallCount += 1;
        return HttpResponse.json(approvalRequestFixture(getCallCount === 1 ? {} : { status: "APPROVED" }));
      }),
      // This user's own outcome (grant) happens to coincide with the actual
      // winning decision recorded elsewhere — but the backend's 3-way check
      // still rejects it, because THIS attempt's idempotency key/actor never
      // matched the one already recorded.
      http.post(GRANT_URL, () =>
        HttpResponse.json(
          { error: { code: "APPROVAL_ALREADY_DECIDED", message: "The approval request already has a final decision that does not match this request." } },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApprovalCard approvalRequestId="approval-1" />);

    await screen.findByTestId("approval-card");
    await user.type(screen.getByLabelText("Reason"), "looks fine to me too");
    await user.click(screen.getByRole("button", { name: "Grant" }));

    expect(await screen.findByTestId("approval-conflict")).toBeInTheDocument();
    expect(await screen.findByTestId("approval-status")).toHaveTextContent("Granted");
  });
});
