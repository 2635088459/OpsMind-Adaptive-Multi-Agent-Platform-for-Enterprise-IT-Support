package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;

import java.time.Instant;

public record TicketTriageUpdate(
    TicketId ticketId,
    long expectedVersion,
    TicketCategoryId categoryId,
    TicketSubcategoryId subcategoryId,
    TicketPriority priority,
    SupportQueueId supportQueueId,
    String teamId,
    String triagedByActorId,
    Instant triagedAt,
    Instant updatedAt
) {
}
