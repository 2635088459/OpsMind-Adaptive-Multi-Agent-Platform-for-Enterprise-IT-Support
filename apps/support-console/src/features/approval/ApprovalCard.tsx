import { useState } from "react";
import { useApprovalRequest, useDecideApproval } from "@/features/approval/useApproval";
import { newIdempotencyKey } from "@/lib/httpClient";
import { ApiError } from "@/lib/apiError";

const RISK_CLASS: Record<string, string> = {
  LOW: "bg-surface-muted text-ink-muted",
  MEDIUM: "bg-surface-muted text-ink",
  HIGH: "bg-danger/10 text-danger",
  CRITICAL: "bg-danger/20 text-danger",
};

const STATUS_LABEL: Record<string, string> = {
  REQUESTED: "Awaiting decision",
  APPROVED: "Granted",
  DENIED: "Denied",
  EXPIRED: "Expired",
  CANCELLED: "Cancelled",
  SUPERSEDED: "Superseded",
  USED: "Used",
  REVOKED: "Revoked",
};

/**
 * SPEC-SC-008 (card) + SPEC-SC-009 (grant/deny) built together — the same
 * one-mechanism discipline used for SPEC-SC-006/007/019 and SPEC-SC-002/019
 * elsewhere this session: a render-only card is meaningless without the
 * action it exists to gate, and the action needs the card's own fetched
 * `requestHash` to even be callable.
 *
 * `conditions: []` and `stepUpVerified: false` are honestly hardcoded, not
 * silently omitted — this console has no UI yet for attaching new
 * constraints to a decision, and no step-up/MFA re-auth flow wired here
 * (matching the same documented-gap discipline as SPEC-SC-002's
 * UI-convenience-only role note).
 */
export function ApprovalCard({ approvalRequestId }: { approvalRequestId: string }) {
  const { data, isLoading, isError } = useApprovalRequest(approvalRequestId);
  const decide = useDecideApproval(approvalRequestId);
  const [reason, setReason] = useState("");

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4" data-testid="approval-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div className="rounded-xl border border-border bg-danger/5 p-4 text-sm text-danger" data-testid="approval-error">
        Unable to load this approval request.
      </div>
    );
  }

  const isDecidable = data.status === "REQUESTED";
  const conflict = decide.isError && decide.error instanceof ApiError && decide.error.code === "APPROVAL_ALREADY_DECIDED";

  function submit(decision: "grant" | "deny") {
    if (!data) return;
    decide.mutate({
      decision,
      body: {
        sourceRequestId: data.sourceRequestId,
        requestHash: data.requestHash,
        reason,
        conditions: [],
        commandIdempotencyKey: newIdempotencyKey(),
        stepUpVerified: false,
      },
    });
  }

  return (
    <div className="rounded-xl border border-border bg-surface p-4" data-testid="approval-card">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-ink">Approval request</h2>
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${RISK_CLASS[data.riskLevel] ?? "bg-surface-muted text-ink-muted"}`}>
          {data.riskLevel}
        </span>
      </div>

      <dl className="mt-3 grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-ink-muted">Action type</dt>
        <dd className="text-ink">{data.approvalType}</dd>
        <dt className="text-ink-muted">Requested by</dt>
        <dd className="text-ink">{data.requestedBy}</dd>
        {data.policyDecisionId && (
          <>
            <dt className="text-ink-muted">Policy decision</dt>
            <dd className="text-ink">{data.policyDecisionId}</dd>
          </>
        )}
        <dt className="text-ink-muted">Status</dt>
        <dd className="text-ink" data-testid="approval-status">
          {STATUS_LABEL[data.status] ?? data.status}
        </dd>
      </dl>

      {data.constraints.length > 0 && (
        <ul className="mt-2 flex flex-col gap-1 text-xs text-ink-muted">
          {data.constraints.map((c) => (
            <li key={`${c.type}-${c.detail}`}>
              {c.type}: {c.detail}
            </li>
          ))}
        </ul>
      )}

      {conflict && (
        <p className="mt-3 rounded-md bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="approval-conflict">
          This request already has a final decision that doesn&apos;t match this attempt — showing the actual current decision above.
        </p>
      )}

      {isDecidable ? (
        <div className="mt-3 flex flex-col gap-2">
          <label className="text-xs font-medium text-ink-muted" htmlFor={`approval-reason-${approvalRequestId}`}>
            Reason
          </label>
          <textarea
            id={`approval-reason-${approvalRequestId}`}
            className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
          />
          <div className="flex gap-2">
            <button
              type="button"
              disabled={!reason.trim() || decide.isPending}
              onClick={() => submit("grant")}
              className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
            >
              Grant
            </button>
            <button
              type="button"
              disabled={!reason.trim() || decide.isPending}
              onClick={() => submit("deny")}
              className="rounded-md bg-danger px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
            >
              Deny
            </button>
          </div>
          {!conflict && decide.isError && (
            <p className="text-sm text-danger" data-testid="approval-decide-error">
              Unable to submit this decision. You can try again.
            </p>
          )}
        </div>
      ) : (
        !conflict && (
          <p className="mt-3 text-sm text-ink-muted" data-testid="approval-decided">
            This request has already reached a final state.
          </p>
        )
      )}
    </div>
  );
}
