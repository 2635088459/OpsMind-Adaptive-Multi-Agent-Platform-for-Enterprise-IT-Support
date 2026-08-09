package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-037 api-contract §"Errors": {@code 409 CONFLICT} — a reconciliation
 * case is already open for this ticket and {@code sourceReference}. Covers
 * both the "attempt" and "source reference" conflict flavors the api-contract
 * names together: a concurrent second attempt to open a case for the same
 * source reference is, by definition, the same already-open case, so one
 * guard and one exception cover both. Never a version conflict — SPEC-TW-037
 * domain-rules: a reconciliation case "must not directly repair business
 * state," so it never touches the ticket's own optimistic-lock version.
 */
public class ReconciliationCaseConflictException extends RuntimeException {

    public ReconciliationCaseConflictException() {
        super("a reconciliation case is already open for this ticket and source reference");
    }
}
