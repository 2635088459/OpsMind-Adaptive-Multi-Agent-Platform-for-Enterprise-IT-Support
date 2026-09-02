import { useState } from "react";
import { useAssignTicket } from "@/features/assignment/useAssignTicket";
import { VersionConflictBanner } from "@/features/ticketOps/VersionConflictBanner";

/**
 * SPEC-SC-011: an assignee-picker (a raw agent-identity text field — domain
 * 01's own user-directory has no lookup endpoint wired into this frontend
 * either, the same honestly-carried gap as triage's category/queue IDs)
 * calling the real assign/reassign/unassign endpoints. `initiallyAssigned`
 * decides whether the picker's own action targets `assign` or `reassign`;
 * a successful response's own `assignee` field is the source of truth for
 * every later submission in the same session, not the original prop.
 */
export function AssignmentForm({ ticketId, initialVersion, initiallyAssigned }: { ticketId: string; initialVersion: number; initiallyAssigned: boolean }) {
  const { conflictVersion, acknowledgeConflict, mutate, isPending, isError, isSuccess, data } = useAssignTicket(ticketId, initialVersion);
  const [assigneeId, setAssigneeId] = useState("");
  const [reason, setReason] = useState("");

  const isAssigned = data ? data.assignee !== null : initiallyAssigned;
  const canSubmitAssign = assigneeId.trim() && reason.trim().length >= 3;
  const canSubmitUnassign = reason.trim().length >= 3;

  return (
    <div className="rounded-xl border border-border bg-surface p-4" data-testid="assignment-form">
      <h2 className="text-sm font-medium text-ink">{isAssigned ? "Reassign ticket" : "Assign ticket"}</h2>

      {conflictVersion !== null && <VersionConflictBanner currentVersion={conflictVersion} onReload={acknowledgeConflict} />}

      {isSuccess && data && (
        <p className="mt-2 rounded-md bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="assignment-success">
          {data.assignee ? `Assigned to ${data.assignee.displayName}.` : "Unassigned."}
        </p>
      )}

      <div className="mt-3 flex flex-col gap-2">
        <label className="text-xs font-medium text-ink-muted" htmlFor={`assignee-${ticketId}`}>
          Assignee ID
        </label>
        <input
          id={`assignee-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={assigneeId}
          onChange={(e) => setAssigneeId(e.target.value)}
        />

        <label className="text-xs font-medium text-ink-muted" htmlFor={`assignment-reason-${ticketId}`}>
          Reason
        </label>
        <textarea
          id={`assignment-reason-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={2}
        />

        <div className="flex gap-2">
          <button
            type="button"
            disabled={!canSubmitAssign || isPending}
            onClick={() => mutate({ mode: isAssigned ? "reassign" : "assign", assigneeId: assigneeId.trim(), reason: reason.trim() })}
            className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            {isAssigned ? "Reassign" : "Assign"}
          </button>
          {isAssigned && (
            <button
              type="button"
              disabled={!canSubmitUnassign || isPending}
              onClick={() => mutate({ mode: "unassign", reason: reason.trim() })}
              className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium text-ink disabled:opacity-50"
            >
              Unassign
            </button>
          )}
        </div>
        {isError && conflictVersion === null && (
          <p className="text-sm text-danger" data-testid="assignment-error">
            Unable to submit this assignment. You can try again.
          </p>
        )}
      </div>
    </div>
  );
}
