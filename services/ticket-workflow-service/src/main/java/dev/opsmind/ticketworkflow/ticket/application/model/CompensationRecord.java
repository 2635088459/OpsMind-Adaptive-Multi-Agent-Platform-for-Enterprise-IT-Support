package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.CompensationAction;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-040 persistence §"Recommended Table"
 * ({@code ticket_phase10_compensation}): one compensation attempt. {@code
 * id} is also the {@code recoveryId} returned to the caller and published
 * in {@code ticket.compensation-executed.v1}. Extends {@code
 * ReconciliationCaseRecord}'s (SPEC-TW-037) shape with {@code
 * compensationAction} — domain-rules "Compensation must select a defined
 * action." {@code completedAt} is left {@code null} on open: this SPEC only
 * records that a compensating action was executed, it never itself repairs
 * the ticket's business state (domain-rules: "cannot run arbitrary SQL or
 * arbitrary state mutation").
 */
public record CompensationRecord(
    UUID id,
    TicketId ticketId,
    CompensationAction compensationAction,
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
