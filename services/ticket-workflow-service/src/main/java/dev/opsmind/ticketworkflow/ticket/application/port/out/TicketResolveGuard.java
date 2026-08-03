package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-010 domain-rules §11 step 3/7: the ticket row joined with its
 * {@code current_resolution_cycle_id} row so the Application layer can
 * validate cycle existence/incompleteness before invoking {@code
 * Ticket.resolve(...)}. {@code resolutionCycleStatus} is {@code null} when
 * the ticket carries no resolution-cycle pointer at all, or the pointer is
 * dangling (no matching row) — both map to {@code RESOLUTION_CYCLE_NOT_FOUND}.
 */
public record TicketResolveGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String teamId,
    String currentAssigneeId,
    UUID currentResolutionCycleId,
    ResolutionCycleStatus resolutionCycleStatus
) {
}
