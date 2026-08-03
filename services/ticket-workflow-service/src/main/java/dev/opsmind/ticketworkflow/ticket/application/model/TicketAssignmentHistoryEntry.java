package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record TicketAssignmentHistoryEntry(
    UUID assignmentHistoryId,
    TicketId ticketId,
    String action,
    String previousAssigneeId,
    String newAssigneeId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String actorType,
    String actorId,
    String reason,
    Instant occurredAt,
    String correlationId,
    String causationId,
    long resultingVersion
) {
}
