package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/**
 * SPEC-TW-037 api-contract §"Request". {@code commandId} doubles as the
 * outbound event's {@code causationId} (domain-rules: "Commands record
 * actor, reason, correlationId, and causationId") — mirrors {@code
 * AutoCloseTicketCommand}'s (SPEC-TW-027) {@code commandId} usage.
 */
public record OpenReconciliationCaseCommand(
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
