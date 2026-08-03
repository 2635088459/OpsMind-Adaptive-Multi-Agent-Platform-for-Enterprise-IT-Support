package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

public record TicketUserReplyResumeUpdate(
    TicketId ticketId,
    long expectedVersion,
    UUID requestId,
    UUID answeredMessageId,
    Instant answeredAt,
    Instant updatedAt
) {
}
