package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-037 persistence §"Recommended Table"
 * ({@code ticket_phase10_open_reconciliation_case}): one recovery attempt.
 * {@code id} is also the {@code recoveryId} returned to the caller and
 * published in {@code ticket.reconciliation-case-opened.v1}. {@code
 * completedAt} is left {@code null} on open — this SPEC only opens a case,
 * it never closes one (domain-rules: "must not directly repair business
 * state"); closure is a later recovery phase's responsibility.
 */
public record ReconciliationCaseRecord(
    UUID id,
    TicketId ticketId,
    String sourceReference,
    ReconciliationDecision decision,
    ReconciliationReasonCode reasonCode,
    String reason,
    String actorId,
    String correlationId,
    String causationId,
    int attemptNumber,
    Instant createdAt,
    Instant completedAt
) {
}
