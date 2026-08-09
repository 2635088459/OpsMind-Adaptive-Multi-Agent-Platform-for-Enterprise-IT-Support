package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Set;

public record TicketAssignmentRouteUpdate(
    TicketId ticketId,
    long expectedVersion,
    Set<TicketStatus> requiredCurrentStatuses,
    String newTeamId,
    SupportQueueId newSupportQueueId,
    String newAssigneeId,
    Instant updatedAt
) {
}
