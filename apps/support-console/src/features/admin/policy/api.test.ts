import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL } from "@/lib/env";
import {
  draftPolicy,
  getPolicy,
  listPolicies,
  publishPolicyVersion,
  reviewPolicyVersion,
} from "@/features/admin/policy/api";

const BASE = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1`;

const VERSION = {
  policyVersionId: "pv-1", policyId: "p-1", versionNumber: 1, status: "DRAFT", rules: [],
  effectiveFrom: null, effectiveTo: null, createdBy: "author-1", reviewedBy: null, publishedBy: null, publishedAt: null,
};

describe("policy admin api", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("lists policies", async () => {
    server.use(http.get(`${BASE}/policies`, () => HttpResponse.json([
      { policyId: "p-1", policyName: "Alpha", scope: "global", currentPublishedVersion: null, status: "ACTIVE", createdBy: "a", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" },
    ])));
    const result = await listPolicies();
    expect(result).toHaveLength(1);
    expect(result[0].policyName).toBe("Alpha");
  });

  it("gets one policy with its version history", async () => {
    server.use(http.get(`${BASE}/policies/p-1`, () => HttpResponse.json({
      policy: { policyId: "p-1", policyName: "Alpha", scope: "global", currentPublishedVersion: 2, status: "ACTIVE", createdBy: "a", createdAt: "x", updatedAt: "y" },
      versions: [VERSION],
    })));
    const detail = await getPolicy("p-1");
    expect(detail.policy.currentPublishedVersion).toBe(2);
    expect(detail.versions[0].policyVersionId).toBe("pv-1");
  });

  it("drafts a policy version and returns the PolicyVersionResponse", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(http.post(`${BASE}/policies:draft`, async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(VERSION, { status: 201 });
    }));

    const result = await draftPolicy({ policyName: "tool-exec", scope: "tool-execution", rules: [{ ruleId: "r1" }] });

    expect(body).toEqual({ policyId: null, policyName: "tool-exec", scope: "tool-execution", rules: [{ ruleId: "r1" }] });
    expect(result.versionNumber).toBe(1);
  });

  it("posts review and publish actions to the :action endpoints", async () => {
    const seen: string[] = [];
    // ':review' / ':publish' would be read as path params by MSW's matcher, so
    // match on the raw request URL instead.
    server.use(
      http.post(/\/policy-versions\/pv-1:(review|publish)$/, ({ request }) => {
        const action = request.url.endsWith(":publish") ? "publish" : "review";
        seen.push(action);
        return action === "publish"
          ? HttpResponse.json({ ...VERSION, status: "PUBLISHED", publishedBy: "p-1" })
          : HttpResponse.json({ ...VERSION, status: "REVIEWING", reviewedBy: "r-1" });
      }),
    );

    expect((await reviewPolicyVersion("pv-1")).status).toBe("REVIEWING");
    expect((await publishPolicyVersion("pv-1")).status).toBe("PUBLISHED");
    expect(seen).toEqual(["review", "publish"]);
  });

  it("surfaces a 403 (missing policy:read scope) as a throwing ApiError", async () => {
    server.use(http.get(`${BASE}/policies`, () => HttpResponse.json({ error: { code: "FORBIDDEN", message: "no scope" } }, { status: 403 })));
    await expect(listPolicies()).rejects.toMatchObject({ status: 403 });
  });
});
