package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

public record CancelTicketCommand(
    TicketId ticketId,
    CancelReasonCode cancelReasonCode,
    String cancelReason,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt,
    StepUpProof stepUpProof
) {
}
