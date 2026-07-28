package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

public record AddTicketMessageResult(
    TicketMessageId messageId,
    TicketId ticketId,
    TicketMessageType messageType,
    MessageVisibility visibility,
    String authorType,
    String content,
    Instant createdAt,
    long version,
    boolean idempotencyReplayed
) {
}
