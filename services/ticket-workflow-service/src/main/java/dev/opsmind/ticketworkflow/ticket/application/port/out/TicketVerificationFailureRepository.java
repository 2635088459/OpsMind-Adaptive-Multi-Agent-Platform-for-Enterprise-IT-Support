package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.UUID;

public interface TicketVerificationFailureRepository {

    /** SPEC-TW-024 domain-rules: "the third failure ... escalates" — the count of already-{@code FAILED} attempts in this resolution cycle, not including the one currently being applied. */
    int countFailedAttempts(TicketId ticketId, UUID resolutionCycleId);

    TicketVerificationFailureUpdateOutcome applyVerificationFailure(TicketVerificationFailureUpdate update);

    /** Flags an already-terminal-but-different (a {@code SUCCEEDED}/{@code STALE}/already {@code CONFLICT}) attempt for reconciliation without silently overwriting it with a failure outcome. Returns {@code false} if the row no longer belongs to {@code ticketId} (a cross-ticket anomaly the caller must not act on). */
    boolean markConflictRequiresReconciliation(TicketId ticketId, String verificationId, String conflictEventId);
}
