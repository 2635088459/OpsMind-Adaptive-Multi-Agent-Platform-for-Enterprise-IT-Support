package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;

import java.time.Instant;

public record TriageTicketResult(
    TicketId ticketId,
    TicketStatus status,
    TicketCategoryId categoryId,
    TicketSubcategoryId subcategoryId,
    TicketPriority priority,
    SupportQueueId supportQueueId,
    String triagedBy,
    Instant triagedAt,
    long version,
    boolean idempotencyReplayed
) {
}
