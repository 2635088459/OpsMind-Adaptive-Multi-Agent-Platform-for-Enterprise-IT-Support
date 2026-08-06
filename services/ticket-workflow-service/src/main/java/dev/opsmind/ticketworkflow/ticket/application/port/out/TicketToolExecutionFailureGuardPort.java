package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

/**
 * SPEC-TW-020: same projection shape as {@link TicketToolExecutionGuardPort}
 * (SPEC-TW-019) — the ticket + authorizing {@code GRANTED}/{@code
 * AUTO_APPROVED} approval request a {@code tool.execution.failed.v1} event
 * must match — kept as its own port/adapter (rather than reusing
 * SPEC-TW-019's bean) so each spec has exactly one implementing bean, per
 * this codebase's established one-port-per-spec convention (mirrors {@code
 * TicketApprovalGrantGuardPort}/{@code TicketApprovalRejectionGuardPort}/
 * {@code TicketApprovalExpirationGuardPort} each having their own adapter
 * despite reading the same {@code ticket_approval_requests} table).
 */
public interface TicketToolExecutionFailureGuardPort {

    Optional<TicketToolExecutionGuard> loadGuard(TicketId ticketId, String workflowId, String actionId);
}
