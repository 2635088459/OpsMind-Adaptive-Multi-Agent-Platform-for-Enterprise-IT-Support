package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-041 api-contract §"Errors": {@code 409 CONFLICT} — a repair is
 * already open for this {@code sourceReference}. Mirrors {@code
 * ReplayEventConflictException} (SPEC-TW-038): a concurrent second repair
 * attempt against the same reconciliation case is, by definition, the same
 * already-open repair, so one guard and one exception cover both the
 * "attempt" and "source reference" conflict flavors the api-contract names
 * together.
 */
public class DataIntegrityRepairConflictException extends RuntimeException {

    public DataIntegrityRepairConflictException() {
        super("a data integrity repair is already open for this source reference");
    }
}
