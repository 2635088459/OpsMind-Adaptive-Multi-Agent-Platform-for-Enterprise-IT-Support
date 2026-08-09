package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UpdateTicketAssignmentResponse(
    UUID ticketId,
    String status,
    String teamId,
    UUID supportQueueId,
    String assigneeId,
    String assigneeDisplayName,
    String reason,
    String updatedBy,
    Instant updatedAt,
    long version
) {
}
