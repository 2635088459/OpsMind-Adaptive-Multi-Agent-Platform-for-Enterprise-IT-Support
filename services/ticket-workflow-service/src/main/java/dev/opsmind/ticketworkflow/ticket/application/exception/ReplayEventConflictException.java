package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-038 api-contract §"Errors": {@code 409 CONFLICT} — a replay attempt
 * is already open for this {@code sourceReference}. domain-rules "Replay
 * must be idempotent by both original event id and replay attempt id": a
 * second, concurrent open attempt against the same original event id is
 * exactly the case this guards against (mirrors {@code
 * ReconciliationCaseConflictException}, SPEC-TW-037).
 */
public class ReplayEventConflictException extends RuntimeException {

    public ReplayEventConflictException() {
        super("a replay attempt is already open for this source reference");
    }
}
