package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

public record ConfirmResolutionCommand(
    TicketId ticketId,
    ResolutionConfirmationReasonCode reasonCode,
    String reason,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
