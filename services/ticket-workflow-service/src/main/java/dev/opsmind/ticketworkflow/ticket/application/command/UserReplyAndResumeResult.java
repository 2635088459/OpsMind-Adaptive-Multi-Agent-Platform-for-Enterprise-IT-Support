package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record UserReplyAndResumeResult(
    TicketId ticketId,
    UUID requestId,
    TicketMessageId messageId,
    TicketStatus previousStatus,
    TicketStatus status,
    Instant answeredAt,
    boolean resumeApplied,
    long version,
    boolean replayed
) {
}
