package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

public record UpdateTicketAssignmentResult(
    TicketId ticketId,
    TicketStatus status,
    String teamId,
    SupportQueueId supportQueueId,
    String assigneeId,
    String assigneeDisplayName,
    String reason,
    String updatedBy,
    Instant updatedAt,
    long version,
    boolean replayed
) {
}
