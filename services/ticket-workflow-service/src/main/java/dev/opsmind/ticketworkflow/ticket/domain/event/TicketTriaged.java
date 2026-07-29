package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;

import java.time.Instant;
import java.util.Objects;

public record TicketTriaged(
    TicketId ticketId,
    TicketStatus fromStatus,
    TicketStatus toStatus,
    TicketCategoryId categoryId,
    TicketSubcategoryId subcategoryId,
    TicketPriority priority,
    SupportQueueId supportQueueId,
    String triagedByActorType,
    String triagedByActorId,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketTriaged {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(fromStatus, "fromStatus must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        Objects.requireNonNull(supportQueueId, "supportQueueId must not be null");
        Objects.requireNonNull(triagedByActorType, "triagedByActorType must not be null");
        Objects.requireNonNull(triagedByActorId, "triagedByActorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
