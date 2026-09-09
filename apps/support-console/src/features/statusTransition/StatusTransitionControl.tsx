import { useState } from "react";
import { useStatusTransition } from "@/features/statusTransition/useStatusTransition";
import { VersionConflictBanner } from "@/features/ticketOps/VersionConflictBanner";
import type { ResolutionCode } from "@/features/statusTransition/types";

const RESOLUTION_CODES: { value: ResolutionCode; note: string }[] = [
  { value: "FIXED", note: "root cause addressed" },
  { value: "WORKAROUND_PROVIDED", note: "usable workaround given" },
  { value: "DUPLICATE", note: "already tracked elsewhere" },
  { value: "REQUEST_FULFILLED", note: "access / item delivered" },
  { value: "NOT_REPRODUCIBLE", note: "could not reproduce" },
  { value: "USER_ERROR", note: "no product defect" },
  { value: "NO_ACTION_REQUIRED", note: "nothing to do" },
];

function isResolveResult(data: { status: string } | undefined): data is { status: string; resolutionCode: string } {
  return !!data && "resolutionCode" in data;
}

/**
 * SPEC-SC-012: distinct action buttons per real backend transition — not one
 * generic dropdown, since no single endpoint covers every reachable target
 * (confirmed in `TransitionTicketStatusController` / `ResolveTicketController`).
 * "Start work" / "Send for approval" call the generic transition endpoint;
 * "Resolve" calls the dedicated resolution endpoint with the real controlled
 * `ResolutionCode` vocabulary.
 */
export function StatusTransitionControl({ ticketId, initialVersion }: { ticketId: string; initialVersion: number }) {
  const { conflictVersion, acknowledgeConflict, mutate, isPending, isError, isSuccess, data } = useStatusTransition(ticketId, initialVersion);
  const [reason, setReason] = useState("");
  const [approvalReference, setApprovalReference] = useState("");
  const [resolutionCode, setResolutionCode] = useState<ResolutionCode>(RESOLUTION_CODES[0].value);
  const [resolutionSummary, setResolutionSummary] = useState("");

  const canTransition = reason.trim().length >= 3;
  const canSendForApproval = canTransition && approvalReference.trim().length >= 3;
  const canResolve = resolutionSummary.trim().length >= 10;

  return (
    <div data-testid="status-transition-control">
      {conflictVersion !== null && <VersionConflictBanner currentVersion={conflictVersion} onReload={acknowledgeConflict} />}

      {isSuccess && data && (
        <p className="mb-3 rounded-md border border-brand-100 bg-brand-50 px-3 py-2 text-sm text-ink" data-testid="status-transition-success">
          {isResolveResult(data) ? `Resolved as ${data.resolutionCode}.` : `Now ${data.status}.`}
        </p>
      )}

      <div className="flex flex-col gap-4">
        <div>
          <h3 className="text-sm font-semibold text-ink">Move this ticket forward</h3>
          <p className="mt-0.5 text-xs leading-relaxed text-ink-muted">
            "Start work" takes an assigned ticket to IN_PROGRESS. "Send for approval" parks it in WAITING_FOR_APPROVAL and
            needs an approval-request reference.
          </p>
        </div>

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`transition-reason-${ticketId}`}>
            Reason
          </label>
          <p className="text-xs leading-relaxed text-ink-muted">At least 3 characters. Shown on the ticket timeline.</p>
          <textarea
            id={`transition-reason-${ticketId}`}
            aria-label="Reason"
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink focus:border-brand-500 focus:outline-none"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            placeholder="e.g. starting work — reproduced on the office wifi"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`approval-ref-${ticketId}`}>
            Approval reference <span className="font-normal text-ink-muted">(only for "Send for approval")</span>
          </label>
          <p className="text-xs leading-relaxed text-ink-muted">
            The approval-request ID from the Approval panel below, or a change-ticket reference.
          </p>
          <input
            id={`approval-ref-${ticketId}`}
            aria-label="Approval reference"
            className="rounded-md border border-border bg-surface px-3 py-2 font-mono text-xs text-ink focus:border-brand-500 focus:outline-none"
            value={approvalReference}
            onChange={(e) => setApprovalReference(e.target.value)}
          />
        </div>

        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            disabled={!canTransition || isPending}
            onClick={() => mutate({ kind: "transition", targetStatus: "IN_PROGRESS", reason: reason.trim() })}
            className="rounded-md bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
          >
            Start work
          </button>
          <button
            type="button"
            disabled={!canSendForApproval || isPending}
            onClick={() => mutate({ kind: "transition", targetStatus: "WAITING_FOR_APPROVAL", reason: reason.trim(), approvalReference: approvalReference.trim() })}
            className="rounded-md border border-border bg-surface px-4 py-2 text-sm font-semibold text-ink hover:bg-surface-muted disabled:opacity-50"
          >
            Send for approval
          </button>
        </div>
      </div>

      <hr className="my-5 border-border" />

      <div className="flex flex-col gap-4">
        <div>
          <h3 className="text-sm font-semibold text-ink">Resolve this ticket</h3>
          <p className="mt-0.5 text-xs leading-relaxed text-ink-muted">
            Closes out the work with a resolution code. The requester still gets a window to confirm or reopen.
          </p>
        </div>

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`resolution-code-${ticketId}`}>
            Resolution code
          </label>
          <select
            id={`resolution-code-${ticketId}`}
            aria-label="Resolution code"
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink focus:border-brand-500 focus:outline-none"
            value={resolutionCode}
            onChange={(e) => setResolutionCode(e.target.value as ResolutionCode)}
          >
            {RESOLUTION_CODES.map((code) => (
              <option key={code.value} value={code.value}>
                {code.value} — {code.note}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`resolution-summary-${ticketId}`}>
            Resolution summary
          </label>
          <p className="text-xs leading-relaxed text-ink-muted">At least 10 characters — what fixed it, in plain language.</p>
          <textarea
            id={`resolution-summary-${ticketId}`}
            aria-label="Resolution summary"
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink focus:border-brand-500 focus:outline-none"
            value={resolutionSummary}
            onChange={(e) => setResolutionSummary(e.target.value)}
            rows={2}
            placeholder="e.g. Switched the workstation from wifi to the wired dock; VPN now stays connected."
          />
        </div>
        <button
          type="button"
          disabled={!canResolve || isPending}
          onClick={() => mutate({ kind: "resolve", resolutionCode, resolutionSummary: resolutionSummary.trim() })}
          className="self-start rounded-md bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
        >
          Resolve
        </button>
      </div>

      {isError && conflictVersion === null && (
        <p className="mt-3 text-sm text-danger" data-testid="status-transition-error">
          Unable to submit this update. You can try again.
        </p>
      )}
    </div>
  );
}
