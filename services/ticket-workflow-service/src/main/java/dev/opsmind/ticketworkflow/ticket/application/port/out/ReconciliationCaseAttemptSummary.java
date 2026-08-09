package dev.opsmind.ticketworkflow.ticket.application.port.out;

/**
 * SPEC-TW-037 persistence §"Recommended Table": a summary of every prior
 * {@code ticket_phase10_open_reconciliation_case} attempt for one {@code
 * (ticketId, sourceReference)} pair. {@code totalAttempts} feeds the next
 * row's {@code attempt_number}; {@code hasOpenCase} is true when the most
 * recent attempt (or any attempt) has not yet been closed ({@code
 * completed_at IS NULL} — closure is out of this SPEC's scope, left to
 * SPEC-TW-038 to SPEC-TW-041) and therefore blocks opening a second,
 * concurrent case for the same source reference (api-contract §"Errors":
 * {@code 409 CONFLICT} "attempt ... or source reference conflict").
 */
public record ReconciliationCaseAttemptSummary(
    int totalAttempts,
    boolean hasOpenCase
) {
}
