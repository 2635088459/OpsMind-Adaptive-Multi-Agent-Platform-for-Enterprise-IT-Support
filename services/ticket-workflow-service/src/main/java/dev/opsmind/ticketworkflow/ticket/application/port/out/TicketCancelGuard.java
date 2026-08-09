package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-029: the ticket row projection needed to classify a Cancel
 * command. Unlike {@link TicketCloseReopenGuard}/{@link
 * TicketResolutionConfirmationGuard}, no resolution-cycle status is
 * carried — {@code current_resolution_cycle_id} is {@code NOT NULL} for
 * every ticket from creation (SPEC-TW-001), and Cancel does not require
 * the cycle to be in any particular status (unlike Close/Confirm/Auto
 * Close, which require it {@code RESOLVED}).
 */
public record TicketCancelGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    String requesterId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String currentAssigneeId,
    UUID currentResolutionCycleId
) {
}
