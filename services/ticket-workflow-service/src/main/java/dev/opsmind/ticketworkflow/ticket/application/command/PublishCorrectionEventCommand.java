package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/**
 * SPEC-TW-039 api-contract §"Request". Mirrors {@code
 * OpenReconciliationCaseCommand} (SPEC-TW-037) exactly: ticket-scoped, {@code
 * reasonCode}/{@code decision} reuse {@code ReconciliationReasonCode}/{@code
 * ReconciliationDecision} — SPEC-TW-037 to SPEC-TW-041 share the same Phase
 * 10 recovery-request template and decision vocabulary.
 */
public record PublishCorrectionEventCommand(
    TicketId ticketId,
    ReconciliationReasonCode reasonCode,
    String reason,
    String sourceReference,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
