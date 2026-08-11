package dev.opsmind.ticketworkflow.ticket.application.port.out;

/**
 * SPEC-TW-038 persistence §"Recommended Table": a summary of every prior
 * {@code ticket_phase10_replay_event} attempt for one {@code (ticketId,
 * sourceReference)} pair. Mirrors {@code ReconciliationCaseAttemptSummary}
 * (SPEC-TW-037): {@code totalAttempts} feeds the next row's {@code
 * attempt_number}; {@code hasOpenCase} blocks a second, concurrent replay
 * attempt for the same original event (domain-rules "Replay must be
 * idempotent by both original event id and replay attempt id").
 */
public record ReplayEventAttemptSummary(
    int totalAttempts,
    boolean hasOpenCase
) {
}
