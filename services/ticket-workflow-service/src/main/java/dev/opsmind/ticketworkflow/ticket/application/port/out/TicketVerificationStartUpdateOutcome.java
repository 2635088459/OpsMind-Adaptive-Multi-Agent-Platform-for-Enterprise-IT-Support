package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/** Mirrors {@code TicketApprovalRequestUpdateOutcome}'s (SPEC-TW-014) reclassify-on-conflict shape. */
public sealed interface TicketVerificationStartUpdateOutcome
    permits TicketVerificationStartUpdateOutcome.Created,
            TicketVerificationStartUpdateOutcome.TicketMissing,
            TicketVerificationStartUpdateOutcome.VersionMismatch,
            TicketVerificationStartUpdateOutcome.InvalidState,
            TicketVerificationStartUpdateOutcome.NotAssigned,
            TicketVerificationStartUpdateOutcome.AttemptAlreadyActive {

    record Created(long newVersion) implements TicketVerificationStartUpdateOutcome {
    }

    record TicketMissing() implements TicketVerificationStartUpdateOutcome {
    }

    record VersionMismatch(long currentVersion) implements TicketVerificationStartUpdateOutcome {
    }

    record InvalidState(TicketStatus currentStatus) implements TicketVerificationStartUpdateOutcome {
    }

    record NotAssigned() implements TicketVerificationStartUpdateOutcome {
    }

    /** The partial unique index ({@code uq_verification_one_active_tool_result}) rejected the insert (defensive: the application layer's own {@code hasActiveAttempt} check should already prevent this). */
    record AttemptAlreadyActive() implements TicketVerificationStartUpdateOutcome {
    }
}
