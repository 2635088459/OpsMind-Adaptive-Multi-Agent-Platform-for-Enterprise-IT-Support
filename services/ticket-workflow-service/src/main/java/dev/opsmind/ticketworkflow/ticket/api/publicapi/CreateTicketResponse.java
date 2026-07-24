package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import java.time.Instant;
import java.util.UUID;

public record CreateTicketResponse(
    UUID ticketId,
    String displayId,
    String status,
    Instant createdAt,
    long version
) {
}
