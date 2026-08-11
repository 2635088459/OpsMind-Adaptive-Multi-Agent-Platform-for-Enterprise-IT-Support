package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-039 persistence §"Recommended Table"
 * ({@code ticket_phase10_correction_event}): one correction attempt. {@code
 * id} is also the {@code recoveryId} returned to the caller and published
 * in {@code ticket.correction-event-published.v1}. Mirrors {@code
 * ReconciliationCaseRecord} (SPEC-TW-037): {@code completedAt} is left
 * {@code null} on open — domain-rules "Correction events must not delete or
 * rewrite original events" means this SPEC only publishes the correction
 * fact, it never mutates or closes the original history it corrects.
 */
public record CorrectionEventRecord(
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
