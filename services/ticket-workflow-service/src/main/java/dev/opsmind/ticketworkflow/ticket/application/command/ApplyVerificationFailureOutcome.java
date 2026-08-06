package dev.opsmind.ticketworkflow.ticket.application.command;

/** SPEC-TW-024 acceptance-criteria-driven outcome vocabulary (mirrors SPEC-TW-020's {@code ApplyToolExecutionFailedOutcome} shape). */
public enum ApplyVerificationFailureOutcome {
    APPLIED_RETRYABLE,
    APPLIED_ESCALATED,
    APPLIED_PIPELINE_FAILED,
    DUPLICATE,
    STALE,
    /** A conflicting terminal result (a success) was already recorded for this attempt; this failure event never silently overwrites it. */
    CONFLICT_REQUIRES_RECONCILIATION
}
