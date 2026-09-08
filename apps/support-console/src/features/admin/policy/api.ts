import { authedFetch, newIdempotencyKey } from "@/lib/httpClient";
import { POLICY_APPROVAL_GOVERNANCE_BASE_URL } from "@/lib/env";

const BASE = `${POLICY_APPROVAL_GOVERNANCE_BASE_URL}/api/v1`;

/** Mirrors policy-approval-governance-service's `DraftPolicyRequest`. `rules` is a
 * `PolicyRuleDto[]` — kept as free-form JSON here rather than a bespoke rule
 * builder; the service validates the shape and returns a typed error if it's wrong. */
export interface DraftPolicyInput {
  policyId?: string;
  policyName: string;
  scope: string;
  rules: unknown[];
}

/** Mirrors `PolicyVersionResponse`. */
export interface PolicyVersion {
  policyVersionId: string;
  policyId: string;
  versionNumber: number;
  status: string;
  rules: unknown[];
  effectiveFrom: string | null;
  effectiveTo: string | null;
  createdBy: string;
  reviewedBy: string | null;
  publishedBy: string | null;
  publishedAt: string | null;
}

/** Mirrors `PolicySummaryResponse`. */
export interface PolicySummary {
  policyId: string;
  policyName: string;
  scope: string;
  currentPublishedVersion: number | null;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface PolicyDetail {
  policy: PolicySummary;
  versions: PolicyVersion[];
}

export async function listPolicies(): Promise<PolicySummary[]> {
  const response = await authedFetch(`${BASE}/policies`, { method: "GET" });
  return (await response.json()) as PolicySummary[];
}

export async function getPolicy(policyId: string): Promise<PolicyDetail> {
  const response = await authedFetch(`${BASE}/policies/${policyId}`, { method: "GET" });
  return (await response.json()) as PolicyDetail;
}

export async function draftPolicy(input: DraftPolicyInput): Promise<PolicyVersion> {
  const response = await authedFetch(`${BASE}/policies:draft`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Correlation-Id": newIdempotencyKey() },
    body: JSON.stringify({
      policyId: input.policyId || null,
      policyName: input.policyName,
      scope: input.scope,
      rules: input.rules,
    }),
  });
  return (await response.json()) as PolicyVersion;
}

async function policyVersionAction(policyVersionId: string, action: "review" | "publish"): Promise<PolicyVersion> {
  const response = await authedFetch(`${BASE}/policy-versions/${policyVersionId}:${action}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Correlation-Id": newIdempotencyKey() },
    body: action === "publish" ? JSON.stringify({}) : undefined,
  });
  return (await response.json()) as PolicyVersion;
}

export const reviewPolicyVersion = (id: string) => policyVersionAction(id, "review");
export const publishPolicyVersion = (id: string) => policyVersionAction(id, "publish");
