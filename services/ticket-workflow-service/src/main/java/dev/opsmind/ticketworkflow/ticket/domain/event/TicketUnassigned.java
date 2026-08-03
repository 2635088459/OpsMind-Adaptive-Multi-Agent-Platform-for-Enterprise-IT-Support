package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;

public record TicketUnassigned(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String previousAssigneeId,
    String actorType,
    String actorId,
    String reason,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketUnassigned {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(previousAssigneeId, "previousAssigneeId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
