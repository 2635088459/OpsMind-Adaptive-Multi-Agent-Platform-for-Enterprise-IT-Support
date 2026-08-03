package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;

public record TicketStatusChanged(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    String actorType,
    String actorId,
    String reason,
    String transitionId,
    String reasonCode,
    Instant waitingForRequesterSince,
    String approvalReference,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketStatusChanged {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
