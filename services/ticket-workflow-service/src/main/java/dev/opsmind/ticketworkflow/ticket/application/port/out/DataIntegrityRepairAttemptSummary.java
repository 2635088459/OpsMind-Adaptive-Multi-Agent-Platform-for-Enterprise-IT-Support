package dev.opsmind.ticketworkflow.ticket.application.port.out;

/**
 * SPEC-TW-041 persistence §"Recommended Table": a summary of every prior
 * {@code ticket_phase10_data_integrity_repair} attempt for one {@code
 * (ticketId, sourceReference)} pair. Mirrors {@code
 * ReplayEventAttemptSummary} (SPEC-TW-038): {@code totalAttempts} feeds the
 * next row's {@code attempt_number}; {@code hasOpenCase} blocks a second,
 * concurrent repair attempt for the same finding (api-contract §"Errors":
 * {@code 409 CONFLICT} "attempt ... or source reference conflict").
 */
public record DataIntegrityRepairAttemptSummary(
    int totalAttempts,
    boolean hasOpenCase
) {
}
