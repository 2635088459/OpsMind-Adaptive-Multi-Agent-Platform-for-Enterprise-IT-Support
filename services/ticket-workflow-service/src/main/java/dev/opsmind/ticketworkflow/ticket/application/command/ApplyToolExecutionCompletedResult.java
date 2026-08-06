package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionCompletedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public record ApplyToolExecutionCompletedResult(
    ApplyToolExecutionCompletedOutcome outcome,
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    String toolExecutionId,
    String toolResultId,
    long version
) {

    public static ApplyToolExecutionCompletedResult applied(TicketToolExecutionCompletedApplied event) {
        return new ApplyToolExecutionCompletedResult(
            ApplyToolExecutionCompletedOutcome.APPLIED, event.ticketId(), event.previousStatus(), event.newStatus(),
            event.toolExecutionId(), event.toolResultId(), event.aggregateVersion()
        );
    }

    public static ApplyToolExecutionCompletedResult duplicate(TicketId ticketId, String toolExecutionId) {
        return new ApplyToolExecutionCompletedResult(ApplyToolExecutionCompletedOutcome.DUPLICATE, ticketId, null, null, toolExecutionId, null, 0);
    }

    public static ApplyToolExecutionCompletedResult stale(TicketId ticketId, String toolExecutionId) {
        return new ApplyToolExecutionCompletedResult(ApplyToolExecutionCompletedOutcome.STALE, ticketId, null, null, toolExecutionId, null, 0);
    }
}
