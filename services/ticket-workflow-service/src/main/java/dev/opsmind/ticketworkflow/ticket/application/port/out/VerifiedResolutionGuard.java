package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-025: the ticket row joined with its {@code
 * current_resolution_cycle_id} row, mirroring {@link TicketResolveGuard}
 * (SPEC-TW-010) exactly — the Application layer still needs cycle
 * existence/incompleteness before invoking {@code
 * Ticket.resolveWithVerification(...)}, it just no longer needs {@code
 * teamId} (this is a trusted internal-service endpoint, not a Support Queue
 * membership check).
 */
public record VerifiedResolutionGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String currentAssigneeId,
    UUID currentResolutionCycleId,
    ResolutionCycleStatus resolutionCycleStatus
) {
}
