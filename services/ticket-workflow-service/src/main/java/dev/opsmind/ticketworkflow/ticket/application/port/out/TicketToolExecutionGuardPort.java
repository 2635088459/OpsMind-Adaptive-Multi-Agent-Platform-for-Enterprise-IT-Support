package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

public interface TicketToolExecutionGuardPort {

    /**
     * Loads the most recently decided {@code GRANTED}/{@code AUTO_APPROVED}
     * approval request for this ticket, workflow, and action — the
     * authorization a {@code tool.execution.completed.v1} event for the
     * same triple must match.
     */
    Optional<TicketToolExecutionGuard> loadGuard(TicketId ticketId, String workflowId, String actionId);
}
