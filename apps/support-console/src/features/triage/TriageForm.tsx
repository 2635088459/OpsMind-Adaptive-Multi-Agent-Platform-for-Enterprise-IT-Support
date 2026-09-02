import { useState } from "react";
import { useTriageTicket } from "@/features/triage/useTriageTicket";
import { VersionConflictBanner } from "@/features/ticketOps/VersionConflictBanner";

const PRIORITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];

/**
 * SPEC-SC-010: a triage form calling the real, already-live
 * `POST /{ticketId}/triage` endpoint. `categoryId`/`subcategoryId`/
 * `supportQueueId` are raw ID text fields, not pickers — this platform has
 * no catalog-read endpoint for either vocabulary anywhere (an honestly
 * carried-forward gap, not an oversight here).
 */
export function TriageForm({ ticketId, initialVersion }: { ticketId: string; initialVersion: number }) {
  const { conflictVersion, acknowledgeConflict, mutate, isPending, isError, isSuccess, data } = useTriageTicket(ticketId, initialVersion);
  const [categoryId, setCategoryId] = useState("");
  const [subcategoryId, setSubcategoryId] = useState("");
  const [priority, setPriority] = useState(PRIORITIES[1]);
  const [supportQueueId, setSupportQueueId] = useState("");
  const [reason, setReason] = useState("");

  const canSubmit = categoryId.trim() && supportQueueId.trim() && reason.trim().length >= 1;

  function submit() {
    mutate({
      categoryId: categoryId.trim(),
      subcategoryId: subcategoryId.trim() || undefined,
      priority,
      supportQueueId: supportQueueId.trim(),
      reason: reason.trim(),
    });
  }

  return (
    <div className="rounded-xl border border-border bg-surface p-4" data-testid="triage-form">
      <h2 className="text-sm font-medium text-ink">Triage ticket</h2>

      {conflictVersion !== null && <VersionConflictBanner currentVersion={conflictVersion} onReload={acknowledgeConflict} />}

      {isSuccess && data && (
        <p className="mt-2 rounded-md bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="triage-success">
          Triaged as {data.priority}, category {data.categoryId}.
        </p>
      )}

      <div className="mt-3 flex flex-col gap-2">
        <label className="text-xs font-medium text-ink-muted" htmlFor={`triage-category-${ticketId}`}>
          Category ID
        </label>
        <input
          id={`triage-category-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={categoryId}
          onChange={(e) => setCategoryId(e.target.value)}
        />

        <label className="text-xs font-medium text-ink-muted" htmlFor={`triage-subcategory-${ticketId}`}>
          Subcategory ID (optional)
        </label>
        <input
          id={`triage-subcategory-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={subcategoryId}
          onChange={(e) => setSubcategoryId(e.target.value)}
        />

        <label className="text-xs font-medium text-ink-muted" htmlFor={`triage-priority-${ticketId}`}>
          Priority
        </label>
        <select
          id={`triage-priority-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={priority}
          onChange={(e) => setPriority(e.target.value)}
        >
          {PRIORITIES.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>

        <label className="text-xs font-medium text-ink-muted" htmlFor={`triage-queue-${ticketId}`}>
          Support queue ID
        </label>
        <input
          id={`triage-queue-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={supportQueueId}
          onChange={(e) => setSupportQueueId(e.target.value)}
        />

        <label className="text-xs font-medium text-ink-muted" htmlFor={`triage-reason-${ticketId}`}>
          Reason
        </label>
        <textarea
          id={`triage-reason-${ticketId}`}
          className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={2}
        />

        <button
          type="button"
          disabled={!canSubmit || isPending}
          onClick={submit}
          className="mt-1 self-start rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          Submit triage
        </button>
        {isError && conflictVersion === null && (
          <p className="text-sm text-danger" data-testid="triage-error">
            Unable to submit this triage. You can try again.
          </p>
        )}
      </div>
    </div>
  );
}
