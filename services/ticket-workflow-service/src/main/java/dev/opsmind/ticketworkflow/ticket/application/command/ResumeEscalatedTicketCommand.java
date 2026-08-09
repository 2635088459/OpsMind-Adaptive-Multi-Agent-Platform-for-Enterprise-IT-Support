package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

public record ResumeEscalatedTicketCommand(
    TicketId ticketId,
    EscalationResumeReasonCode resumeReasonCode,
    String resumeReason,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
