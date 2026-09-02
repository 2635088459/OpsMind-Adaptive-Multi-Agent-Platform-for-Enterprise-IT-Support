import { useState } from "react";
import { useStatusTransition } from "@/features/statusTransition/useStatusTransition";
import { VersionConflictBanner } from "@/features/ticketOps/VersionConflictBanner";
import type { ResolutionCode } from "@/features/statusTransition/types";

const RESOLUTION_CODES: ResolutionCode[] = [
  "FIXED", "WORKAROUND_PROVIDED", "DUPLICATE", "REQUEST_FULFILLED", "NOT_REPRODUCIBLE", "USER_ERROR", "NO_ACTION_REQUIRED",
];

function isResolveResult(data: { status: string } | undefined): data is { status: string; resolutionCode: string } {
  return !!data && "resolutionCode" in data;
}

/**
 * SPEC-SC-012: distinct action buttons per real backend transition — not
 * one generic dropdown, since no single endpoint covers every reachable
 * target (confirmed directly in `TransitionTicketStatusController`/
 * `ResolveTicketController`). "Start work" / "Send for approval" call the
 * generic transition endpoint; "Resolve" calls the dedicated resolution
 * endpoint with the real controlled `ResolutionCode` vocabulary.
 */
export function StatusTransitionControl({ ticketId, initialVersion }: { ticketId: string; initialVersion: number }) {
  const { conflictVersion, acknowledgeConflict, mutate, isPending, isError, isSuccess, data } = useStatusTransition(ticketId, initialVersion);
  const [reason, setReason] = useState("");
  const [approvalReference, setApprovalReference] = useState("");
  const [resolutionCode, setResolutionCode] = useState<ResolutionCode>(RESOLUTION_CODES[0]);
  const [resolutionSummary, setResolutionSummary] = useState("");

  const canTransition = reason.trim().length >= 3;
  const canSendForApproval = canTransition && approvalReference.trim().length >= 3;
  const canResolve = resolutionSummary.trim().length >= 10;

  return (
    <div className="rounded-xl border border-border bg-surface p-4" data-testid="status-transition-control">
      <h2 className="text-sm font-medium text-ink">Update ticket status</h2>

      {conflictVersion !== null && <VersionConflictBanner currentVersion={conflictVersion} onReload={acknowledgeConflict} />}

      {isSuccess && data && (
        <p className="mt-2 rounded-md bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="status-transition-success">
          {isResolveResult(data) ? `Resolved as ${data.resolutionCode}.` : `Now ${data.status}.`}
        </p>
      )}

      <div className="mt-3 flex flex-col gap-2">
        <label className="text-xs font-medium text-ink-muted" htmlFor={`transition-reason-${ticketId}`}>
          Reason
        </label>
        <textarea
          id={`transition-reason-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={2}
        />

        <label className="text-xs font-medium text-ink-muted" htmlFor={`approval-ref-${ticketId}`}>
          Approval reference (for "Send for approval")
        </label>
        <input
          id={`approval-ref-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={approvalReference}
          onChange={(e) => setApprovalReference(e.target.value)}
        />

        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            disabled={!canTransition || isPending}
            onClick={() => mutate({ kind: "transition", targetStatus: "IN_PROGRESS", reason: reason.trim() })}
            className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            Start work
          </button>
          <button
            type="button"
            disabled={!canSendForApproval || isPending}
            onClick={() => mutate({ kind: "transition", targetStatus: "WAITING_FOR_APPROVAL", reason: reason.trim(), approvalReference: approvalReference.trim() })}
            className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium text-ink disabled:opacity-50"
          >
            Send for approval
          </button>
        </div>
      </div>

      <hr className="my-3 border-border" />

      <div className="flex flex-col gap-2">
        <label className="text-xs font-medium text-ink-muted" htmlFor={`resolution-code-${ticketId}`}>
          Resolution code
        </label>
        <select
          id={`resolution-code-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={resolutionCode}
          onChange={(e) => setResolutionCode(e.target.value as ResolutionCode)}
        >
          {RESOLUTION_CODES.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>

        <label className="text-xs font-medium text-ink-muted" htmlFor={`resolution-summary-${ticketId}`}>
          Resolution summary
        </label>
        <textarea
          id={`resolution-summary-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={resolutionSummary}
          onChange={(e) => setResolutionSummary(e.target.value)}
          rows={2}
        />
        <button
          type="button"
          disabled={!canResolve || isPending}
          onClick={() => mutate({ kind: "resolve", resolutionCode, resolutionSummary: resolutionSummary.trim() })}
          className="self-start rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          Resolve
        </button>
      </div>

      {isError && conflictVersion === null && (
        <p className="mt-2 text-sm text-danger" data-testid="status-transition-error">
          Unable to submit this update. You can try again.
        </p>
      )}
    </div>
  );
}
