package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolResultUnknownRecorded;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public record ApplyToolResultUnknownResult(
    ApplyToolResultUnknownOutcome outcome,
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    String toolExecutionId,
    boolean reconciliationRequired,
    long version
) {

    public static ApplyToolResultUnknownResult recorded(TicketToolResultUnknownRecorded event) {
        return new ApplyToolResultUnknownResult(
            ApplyToolResultUnknownOutcome.RECORDED_UNKNOWN, event.ticketId(), event.previousStatus(), event.newStatus(),
            event.toolExecutionId(), event.reconciliationRequired(), event.aggregateVersion()
        );
    }

    public static ApplyToolResultUnknownResult duplicate(TicketId ticketId, String toolExecutionId) {
        return new ApplyToolResultUnknownResult(ApplyToolResultUnknownOutcome.DUPLICATE, ticketId, null, null, toolExecutionId, false, 0);
    }

    public static ApplyToolResultUnknownResult stale(TicketId ticketId, String toolExecutionId) {
        return new ApplyToolResultUnknownResult(ApplyToolResultUnknownOutcome.STALE, ticketId, null, null, toolExecutionId, false, 0);
    }

    public static ApplyToolResultUnknownResult conflictRequiresReconciliation(TicketId ticketId, String toolExecutionId) {
        return new ApplyToolResultUnknownResult(ApplyToolResultUnknownOutcome.CONFLICT_REQUIRES_RECONCILIATION, ticketId, null, null, toolExecutionId, true, 0);
    }
}
