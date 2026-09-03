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
    <div className="rounded-[10px] border border-border bg-surface p-3.5" data-testid="proposed-action-card">
      <p className="font-mono text-[10.5px] font-semibold tracking-wide text-faint uppercase">
        Proposed action · {action.riskLevel} risk
      </p>
      <p className="mt-2 text-sm text-ink whitespace-pre-wrap break-words" data-testid="proposed-action-summary">
        {action.summary}
      </p>
      <div className="mt-3 flex gap-2">
        <button
          type="button"
          disabled={disabled}
          onClick={() => onConfirm(action.actionId)}
          className="rounded-lg bg-brand-600 px-3.5 py-1.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          Confirm
        </button>
        <button
          type="button"
          disabled={disabled}
          onClick={() => onDecline(action.actionId)}
          className="rounded-lg border border-border bg-transparent px-3.5 py-1.5 text-sm font-semibold text-ink-muted hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60"
        >
          Not now
        </button>
      </div>
    </div>
  );
}
