package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;

import java.time.Instant;

/**
 * SPEC-TW-038 api-contract §"Request". Unlike {@code
 * OpenReconciliationCaseCommand} (SPEC-TW-037), there is no {@code ticketId}
 * — the endpoint ({@code /internal/v1/tickets/events/replay}) is not
 * ticket-scoped; the ticket is resolved from the original event that {@code
 * sourceReference} identifies. {@code reasonCode}/{@code decision} reuse
 * {@code ReconciliationReasonCode}/{@code ReconciliationDecision}: SPEC-TW-037
 * to SPEC-TW-041 share the same Phase 10 recovery-request template and
 * decision vocabulary.
 */
public record ReplayEventCommand(
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
