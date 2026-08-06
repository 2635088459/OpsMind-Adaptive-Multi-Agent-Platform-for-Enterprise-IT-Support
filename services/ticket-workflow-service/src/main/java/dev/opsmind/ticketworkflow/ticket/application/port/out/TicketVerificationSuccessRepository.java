package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

public interface TicketVerificationSuccessRepository {

    TicketVerificationSuccessUpdateOutcome applyVerificationSuccess(TicketVerificationSuccessUpdate update);

    /** Flags an already-terminal ({@code FAILED}/{@code STALE}/already {@code CONFLICT}) attempt for reconciliation without silently overwriting it with a success outcome. Returns {@code false} if the row no longer belongs to {@code ticketId} (a cross-ticket anomaly the caller must not act on). */
    boolean markConflictRequiresReconciliation(TicketId ticketId, String verificationId, String conflictEventId);
}
