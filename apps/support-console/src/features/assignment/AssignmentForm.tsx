import { useState } from "react";
import { useAssignTicket } from "@/features/assignment/useAssignTicket";
import { VersionConflictBanner } from "@/features/ticketOps/VersionConflictBanner";
import { CatalogField } from "@/features/catalog/CatalogField";
import { SUPPORT_AGENTS, agentLabel } from "@/features/catalog/catalog";
import { useAuthStore } from "@/store/authStore";
import { decodeJwtPayload } from "@/lib/jwt";

function useOperator(): { sub: string; name: string } | null {
  const accessToken = useAuthStore((state) => state.accessToken);
  if (!accessToken) return null;
  const claims = decodeJwtPayload(accessToken);
  const sub = typeof claims?.sub === "string" ? claims.sub : null;
  if (!sub) return null;
  const name = typeof claims?.preferred_username === "string" ? claims.preferred_username : "me";
  return { sub, name };
}

/**
 * SPEC-SC-011: assign / reassign / unassign against the real endpoints. The
 * assignee is picked from the platform's real seeded support agents (see
 * `features/catalog/catalog.ts`), with an "assign to me" shortcut off the
 * signed-in operator's own token and an "enter a different ID" escape hatch.
 * `initiallyAssigned` decides whether the picker's action targets `assign` or
 * `reassign`; a successful response's `assignee` is the source of truth for
 * every later submission in the same session, not the original prop.
 */
export function AssignmentForm({
  ticketId,
  initialVersion,
  initiallyAssigned,
  currentAgentId = null,
  currentTeamId = null,
}: {
  ticketId: string;
  initialVersion: number;
  initiallyAssigned: boolean;
  currentAgentId?: string | null;
  currentTeamId?: string | null;
}) {
  const { conflictVersion, acknowledgeConflict, mutate, isPending, isError, isSuccess, data } = useAssignTicket(ticketId, initialVersion);
  const operator = useOperator();
  const [assigneeId, setAssigneeId] = useState("");
  const [reason, setReason] = useState("");

  const isAssigned = data ? data.assignee !== null : initiallyAssigned;
  // What the ticket currently shows — the mutation response wins once we have one.
  const currentOwner = data
    ? data.assignee
      ? data.assignee.displayName
      : null
    : currentAgentId
      ? agentLabel(currentAgentId)
      : currentTeamId
        ? `${currentTeamId} (team)`
        : null;
  const canSubmitAssign = assigneeId.trim() && reason.trim().length >= 3;
  const canSubmitUnassign = reason.trim().length >= 3;

  return (
    <div data-testid="assignment-form">
      <p className="text-sm text-ink" data-testid="assignment-current">
        Currently: <span className="font-medium">{currentOwner ?? "Unassigned"}</span>
      </p>
      <p className="mt-1 text-sm leading-relaxed text-ink-muted">
        {isAssigned
          ? "Hand it to someone else, or release it back to the queue."
          : "Give this ticket an owner. They must belong to the ticket's support queue."}
      </p>

      {conflictVersion !== null && <VersionConflictBanner currentVersion={conflictVersion} onReload={acknowledgeConflict} />}

      {isSuccess && data && (
        <p className="mt-3 rounded-md border border-brand-100 bg-brand-50 px-3 py-2 text-sm text-ink" data-testid="assignment-success">
          {data.assignee ? `Assigned to ${data.assignee.displayName}.` : "Unassigned."}
        </p>
      )}

      <div className="mt-4 flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <CatalogField
            label="Assignee"
            entries={SUPPORT_AGENTS}
            value={assigneeId}
            onChange={setAssigneeId}
            help="Who should own this ticket."
          />
          {operator && (
            <button
              type="button"
              onClick={() => setAssigneeId(operator.sub)}
              className="self-start text-xs font-medium text-brand-600 hover:underline"
            >
              Assign to me ({operator.name})
            </button>
          )}
        </div>

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`assignment-reason-${ticketId}`}>
            Reason
          </label>
          <p className="text-xs leading-relaxed text-ink-muted">At least 3 characters. Recorded on the ticket timeline.</p>
          <textarea
            id={`assignment-reason-${ticketId}`}
            aria-label="Reason"
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink focus:border-brand-500 focus:outline-none"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            placeholder={isAssigned ? "e.g. rebalancing load to the on-call agent" : "e.g. picking this up"}
          />
        </div>

        <div className="flex gap-2">
          <button
            type="button"
            disabled={!canSubmitAssign || isPending}
            onClick={() => mutate({ mode: isAssigned ? "reassign" : "assign", assigneeId: assigneeId.trim(), reason: reason.trim() })}
            className="rounded-md bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
          >
            {isAssigned ? "Reassign" : "Assign"}
          </button>
          {isAssigned && (
            <button
              type="button"
              disabled={!canSubmitUnassign || isPending}
              onClick={() => mutate({ mode: "unassign", reason: reason.trim() })}
              className="rounded-md border border-border bg-surface px-4 py-2 text-sm font-semibold text-ink hover:bg-surface-muted disabled:opacity-50"
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
