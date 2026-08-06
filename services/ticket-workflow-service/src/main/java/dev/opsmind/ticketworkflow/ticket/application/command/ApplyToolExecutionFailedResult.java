package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionFailedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public record ApplyToolExecutionFailedResult(
    ApplyToolExecutionFailedOutcome outcome,
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    String toolExecutionId,
    String failureClass,
    long version
) {

    public static ApplyToolExecutionFailedResult applied(TicketToolExecutionFailedApplied event) {
        ApplyToolExecutionFailedOutcome outcome = event.newStatus() == TicketStatus.FAILED
            ? ApplyToolExecutionFailedOutcome.APPLIED_PIPELINE_FAILURE
            : ApplyToolExecutionFailedOutcome.APPLIED_SAFE_FAILURE;
        return new ApplyToolExecutionFailedResult(
            outcome, event.ticketId(), event.previousStatus(), event.newStatus(),
            event.toolExecutionId(), event.failureClass(), event.aggregateVersion()
        );
    }

    public static ApplyToolExecutionFailedResult duplicate(TicketId ticketId, String toolExecutionId) {
        return new ApplyToolExecutionFailedResult(ApplyToolExecutionFailedOutcome.DUPLICATE, ticketId, null, null, toolExecutionId, null, 0);
    }

    public static ApplyToolExecutionFailedResult stale(TicketId ticketId, String toolExecutionId) {
        return new ApplyToolExecutionFailedResult(ApplyToolExecutionFailedOutcome.STALE, ticketId, null, null, toolExecutionId, null, 0);
    }
}
