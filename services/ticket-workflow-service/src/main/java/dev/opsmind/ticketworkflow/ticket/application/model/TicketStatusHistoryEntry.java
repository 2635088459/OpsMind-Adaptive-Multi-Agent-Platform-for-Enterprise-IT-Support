package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record TicketStatusHistoryEntry(
    UUID historyId,
    TicketId ticketId,
    TicketStatus fromStatus,
    TicketStatus toStatus,
    String transitionId,
    String reasonCode,
    String actorType,
    String actorId,
    String sourceCommandId,
    String sourceEventId,
    String workflowId,
    long aggregateVersion,
    Instant occurredAt
) {
}
