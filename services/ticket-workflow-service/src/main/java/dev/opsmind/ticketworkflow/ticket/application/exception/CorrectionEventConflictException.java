package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-039 api-contract §"Errors": {@code 409 CONFLICT} — a correction
 * event is already open for this ticket and {@code sourceReference}. Mirrors
 * {@code ReconciliationCaseConflictException} (SPEC-TW-037): a concurrent
 * second attempt to correct the same source reference is, by definition, the
 * same already-open correction, so one guard and one exception cover both
 * the "attempt" and "source reference" conflict flavors the api-contract
 * names together. Never a version conflict — domain-rules "Correction events
 * must not delete or rewrite original events": this SPEC never touches the
 * ticket's own optimistic-lock version.
 */
public class CorrectionEventConflictException extends RuntimeException {

    public CorrectionEventConflictException() {
        super("a correction event is already open for this ticket and source reference");
    }
}
