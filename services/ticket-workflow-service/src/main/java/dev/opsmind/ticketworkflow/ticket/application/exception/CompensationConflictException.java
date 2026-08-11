package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-040 api-contract §"Errors": {@code 409 CONFLICT} — a compensation
 * is already open for this ticket and {@code sourceReference}. Mirrors
 * {@code ReconciliationCaseConflictException} (SPEC-TW-037): a concurrent
 * second attempt to compensate the same source reference is, by definition,
 * the same already-open compensation, so one guard and one exception cover
 * both the "attempt" and "source reference" conflict flavors the
 * api-contract names together. Never a version conflict — domain-rules
 * "cannot run arbitrary SQL or arbitrary state mutation": this SPEC never
 * touches the ticket's own optimistic-lock version.
 */
public class CompensationConflictException extends RuntimeException {

    public CompensationConflictException() {
        super("a compensation is already open for this ticket and source reference");
    }
}
