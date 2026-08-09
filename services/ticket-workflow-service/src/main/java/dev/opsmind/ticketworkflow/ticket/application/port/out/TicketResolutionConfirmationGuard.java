package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-026: the ticket row joined with its current resolution-cycle row,
 * mirroring {@link TicketCloseReopenGuard} (SPEC-TW-011) exactly, plus
 * {@code requesterId} — the field this SPEC additionally needs to authorize
 * the ticket's own requester (as opposed to Close, which is IT-support-only
 * and never needs to compare against the requester).
 */
public record TicketResolutionConfirmationGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    String requesterId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String currentAssigneeId,
    UUID currentResolutionCycleId,
    ResolutionCycleStatus resolutionCycleStatus
) {
}
