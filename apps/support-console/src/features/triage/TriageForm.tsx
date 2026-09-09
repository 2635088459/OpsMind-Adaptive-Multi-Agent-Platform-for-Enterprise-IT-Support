import { useState } from "react";
import { useTriageTicket } from "@/features/triage/useTriageTicket";
import { VersionConflictBanner } from "@/features/ticketOps/VersionConflictBanner";
import { CatalogField } from "@/features/catalog/CatalogField";
import { SUPPORT_QUEUES, TICKET_CATEGORIES } from "@/features/catalog/catalog";

const PRIORITIES = [
  { value: "CRITICAL", note: "service down, no workaround" },
  { value: "HIGH", note: "major impact, workaround exists" },
  { value: "MEDIUM", note: "limited impact" },
  { value: "LOW", note: "minor / cosmetic" },
];

/**
 * SPEC-SC-010: a triage form calling the real, already-live
 * `POST /{ticketId}/triage` endpoint. Category and support-queue are now
 * pickers over the platform's real seeded reference data (see
 * `features/catalog/catalog.ts` for why it's a curated list and not a fetch),
 * each keeping an "enter a different ID" escape hatch. `subcategoryId` stays a
 * raw optional field — there is genuinely no subcategory vocabulary seeded
 * anywhere to pick from.
 */
export function TriageForm({
  ticketId,
  initialVersion,
  defaultPriority,
}: {
  ticketId: string;
  initialVersion: number;
  defaultPriority?: string;
}) {
  const { conflictVersion, acknowledgeConflict, mutate, isPending, isError, isSuccess, data } = useTriageTicket(ticketId, initialVersion);
  const [categoryId, setCategoryId] = useState("");
  const [subcategoryId, setSubcategoryId] = useState("");
  const [priority, setPriority] = useState(
    PRIORITIES.some((p) => p.value === defaultPriority) ? (defaultPriority as string) : "HIGH",
  );
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
    <div data-testid="triage-form">
      <p className="text-sm leading-relaxed text-ink-muted">
        Classify this ticket and route it to a queue. This is the same action agent-runtime runs automatically on
        escalation — doing it here overrides that.
      </p>

      {conflictVersion !== null && <VersionConflictBanner currentVersion={conflictVersion} onReload={acknowledgeConflict} />}

      {isSuccess && data && (
        <p className="mt-3 rounded-md border border-brand-100 bg-brand-50 px-3 py-2 text-sm text-ink" data-testid="triage-success">
          Triaged as {data.priority}, category {data.categoryId}.
        </p>
      )}

      <div className="mt-4 flex flex-col gap-4">
        <CatalogField
          label="Category"
          entries={TICKET_CATEGORIES}
          value={categoryId}
          onChange={setCategoryId}
          help="What kind of problem is this? Sets the routing category on the ticket."
        />

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`triage-subcategory-${ticketId}`}>
            Subcategory <span className="font-normal text-ink-muted">(optional)</span>
          </label>
          <p className="text-xs leading-relaxed text-ink-muted">
            Leave blank unless you have a specific subcategory UUID — there is no subcategory list to pick from yet.
          </p>
          <input
            id={`triage-subcategory-${ticketId}`}
            aria-label="Subcategory ID"
            className="rounded-md border border-border bg-surface px-3 py-2 font-mono text-xs text-ink focus:border-brand-500 focus:outline-none"
            value={subcategoryId}
            onChange={(e) => setSubcategoryId(e.target.value)}
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`triage-priority-${ticketId}`}>
            Priority
          </label>
          <select
            id={`triage-priority-${ticketId}`}
            aria-label="Priority"
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink focus:border-brand-500 focus:outline-none"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
          >
            {PRIORITIES.map((p) => (
              <option key={p.value} value={p.value}>
                {p.value} — {p.note}
              </option>
            ))}
          </select>
        </div>

        <CatalogField
          label="Support queue"
          entries={SUPPORT_QUEUES}
          value={supportQueueId}
          onChange={setSupportQueueId}
          help="Which team's queue should own this ticket from here."
        />

        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-ink" htmlFor={`triage-reason-${ticketId}`}>
            Reason
          </label>
          <p className="text-xs leading-relaxed text-ink-muted">Recorded on the ticket timeline — say why this classification.</p>
          <textarea
            id={`triage-reason-${ticketId}`}
            aria-label="Reason"
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink focus:border-brand-500 focus:outline-none"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            placeholder="e.g. reclassifying — this is a VPN issue, not email"
          />
        </div>

        <button
          type="button"
          disabled={!canSubmit || isPending}
          onClick={submit}
          className="mt-1 self-start rounded-md bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
        >
          {isPending ? "Submitting…" : "Submit triage"}
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
