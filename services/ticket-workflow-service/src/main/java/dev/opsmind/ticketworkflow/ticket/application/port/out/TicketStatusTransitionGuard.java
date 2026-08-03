package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

public record TicketStatusTransitionGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String teamId,
    String currentAssigneeId
) {
}
