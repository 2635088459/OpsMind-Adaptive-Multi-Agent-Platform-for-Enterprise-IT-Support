package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;

import java.time.Instant;
import java.util.Objects;

public record CreateTicketCommand(
    TicketTitle title,
    TicketDescription description,
    ApplicationCode applicationCode,
    TicketSource source,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {

    public CreateTicketCommand {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(applicationCode, "applicationCode must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must be 1-128 characters");
        }
    }
}
