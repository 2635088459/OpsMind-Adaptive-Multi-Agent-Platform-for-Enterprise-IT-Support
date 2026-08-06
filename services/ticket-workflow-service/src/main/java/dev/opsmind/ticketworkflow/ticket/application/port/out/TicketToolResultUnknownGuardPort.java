package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

/**
 * SPEC-TW-021: same projection shape as {@link TicketToolExecutionGuardPort}
 * (SPEC-TW-019) / {@link TicketToolExecutionFailureGuardPort} (SPEC-TW-020)
 * — kept as its own port/adapter so each spec has exactly one implementing
 * bean, per this codebase's established one-port-per-spec convention.
 */
public interface TicketToolResultUnknownGuardPort {

    Optional<TicketToolExecutionGuard> loadGuard(TicketId ticketId, String workflowId, String actionId);
}
