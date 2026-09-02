import type { ActionOutcome } from "@/features/conversation/types";

const COPY: Record<ActionOutcome, { title: string; tone: "success" | "info" | "muted" }> = {
  done: { title: "Done — the action completed successfully.", tone: "success" },
  "still-processing": { title: "Still working on it — this may take a little longer.", tone: "info" },
  "awaiting-approval": { title: "Waiting on a human approver before this can proceed.", tone: "info" },
  declined: { title: "You chose not to proceed.", tone: "muted" },
};

/**
 * SPEC-EP-008 §9/§16: renders whichever real outcome came back, honestly —
 * BI-EP-005 forbids rendering `still-processing`/`awaiting-approval` as if
 * they were `done`. No polling/upgrade-to-done logic here: the backend's own
 * bounded wait (SPEC-ARO-040) already resolved to a final outcome by the
 * time this renders; a later real status change would arrive as a new
 * conversation turn, not a mutation of this card.
 */
export function ActionExecutionStatus({ outcome }: { outcome: ActionOutcome }) {
  const copy = COPY[outcome];
  const toneClass =
    copy.tone === "success" ? "border-brand-100 bg-brand-50" : copy.tone === "info" ? "border-border bg-surface-muted" : "border-border bg-surface";

  return (
    <div className={`rounded-xl border p-4 text-sm text-ink ${toneClass}`} data-testid="action-execution-status" data-outcome={outcome}>
      {copy.title}
    </div>
  );
}
