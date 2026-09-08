import { useAiLog } from "@/features/ailog/useAiLog";
import { ApprovalCard } from "@/features/approval/ApprovalCard";

/**
 * SPEC-SC-008 / UC-SC-02 §3: "if a pending approval request exists, the
 * approval card is shown alongside" the AI processing log. The
 * approval-request id is not something this console holds on its own — it
 * comes from this ticket's real `GovernanceAuditRecordResponse.approvalRequestId`
 * (SPEC-PG-030), surfaced by `useAiLog`. Reuses the exact same 3 cached
 * queries `AiLogPanel` already runs on this ticket (identical react-query
 * keys), so mounting this next to it costs no extra fetch.
 *
 * An `ApprovalCard` renders for every distinct linked approval — most often
 * zero (no approval was ever requested), occasionally one; `ApprovalCard`
 * itself shows the real current status, so a since-decided request just
 * renders as "Granted"/"Denied" rather than being hidden here.
 */
export function TicketApprovals({ ticketId }: { ticketId: string }) {
  const { isLoading, approvalRequestIds } = useAiLog(ticketId, null);

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4" data-testid="ticket-approvals-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  if (approvalRequestIds.length === 0) {
    return (
      <p className="text-sm text-ink-muted" data-testid="ticket-approvals-empty">
        No approval was requested for this ticket.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-4" data-testid="ticket-approvals">
      {approvalRequestIds.map((id) => (
        <ApprovalCard key={id} approvalRequestId={id} />
      ))}
    </div>
  );
}
