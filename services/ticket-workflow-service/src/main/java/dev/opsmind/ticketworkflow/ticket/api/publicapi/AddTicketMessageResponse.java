package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches {@code schemas/add-message-response.schema.json} (SPEC-TW-004
 * §16).
 */
public record AddTicketMessageResponse(
    UUID messageId,
    UUID ticketId,
    String messageType,
    String visibility,
    String authorType,
    String content,
    Instant createdAt,
    long version
) {
}
