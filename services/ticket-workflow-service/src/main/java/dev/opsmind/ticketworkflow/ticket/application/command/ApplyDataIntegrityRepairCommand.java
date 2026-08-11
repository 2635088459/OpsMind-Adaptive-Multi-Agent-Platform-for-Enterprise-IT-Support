package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;

import java.time.Instant;

/**
 * SPEC-TW-041 api-contract §"Request". Mirrors {@code ReplayEventCommand}
 * (SPEC-TW-038): no {@code ticketId} — the endpoint ({@code
 * /internal/v1/tickets/integrity-repairs}) is not ticket-scoped; the ticket
 * is resolved from the reconciliation case that {@code sourceReference}
 * identifies.
 */
public record ApplyDataIntegrityRepairCommand(
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
