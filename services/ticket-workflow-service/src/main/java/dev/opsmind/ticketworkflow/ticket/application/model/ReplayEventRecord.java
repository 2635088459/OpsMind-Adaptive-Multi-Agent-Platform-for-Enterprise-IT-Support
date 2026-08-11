package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-038 persistence §"Recommended Table"
 * ({@code ticket_phase10_replay_event}): one replay attempt. {@code id} is
 * also the {@code recoveryId} returned to the caller and published in {@code
 * ticket.event-replay-recorded.v1}. Mirrors {@code ReconciliationCaseRecord}
 * (SPEC-TW-037): {@code completedAt} is left {@code null} on open — this
 * SPEC only records that a replay was applied, it never itself closes the
 * attempt (domain-rules: "must not directly repair business state").
 */
public record ReplayEventRecord(
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
