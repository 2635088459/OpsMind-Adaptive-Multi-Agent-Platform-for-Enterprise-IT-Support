package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-041 api-contract §"Errors": {@code 404 NOT_FOUND} — {@code
 * sourceReference} does not match any known reconciliation case. domain-
 * rules "Repair must first produce a scan finding and repair plan before
 * controlled repair execution": this implementation resolves {@code
 * sourceReference} against the SPEC-TW-037 case ledger ({@code
 * ticket.ticket_phase10_open_reconciliation_case.id}) — the recovery entry
 * point every Phase 10 finding is expected to have opened a case through —
 * mirrors {@code ReplaySourceEventNotFoundException} (SPEC-TW-038).
 */
public class IntegrityRepairSourceNotFoundException extends RuntimeException {

    public IntegrityRepairSourceNotFoundException() {
        super("the reconciliation case referenced by sourceReference was not found");
    }
}
