package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record AddTicketMessageCommand(
    TicketId ticketId,
    TicketMessageType messageType,
    MessageContent content,
    ActorContext actor,
    Set<ApplicationCode> allowedApplicationCodes,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {

    public AddTicketMessageCommand {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        allowedApplicationCodes = allowedApplicationCodes == null ? Set.of() : Set.copyOf(allowedApplicationCodes);
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must be 1-128 characters");
        }
    }
}
