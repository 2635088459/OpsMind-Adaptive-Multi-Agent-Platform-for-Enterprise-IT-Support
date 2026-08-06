package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.List;

public record TicketToolResultUnknownUpdate(
    TicketId ticketId,
    long expectedVersion,
    String workflowId,
    String actionId,
    String authorizationReference,
    String toolExecutionId,
    String unknownReason,
    List<String> evidenceReferences,
    Instant observedAt,
    String eventId,
    Instant updatedAt
) {
}
