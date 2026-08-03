package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-011: the ticket row joined with its current resolution-cycle row,
 * shared by Close and Reopen (both need the identical projection — mirrors
 * SPEC-TW-010's {@code TicketResolveGuard}). {@code closedAt} is only
 * populated when {@code status == CLOSED}; Reopen's optional reopen-window
 * check reads it directly rather than issuing a second query.
 */
public record TicketCloseReopenGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String teamId,
    String currentAssigneeId,
    UUID currentResolutionCycleId,
    ResolutionCycleStatus resolutionCycleStatus,
    int resolutionCycleNumber,
    int reopenCount,
    Instant closedAt
) {
}
