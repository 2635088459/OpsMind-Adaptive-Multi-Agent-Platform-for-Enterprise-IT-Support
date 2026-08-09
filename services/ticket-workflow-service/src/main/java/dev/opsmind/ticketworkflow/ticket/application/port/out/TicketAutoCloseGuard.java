package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-027: the ticket row joined with its current resolution-cycle row,
 * mirroring {@link TicketCloseReopenGuard} (SPEC-TW-011), plus {@code
 * autoCloseDueAt} — the field this SPEC additionally needs to recompute
 * eligibility itself rather than trusting the scheduler's own signal.
 */
public record TicketAutoCloseGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String currentAssigneeId,
    UUID currentResolutionCycleId,
    ResolutionCycleStatus resolutionCycleStatus,
    Instant autoCloseDueAt
) {
}
