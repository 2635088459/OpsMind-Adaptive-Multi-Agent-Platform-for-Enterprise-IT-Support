package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserReplyAndResumeCommand(
    TicketId ticketId,
    UUID requestId,
    MessageContent body,
    List<String> attachmentIds,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {

    public UserReplyAndResumeCommand {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}
