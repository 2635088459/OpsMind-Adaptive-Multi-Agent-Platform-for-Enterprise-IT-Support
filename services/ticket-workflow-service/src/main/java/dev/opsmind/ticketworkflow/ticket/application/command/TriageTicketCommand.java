package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;

import java.time.Instant;
import java.util.Set;

public record TriageTicketCommand(
    TicketId ticketId,
    TicketCategoryId categoryId,
    TicketSubcategoryId subcategoryId,
    TicketPriority priority,
    SupportQueueId supportQueueId,
    String reason,
    long expectedVersion,
    ActorContext actor,
    Set<String> allowedTeamIds,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
