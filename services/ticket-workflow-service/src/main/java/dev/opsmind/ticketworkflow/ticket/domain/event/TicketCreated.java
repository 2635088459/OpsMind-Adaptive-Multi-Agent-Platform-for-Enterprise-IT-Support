package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;

import java.time.Instant;
import java.util.Objects;

public record TicketCreated(
    TicketId ticketId,
    TicketDisplayId displayId,
    RequesterId requesterId,
    ApplicationCode applicationCode,
    TicketSource source,
    long aggregateVersion,
    Instant createdAt
) implements TicketDomainEvent {

    public TicketCreated {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(displayId, "displayId must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(applicationCode, "applicationCode must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
