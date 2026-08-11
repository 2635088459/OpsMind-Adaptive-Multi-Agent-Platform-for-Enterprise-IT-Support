package dev.opsmind.ticketworkflow.ticket.application.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-041 persistence §"Recommended Table"
 * ({@code ticket_phase10_data_integrity_repair}): one repair attempt.
 * {@code id} is also the {@code recoveryId} returned to the caller and
 * published in {@code ticket.integrity-repair-applied.v1}. Mirrors {@code
 * ReplayEventRecord} (SPEC-TW-038): {@code completedAt} is left {@code
 * null} on open — domain-rules "Repair must first produce a scan finding
 * and repair plan before controlled repair execution" means this SPEC only
 * records that a controlled repair was applied against an existing finding,
 * it never itself closes the underlying case.
 */
public record DataIntegrityRepairRecord(
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
