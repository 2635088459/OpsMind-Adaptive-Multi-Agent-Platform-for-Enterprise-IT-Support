import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL } from "@/lib/env";
import { decideApproval, getApprovalRequest } from "@/features/approval/api";
import { approvalRequestFixture } from "@/features/approval/testFixtures";

describe("approval api — SPEC-SC-008/009 real contracts", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("getApprovalRequest maps the real ApprovalRequestResponse shape, including requestHash", async () => {
    server.use(
      http.get(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1`, () =>
        HttpResponse.json(approvalRequestFixture()),
      ),
    );

    const result = await getApprovalRequest("approval-1");

    expect(result.approvalRequestId).toBe("approval-1");
    expect(result.requestHash).toBe("hash-abc");
    expect(result.status).toBe("REQUESTED");
  });

  it("decideApproval posts to the real :grant path with the Idempotency-Key header set from the body", async () => {
    server.use(
      http.post(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1:grant`, async ({ request }) => {
        expect(request.headers.get("Idempotency-Key")).toBe("idem-1");
        const body = (await request.json()) as Record<string, unknown>;
        expect(body).toMatchObject({ sourceRequestId: "src-1", requestHash: "hash-abc", reason: "looks fine" });
        return HttpResponse.json(approvalRequestFixture({ status: "APPROVED" }));
      }),
    );

    const result = await decideApproval("approval-1", "grant", {
      sourceRequestId: "src-1",
      requestHash: "hash-abc",
      reason: "looks fine",
      conditions: [],
      commandIdempotencyKey: "idem-1",
      stepUpVerified: false,
    });

    expect(result.status).toBe("APPROVED");
  });

  it("decideApproval on a real 409 APPROVAL_ALREADY_DECIDED response throws a typed ApiError", async () => {
    server.use(
      http.post(`${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1/approval-requests/approval-1:deny`, () =>
        HttpResponse.json(
          { error: { code: "APPROVAL_ALREADY_DECIDED", message: "The approval request already has a final decision that does not match this request." } },
          { status: 409 },
        ),
      ),
    );

    await expect(
      decideApproval("approval-1", "deny", {
        sourceRequestId: "src-1",
        requestHash: "hash-abc",
        reason: "too risky",
        conditions: [],
        commandIdempotencyKey: "idem-2",
        stepUpVerified: false,
      }),
    ).rejects.toMatchObject({ status: 409, code: "APPROVAL_ALREADY_DECIDED" });
  });
});
