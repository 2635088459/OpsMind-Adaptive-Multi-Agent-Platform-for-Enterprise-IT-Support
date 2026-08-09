package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

public record EscalateTicketCommand(
    TicketId ticketId,
    EscalationReasonCode escalationReasonCode,
    String escalationReason,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt,
    StepUpProof stepUpProof
) {
}
