package dev.opsmind.ticketworkflow.ticket.application.port.out;

/**
 * SPEC-TW-039 persistence §"Recommended Table": a summary of every prior
 * {@code ticket_phase10_correction_event} attempt for one {@code (ticketId,
 * sourceReference)} pair. Mirrors {@code ReconciliationCaseAttemptSummary}
 * (SPEC-TW-037): {@code totalAttempts} feeds the next row's {@code
 * attempt_number}; {@code hasOpenCase} blocks a second, concurrent
 * correction for the same source reference (api-contract §"Errors": {@code
 * 409 CONFLICT} "attempt ... or source reference conflict").
 */
public record CorrectionEventAttemptSummary(
    int totalAttempts,
    boolean hasOpenCase
) {
}
