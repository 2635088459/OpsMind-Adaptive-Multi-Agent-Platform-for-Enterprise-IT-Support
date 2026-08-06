package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

public record ResolveTicketWithVerificationCommand(
    TicketId ticketId,
    String verificationEvidenceId,
    ResolutionCode resolutionCode,
    String resolutionSummary,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
