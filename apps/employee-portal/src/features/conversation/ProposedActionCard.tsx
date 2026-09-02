import type { PendingAction } from "@/features/conversation/conversationStore";

interface ProposedActionCardProps {
  action: PendingAction;
  onConfirm: (actionId: string) => void;
  onDecline: (actionId: string) => void;
  disabled: boolean;
}

/**
 * SPEC-EP-007: renders `summary` in FULL — BI-EP-007 forbids any truncation
 * styling (no `text-overflow: ellipsis`, no `overflow: hidden` + fixed
 * height, no `line-clamp`) regardless of viewport width. The className list
 * below is deliberately auditable by ProposedActionCard.test.tsx's own
 * assertion against exactly this string, not just "looks fine visually".
 */
export function ProposedActionCard({ action, onConfirm, onDecline, disabled }: ProposedActionCardProps) {
  return (
    <div className="rounded-xl border border-border bg-surface p-5 shadow-sm" data-testid="proposed-action-card">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-muted">Proposed action · {action.riskLevel} risk</p>
      <p className="mt-2 whitespace-pre-wrap break-words text-sm text-ink" data-testid="proposed-action-summary">
        {action.summary}
      </p>
      <div className="mt-4 flex gap-3">
        <button
          type="button"
          disabled={disabled}
          onClick={() => onConfirm(action.actionId)}
          className="rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          Confirm
        </button>
        <button
          type="button"
          disabled={disabled}
          onClick={() => onDecline(action.actionId)}
          className="rounded-md border border-border bg-transparent px-4 py-2 text-sm font-medium text-ink hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60"
        >
          Not now
        </button>
      </div>
    </div>
  );
}
