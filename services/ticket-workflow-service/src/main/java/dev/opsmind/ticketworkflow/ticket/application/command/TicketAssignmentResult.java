package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

/**
 * Shared result shape for assign/reassign/unassign (API contract §5: all
 * three return the same response envelope). {@code assigneeId}/{@code
 * assigneeDisplayName}/{@code assignedAt} are all {@code null} for a
 * successful unassign.
 */
public record TicketAssignmentResult(
    TicketId ticketId,
    TicketStatus status,
    String assigneeId,
    String assigneeDisplayName,
    Instant assignedAt,
    long version,
    boolean idempotencyReplayed
) {
}
